package com.nadeem.apkscope.sandbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import com.nadeem.apkscope.core.model.Recoverability
import com.nadeem.apkscope.core.model.SandboxError
import com.nadeem.apkscope.core.model.SandboxErrorCode
import com.nadeem.apkscope.core.model.SandboxSessionState
import com.nadeem.apkscope.core.model.SandboxPolicyType
import com.nadeem.apkscope.core.crossprofile.Handoff
import com.nadeem.apkscope.core.crossprofile.CrossProfileContract
import com.nadeem.apkscope.core.sandbox.PolicyEnforcer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Work-profile-side [PackageInstaller] session-commit and uninstall-result callback (checkpoint 4,
 * items 8/9/16) — the production replacement for the spike's `InstallResultReceiver`, which only
 * ever logged to `Evidence` and re-launched the system confirmation UI. This one **verifies**
 * (item 8's "the installed package name MUST match the analyzed package name — if it does not,
 * FAIL, do not launch") and reports the real outcome back to the personal side.
 *
 * Runs naturally as the Work Profile process without any extra plumbing: Android delivers a
 * `PackageInstaller`/uninstall result `PendingIntent` callback in whichever profile created the
 * session/request that it belongs to — since [SandboxWorkerService] always creates/commits/requests
 * from the Work-profile process, this receiver's instance is too.
 *
 * ANDROID PLATFORM LIMITATION found on the emulator this checkpoint (documented in
 * `V0.1_CHECKPOINT_4.md`): calling `context.startActivity()` directly on the `EXTRA_INTENT`
 * confirmation Intent creates the `PackageInstaller` confirmation task in the Work Profile but
 * does not bring it to the foreground display — background-activity-launch restrictions block a
 * `BroadcastReceiver` invoked by a system `PendingIntent` callback (not a live user gesture) from
 * forcing a cross-profile focus switch. The fix used here matches how real device-management apps
 * surface this: post a real notification whose `contentIntent` wraps the same confirmation
 * `Intent` — tapping a notification IS a genuine user gesture, which Android does allow to launch
 * across the profile boundary.
 */
class SandboxInstallResultReceiver : BroadcastReceiver() {
 companion object {
  const val ACTION_INSTALL_RESULT = "com.nadeem.apkscope.sandbox.action.INSTALL_RESULT"
  const val ACTION_UNINSTALL_RESULT = "com.nadeem.apkscope.sandbox.action.UNINSTALL_RESULT"
  const val ACTION_REQUIRED_CHANNEL_ID = "sandbox-action-required"
  const val NOTIFICATION_ID_INSTALL = 45
  const val NOTIFICATION_ID_UNINSTALL = 46

  fun notifyActionRequired(context: Context, notificationId: Int, title: String, text: String, confirmationIntent: Intent) {
   val manager = context.getSystemService(NotificationManager::class.java)
   manager.createNotificationChannel(NotificationChannel(ACTION_REQUIRED_CHANNEL_ID, "Sandbox action required", NotificationManager.IMPORTANCE_HIGH))
   val pendingIntent = PendingIntent.getActivity(
    context, notificationId, confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
   )
   val notification = Notification.Builder(context, ACTION_REQUIRED_CHANNEL_ID)
    .setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle(title).setContentText(text)
    .setContentIntent(pendingIntent).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS)
    .build()
   manager.notify(notificationId, notification)
   PackageInstallerDiagnostics.log("confirmation notification posted id=$notificationId available=${InstallConfirmationAvailability.available(context)}")
  }

  @Suppress("DEPRECATION")
  fun notifyUninstallForPackage(context: Context, packageName: String) {
   val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).setData(android.net.Uri.parse("package:$packageName")).putExtra(Intent.EXTRA_RETURN_RESULT, true)
   notifyActionRequired(context, NOTIFICATION_ID_UNINSTALL, "APK Scope · Sandbox", "Tap to confirm removing this app from the Sandbox.", intent)
  }
 }

 override fun onReceive(context: Context, intent: Intent) {
  // Checkpoint 4.1: recording durable evidence is a suspend Room write — `goAsync()` keeps this
  // receiver's process alive long enough for it to complete before Android may reclaim it, exactly
  // the guarantee "evidence survives Work service restart / temporary inability to report" needs.
  val pendingResult = goAsync()
  CoroutineScope(Dispatchers.IO).launch {
   try {
    when (intent.action) {
     ACTION_INSTALL_RESULT -> InstallAttemptStore.mutex.withLock { handleInstallResult(context, intent) }
     ACTION_UNINSTALL_RESULT -> handleUninstallResult(context, intent)
    }
   } finally {
    pendingResult.finish()
   }
  }
 }

 private suspend fun handleInstallResult(context: Context, intent: Intent) {
  val installSessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
  val prefs = context.getSharedPreferences(SandboxWorkerService.PREFS, Context.MODE_PRIVATE)
  val sessionId = InstallAttemptStore.sessionFor(context, installSessionId)
   ?: prefs.getString("session_$installSessionId", null)
  val attemptId = InstallAttemptStore.attemptFor(context, installSessionId)
  val currentAttempt = sessionId?.let { InstallAttemptStore.current(context, it) }
  val expectedPackage = prefs.getString("package_$installSessionId", null)
  val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
  // Checkpoint 5.2, item 3: capture every returned status/extra from the commit callback, exactly
  // as it arrived — this is the one place that can prove or disprove whether Android ever actually
  // handed back STATUS_PENDING_USER_ACTION with a usable EXTRA_INTENT, instead of assuming.
  logCommitExtras(intent, installSessionId)
  if (sessionId == null) {
   PackageInstallerDiagnostics.log("handleInstallResult: no sessionId on file for installSessionId=$installSessionId — dropping (stale or foreign session)")
   return
  }
  // A retry supersedes the old PackageInstaller session. Android may still deliver its callback
  // after the new session is staged; never let that callback overwrite the active attempt.
  if (currentAttempt != null && attemptId != currentAttempt) {
   PackageInstallerDiagnostics.log("handleInstallResult: stale callback ignored installSessionId=$installSessionId attempt=$attemptId currentAttempt=$currentAttempt session=$sessionId")
   InstallAttemptStore.removeBinding(context, installSessionId)
   return
  }

  if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
   // The callback is terminal for this PackageInstaller session. Remove all per-session
   // bookkeeping so a later process restart cannot accidentally associate a stale callback with
   // the current sandbox session.
   context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID_INSTALL)
   PackageInstallerDiagnostics.log("final callback mySessions=${context.packageManager.packageInstaller.mySessions.map { it.sessionId }}")
  }
  when (com.nadeem.apkscope.core.model.InstallLifecycle.outcome(status)) {
   com.nadeem.apkscope.core.model.InstallLifecycle.Outcome.PENDING_CONFIRMATION -> {
    val confirmation = extractConfirmationIntent(intent)
    if (confirmation == null || !InstallConfirmationAvailability.available(context)) {
     context.packageManager.packageInstaller.getSessionInfo(installSessionId)?.let {
      context.packageManager.packageInstaller.abandonSession(installSessionId)
     }
     report(context, sessionId, SandboxStatusReport(sessionId, SandboxSessionState.FAILED, installSessionId = installSessionId, installAttemptId = attemptId,
      error = SandboxError(SandboxErrorCode.INSTALL_FAILED,
       "Android installation confirmation is unavailable. Enable Work Profile notifications and start a new sandbox session.",
       "confirmationIntentPresent=${confirmation != null}", Recoverability.REQUIRES_USER_ACTION)), expectedPackage)
    } else {
     // Durable pending marker; the system notification owns the actual permission-bearing Intent.
     check(prefs.edit().putBoolean("pending_$installSessionId", true).commit())
     // Keep the notification as the single user-action entry point. Starting the same
     // confirmation Intent from the receiver as well creates two competing PackageInstaller UI
     // paths on some Android builds.
     notifyActionRequired(context, NOTIFICATION_ID_INSTALL, "APK Scope · Sandbox", "Tap to confirm installing this app in the Sandbox.", confirmation)
    }
   }
   com.nadeem.apkscope.core.model.InstallLifecycle.Outcome.VERIFY_PACKAGE -> {
    // PackageInstaller can report success before every PackageManager client observes the new
    // package. Retry the authoritative lookup briefly instead of turning that visibility window
    // into a terminal PACKAGE_MISMATCH and leaving the Personal session stale.
    val installedInfo = awaitInstalledPackage(context, expectedPackage)
    if (installedInfo == null || installedInfo.packageName != expectedPackage) {
     report(context, sessionId, SandboxStatusReport(sessionId, SandboxSessionState.FAILED, installSessionId = installSessionId, installAttemptId = attemptId,
      error = SandboxError(SandboxErrorCode.PACKAGE_MISMATCH, "The installed app does not match the app that was analyzed.", "expected=$expectedPackage actual=${installedInfo?.packageName}", Recoverability.TERMINAL)), expectedPackage)
    } else {
     val admin = android.content.ComponentName(context, com.nadeem.apkscope.spike.SandboxAdminReceiver::class.java)
     val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
     val enforcements = mutableListOf<com.nadeem.apkscope.core.model.PolicyEnforcementResult>()
     if (dpm != null && dpm.isProfileOwnerApp(context.packageName)) {
      val enforcer = PolicyEnforcer(dpm, admin)
      enforcer.unsuspendPackage(expectedPackage)
      enforcements += enforcer.denyRuntimePermission(SandboxPolicyType.CAMERA_RUNTIME_PERMISSION_DENIAL, expectedPackage, "android.permission.CAMERA")
      enforcements += enforcer.denyRuntimePermission(SandboxPolicyType.MICROPHONE_RUNTIME_PERMISSION_DENIAL, expectedPackage, "android.permission.RECORD_AUDIO")
      enforcements += enforcer.denyRuntimePermission(SandboxPolicyType.LOCATION_RUNTIME_PERMISSION_DENIAL, expectedPackage, "android.permission.ACCESS_FINE_LOCATION")
     }
     @Suppress("DEPRECATION") val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) installedInfo.longVersionCode else installedInfo.versionCode.toLong()
     report(context, sessionId, SandboxStatusReport(sessionId, SandboxSessionState.INSTALLED, installSessionId = installSessionId, installAttemptId = attemptId, installedVersionCode = versionCode, enforcements = enforcements), expectedPackage)
    }
   }
   com.nadeem.apkscope.core.model.InstallLifecycle.Outcome.CANCELLED -> report(context, sessionId, SandboxStatusReport(sessionId, SandboxSessionState.FAILED, installSessionId = installSessionId, installAttemptId = attemptId,
    error = SandboxError(SandboxErrorCode.INSTALL_USER_CANCELLED, "Installation was cancelled.", intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), Recoverability.RETRYABLE)), expectedPackage)
   else -> report(context, sessionId, SandboxStatusReport(sessionId, SandboxSessionState.FAILED, installSessionId = installSessionId, installAttemptId = attemptId,
    error = SandboxError(SandboxErrorCode.INSTALL_FAILED, "Installation failed.", "status=$status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}", Recoverability.RETRYABLE)), expectedPackage)
  }
  // Persist the terminal fact before removing the callback mapping. A process death during the
  // write must leave enough identity for Android's redelivery to finish recording the outcome.
  if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) InstallAttemptStore.removeBinding(context, installSessionId)
 }

 private suspend fun awaitInstalledPackage(context: Context, packageName: String?, attempts: Int = 20): android.content.pm.PackageInfo? {
  if (packageName == null) return null
  repeat(attempts) { attempt ->
   try {
    return context.packageManager.getPackageInfo(packageName, 0)
   } catch (_: PackageManager.NameNotFoundException) {
    if (attempt < attempts - 1) delay(250)
   }
  }
  return null
 }

 private suspend fun handleUninstallResult(context: Context, intent: Intent) {
  val sessionId = intent.getStringExtra(SandboxWorkerService.EXTRA_SESSION_ID)
  val targetPackage = intent.getStringExtra(SandboxWorkerService.EXTRA_PACKAGE_NAME)
  val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
  logCommitExtras(intent, intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1))
  if (sessionId == null || targetPackage == null) {
   PackageInstallerDiagnostics.log("handleUninstallResult: missing sessionId/targetPackage extras — dropping")
   return
  }

  when (status) {
   PackageInstaller.STATUS_PENDING_USER_ACTION -> {
    extractConfirmationIntent(intent)?.let {
     launchConfirmationDirectly(context, it)
     notifyActionRequired(context, NOTIFICATION_ID_UNINSTALL, "APK Scope · Sandbox", "Tap to confirm removing this app from the Sandbox.", it)
    }
   }
   PackageInstaller.STATUS_SUCCESS -> {
    val actuallyGone = try { context.packageManager.getPackageInfo(targetPackage, 0); false } catch (_: PackageManager.NameNotFoundException) { true }
    report(context, sessionId, buildCleanupReport(sessionId, targetPackage, apkRemoved = actuallyGone), targetPackage)
   }
   PackageInstaller.STATUS_FAILURE_ABORTED -> report(context, sessionId, SandboxStatusReport(sessionId, SandboxSessionState.CLEANUP_REQUIRED,
    error = SandboxError(SandboxErrorCode.UNINSTALL_USER_CANCELLED, "Removal was cancelled.", intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), Recoverability.REQUIRES_USER_ACTION)), targetPackage)
   else -> report(context, sessionId, SandboxStatusReport(sessionId, SandboxSessionState.CLEANUP_REQUIRED,
    error = SandboxError(SandboxErrorCode.UNINSTALL_FAILED, "Removal failed.", "status=$status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}", Recoverability.RETRYABLE)), targetPackage)
  }
 }

 /**
  * Item 3: log every extra the platform returned on this commit/uninstall callback — presence
  * only, never the confirmation Intent's own content (it may carry a content:// grant we don't
  * want dumped into logcat). This is deliberately called before any early-return above, so a
  * stale/unrecognized session's callback is still fully visible rather than silently dropped.
  */
 private fun logCommitExtras(intent: Intent, installSessionId: Int) {
  val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
  // PackageInstaller.EXTRA_LEGACY_STATUS ("android.content.pm.extra.LEGACY_STATUS") is a real extra
  // the platform sets on this callback but is not exposed as a public SDK constant — read by its
  // literal string per item 3's requirement to capture it anyway.
  val legacyStatus = intent.getIntExtra("android.content.pm.extra.LEGACY_STATUS", Int.MIN_VALUE)
  PackageInstallerDiagnostics.log(
   "commit callback received action=${intent.action} installSessionId=$installSessionId " +
    "EXTRA_STATUS=$status EXTRA_LEGACY_STATUS=$legacyStatus " +
    "EXTRA_STATUS_MESSAGE=${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)} " +
    "EXTRA_PACKAGE_NAME=${intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)} " +
    "EXTRA_SESSION_ID_present=${intent.hasExtra(PackageInstaller.EXTRA_SESSION_ID)} " +
    "EXTRA_INTENT_present=${extractConfirmationIntent(intent) != null}",
  )
 }

 private fun extractConfirmationIntent(intent: Intent): Intent? =
  if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
  else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)

 private fun launchConfirmationDirectly(context: Context, confirmationIntent: Intent) {
  try {
   val direct = Intent(confirmationIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
   context.startActivity(direct)
   PackageInstallerDiagnostics.log("launchConfirmationDirectly invoked successfully")
  } catch (e: Exception) {
   PackageInstallerDiagnostics.log("launchConfirmationDirectly suppressed or failed: $e")
  }
 }

 /** Same durable-first, best-effort-push-second shape as `SandboxWorkerService.report` (checkpoint 4.1 §5) — this is the other of the two work-side call sites that ever produce a status report. */
 private suspend fun report(context: Context, sessionId: String, statusReport: SandboxStatusReport, targetPackage: String? = null) {
  // Checkpoint 5.3 root-cause fix — see WorkSessionTeardown's doc comment: an install
  // denial/failure delivered straight to this receiver (never passing back through
  // SandboxWorkerService.report) is exactly the path that left Work's VPN orphaned.
  if (!WorkEvidenceStore(context).recordFact(sessionId, targetPackage.orEmpty(), statusReport)) return
  WorkSessionTeardown.tearDownIfEarlyTermination(context, sessionId, statusReport.state)
  val file = File(context.filesDir, "reports/$sessionId-${System.nanoTime()}.json")
  file.parentFile?.mkdirs()
  file.writeText(statusReport.toJson().toString())
  val action = if (statusReport.error != null) CrossProfileContract.ACTION_SANDBOX_ERROR else CrossProfileContract.ACTION_SANDBOX_READY
  try { Handoff.send(context, file, action, sessionId, SANDBOX_FILE_PROVIDER_AUTHORITY) } catch (_: Exception) { /* best-effort only */ }
 }
}
