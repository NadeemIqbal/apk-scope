package com.nadeem.apkscope.sandbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.nadeem.apkscope.core.crossprofile.CrossProfileContract
import com.nadeem.apkscope.core.crossprofile.Handoff
import com.nadeem.apkscope.core.model.EnforcementStatus
import com.nadeem.apkscope.core.model.PolicyEnforcementResult
import com.nadeem.apkscope.core.model.Recoverability
import com.nadeem.apkscope.core.model.SandboxError
import com.nadeem.apkscope.core.model.SandboxErrorCode
import com.nadeem.apkscope.core.model.SandboxSessionState
import com.nadeem.apkscope.core.sandbox.PolicyEnforcer
import com.nadeem.apkscope.poc.apkrepack.FridaChannelConfig
import com.nadeem.apkscope.poc.apkrepack.FridaTrafficMonitor
import com.nadeem.apkscope.spike.SandboxAdminReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * Checkpoint 4, items 3/4/5/6/7/8/13/14/15/16 — the real work-profile-side lifecycle sequence.
 * Everything DPM/VPN/`PackageInstaller`-related in this whole checkpoint that must run as the
 * Work Profile's own process happens here (never in the personal-profile `SandboxSessionCoordinator`,
 * which cannot legally perform any of it — DevicePolicyManager profile-owner authority and a
 * `VpnService` tunnel are both strictly per-user/per-profile in Android).
 *
 * A foreground `Service`, not the transient `SandboxWorkerActivity` that starts it, because this
 * sequence is genuinely asynchronous (VPN establishment takes a moment; `clearApplicationUserData`
 * is itself callback-based) and must survive past that Activity's own immediate `finish()`.
 *
 * Deliberately stateless across restarts beyond a tiny `SharedPreferences` map from a
 * `PackageInstaller` session id to the sandbox `sessionId`/`packageName` it belongs to (read by
 * [SandboxInstallResultReceiver]) — the *authoritative* session record lives in the **personal**
 * profile's own Room database, which this process cannot and does not touch (Android gives each
 * profile entirely separate private app storage, even for the same APK).
 */
class SandboxWorkerService : Service() {
 private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
 private val runningJobs = AtomicInteger(0)
 private val installMutex = Mutex()
 private val admin get() = ComponentName(this, SandboxAdminReceiver::class.java)
 private val dpm get() = getSystemService(DevicePolicyManager::class.java)
 private val evidenceStore by lazy { WorkEvidenceStore(this) }

 companion object {
  const val EXTRA_ACTION = "action"
  const val EXTRA_SESSION_ID = "sessionId"
  const val EXTRA_APK_PATH = "apkPath"
  const val EXTRA_INSTALL_SESSION_ID = "installSessionId"
  const val EXTRA_PACKAGE_NAME = "packageName"
  private const val CHANNEL_ID = "sandbox-worker"
  private const val NOTIFICATION_ID = 44
  const val PREFS = "sandbox_worker"
 }

 override fun onBind(intent: Intent?): IBinder? = null

 override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
  val manager = getSystemService(NotificationManager::class.java)
  manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Sandbox setup", NotificationManager.IMPORTANCE_LOW))
  startForeground(
   NOTIFICATION_ID,
   Notification.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_lock_lock)
    .setContentTitle("APK Scope · Sandbox")
    .setSubText("Sandbox")
    .setContentText("Preparing Sandbox…")
    .build(),
  )

  val action = intent?.getStringExtra(EXTRA_ACTION)
  val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
  com.nadeem.apkscope.core.crossprofile.HandoffDiagnostics.log("worker_service_started action=$action session=$sessionId")
  if (action == null || sessionId == null) {
   if (runningJobs.get() == 0) stopSelf()
   return START_NOT_STICKY
  }

  runningJobs.incrementAndGet()
  scope.launch {
   try {
	    when (action) {
	     CrossProfileContract.ACTION_SANDBOX_IMPORT_APK -> runPrepareSequence(sessionId, requireNotNull(intent.getStringExtra(EXTRA_APK_PATH)))
	     CrossProfileContract.ACTION_PATCHED_APK_INSTALL -> runPatchedApkInstall(sessionId, requireNotNull(intent.getStringExtra(EXTRA_APK_PATH)))
	     CrossProfileContract.ACTION_CONTINUE_INSTALL -> runContinueInstall(sessionId, intent.getIntExtra(EXTRA_INSTALL_SESSION_ID, -1))
     CrossProfileContract.ACTION_REINSTALL -> runReinstall(sessionId, intent.getIntExtra(EXTRA_INSTALL_SESSION_ID, -1))
     CrossProfileContract.ACTION_END_SESSION -> runEndSessionSequence(sessionId, requireNotNull(intent.getStringExtra(EXTRA_PACKAGE_NAME)))
     CrossProfileContract.ACTION_ACK_RUNTIME_ARTIFACT -> runAckRuntimeArtifact(sessionId)
     CrossProfileContract.ACTION_ACK_ANDROID_EVIDENCE -> runAckAndroidEvidence(sessionId)
    }
   } catch (e: Exception) {
    report(sessionId, SandboxStatusReport(sessionId, SandboxSessionState.FAILED, error = SandboxError(SandboxErrorCode.UNKNOWN, "Something went wrong preparing the sandbox.", e.toString(), Recoverability.RETRYABLE)))
   } finally {
    if (runningJobs.decrementAndGet() == 0) {
     stopSelf()
    }
   }
  }
  return START_NOT_STICKY
	 }

	 /** Frida patching flow: install the already patched APK directly inside Work Profile. */
	 private suspend fun runPatchedApkInstall(sessionId: String, apkPath: String) {
	  val apkFile = File(apkPath)
	  if (!apkFile.exists()) {
	   PackageInstallerDiagnostics.log("patched install failed: missing apk path=$apkPath session=$sessionId")
	   return
	  }
	  @Suppress("DEPRECATION")
	  val archiveInfo = packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
	  val targetPackage = archiveInfo?.packageName
	  if (targetPackage == null) {
	   PackageInstallerDiagnostics.log("patched install failed: unreadable apk path=$apkPath session=$sessionId")
	   return
	  }
		  FridaChannelConfig.storeToken(this, sessionId, targetPackage, FridaChannelConfig.generateToken())
		  PackageInstallerDiagnostics.log("stored Work-profile target-bound Frida channel token for session=$sessionId package=$targetPackage")
	  ensureWorkProfilePermissions()
	  if (!packageManager.canRequestPackageInstalls()) {
	   PackageInstallerDiagnostics.log("patched install blocked: canRequestPackageInstalls=false session=$sessionId package=$targetPackage")
	   return
	  }
	  val installer = packageManager.packageInstaller
	  val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
	  if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
	  val installSessionId = installer.createSession(params)
	  getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
	   .putString("session_$installSessionId", sessionId)
	   .putString("package_$installSessionId", targetPackage)
	   .apply()
	  installer.openSession(installSessionId).use { session ->
	   session.openWrite("base.apk", 0, apkFile.length()).use { out ->
	    apkFile.inputStream().use { it.copyTo(out) }
	    session.fsync(out)
	   }
	   val callback = PendingIntent.getBroadcast(
	    this,
	    installSessionId,
	    Intent(this, SandboxInstallResultReceiver::class.java).setAction(SandboxInstallResultReceiver.ACTION_INSTALL_RESULT),
	    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
	   )
	   PackageInstallerDiagnostics.log("patched install commit invoked installSessionId=$installSessionId session=$sessionId package=$targetPackage")
	   session.commit(callback.intentSender)
	  }
	 }

	 /** Item 4/5/6/8: real preflight + policy application + VPN establishment + a *created but uncommitted* PackageInstaller session — the system confirmation dialog only appears once [runContinueInstall] later commits it. */
 private suspend fun runPrepareSequence(sessionId: String, apkPath: String) {
  val apkFile = File(apkPath)
  if (!apkFile.exists()) {
   report(sessionId, SandboxStatusReport(sessionId, SandboxSessionState.FAILED, error = SandboxError(SandboxErrorCode.TEMP_APK_MISSING, "The APK copy for this session is missing.", "work-side path=$apkPath", Recoverability.TERMINAL)))
   return
  }
  if (dpm?.isProfileOwnerApp(packageName) != true) {
   report(sessionId, SandboxStatusReport(sessionId, SandboxSessionState.FAILED, error = SandboxError(SandboxErrorCode.PROFILE_OWNER_INVALID, "This device's sandbox profile is not correctly configured.", "isProfileOwnerApp=false", Recoverability.REQUIRES_USER_ACTION)))
   return
  }

  @Suppress("DEPRECATION")
  val archiveInfo = packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
  val targetPackage = archiveInfo?.packageName
  if (targetPackage == null) {
   report(sessionId, SandboxStatusReport(sessionId, SandboxSessionState.FAILED, error = SandboxError(SandboxErrorCode.INSTALL_FAILED, "This file could not be read as an APK.", "getPackageArchiveInfo returned null", Recoverability.TERMINAL)))
   return
  }

  // Generate the token only after the APK has crossed into the Work profile. It is kept in this
  // profile's private storage and is never recoverable by unpacking the target APK.
  FridaChannelConfig.storeToken(this, sessionId, targetPackage, FridaChannelConfig.generateToken())
  PackageInstallerDiagnostics.log("stored Work-profile target-bound Frida channel token for session=$sessionId package=$targetPackage")

  WorkAndroidEvidenceSink.currentSessionId = sessionId
  WorkAndroidEvidenceSink.currentTargetPackage = targetPackage

  val enforcer = PolicyEnforcer(requireNotNull(dpm), admin)
  val enforcements = mutableListOf<PolicyEnforcementResult>()
  val vpnLockdown = enforcer.enableAlwaysOnVpnLockdown(packageName)
  enforcements += vpnLockdown
  enforcements += enforcer.enableNetworkLogging()

  val vpnLockdownEnforced = vpnLockdown.status == EnforcementStatus.ENFORCED
  var forwardingActive = false
  if (vpnLockdownEnforced) {
   val consent = VpnService.prepare(this)
   if (consent == null) {
    startForegroundService(
     Intent(this, SandboxVpnService::class.java).setAction(SandboxVpnService.ACTION_ESTABLISH)
      // Checkpoint 5, item 4/28: session attribution is established here, at the same real
      // tunnel-establish call item 28 already requires before every launch — never inferred later.
      .putExtra(SandboxVpnService.EXTRA_SESSION_ID, sessionId)
      .putExtra(SandboxVpnService.EXTRA_PACKAGE_NAME, targetPackage),
    )
    var waitedMs = 0
    while (!SandboxVpnService.isForwardingActive && waitedMs < 4000) { delay(100); waitedMs += 100 }
    forwardingActive = SandboxVpnService.isForwardingActive
   }
  }
  if (!forwardingActive) {
   // Fail closed (item 6) — never proceed to an installable state without real network isolation.
   report(sessionId, SandboxStatusReport(sessionId, SandboxSessionState.FAILED, enforcements = enforcements,
    error = SandboxError(SandboxErrorCode.NETWORK_ISOLATION_UNAVAILABLE, "Monitored network isolation could not be established for this session.", "vpnLockdownEnforced=$vpnLockdownEnforced forwardingActive=$forwardingActive", Recoverability.RETRYABLE)), targetPackage)
   return
  }

   ensureWorkProfilePermissions()

  // Checkpoint 5.2, item 2: the exact runtime prerequisite state, logged rather than assumed — see
  // PackageInstallerDiagnostics's own doc comment for why this checkpoint requires reading these
  // back instead of inferring them.
  PackageInstallerDiagnostics.logPrerequisites(this, sessionId, targetPackage)

  if (!packageManager.canRequestPackageInstalls()) {
   report(sessionId, SandboxStatusReport(sessionId, SandboxSessionState.FAILED, enforcements = enforcements,
    error = SandboxError(SandboxErrorCode.INSTALL_FAILED, "This device has not allowed the sandbox to install apps yet.", "canRequestPackageInstalls=false", Recoverability.REQUIRES_USER_ACTION)), targetPackage)
   return
  }

  if (!InstallConfirmationAvailability.available(this)) {
   report(sessionId, SandboxStatusReport(sessionId, SandboxSessionState.FAILED,
    error = SandboxError(SandboxErrorCode.INSTALL_FAILED,
     "Enable APK Scope notifications in the Work Profile app settings, then start a new sandbox session. Notifications are required for Android installation confirmation.",
     "installation confirmation notification unavailable", Recoverability.REQUIRES_USER_ACTION)), targetPackage)
   return
  }
  val installer = packageManager.packageInstaller
  val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
  if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
  // SessionParams exposes installerPackageName/packageSource as setter-only (no getter) fields —
  // there is nothing to read back off `params` itself; the installer identity that actually matters
  // (which package this process runs as) is logged instead.
  PackageInstallerDiagnostics.log("createSession requested mode=MODE_FULL_INSTALL requireUserAction=${if (Build.VERSION.SDK_INT >= 31) "USER_ACTION_REQUIRED" else "unset (API<31)"} callingPackage=$packageName")
  val installSessionId = installer.createSession(params)
  PackageInstallerDiagnostics.log("createSession returned installSessionId=$installSessionId mySessions=${installer.mySessions.size}")
  installer.openSession(installSessionId).use { session ->
   session.openWrite("base.apk", 0, apkFile.length()).use { out -> apkFile.inputStream().use { it.copyTo(out) }; session.fsync(out) }
  }
  PackageInstallerDiagnostics.log("openWrite+fsync completed installSessionId=$installSessionId bytes=${apkFile.length()}")
  getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
   .putString("apk_$sessionId", apkPath)
   .putString("session_$installSessionId", sessionId)
   .putString("package_$installSessionId", targetPackage)
   .apply()

  report(sessionId, SandboxStatusReport(sessionId, SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION, installSessionId = installSessionId, enforcements = enforcements), targetPackage)
 }

 /** Item 8: the deferred half — only reached once the personal-side user has tapped "Continue Installation". Committing here is what actually shows Android's system confirmation UI. */
 private suspend fun runContinueInstall(sessionId: String, installSessionId: Int) {
  installMutex.withLock {
  if (installSessionId < 0) {
   report(sessionId, SandboxStatusReport(sessionId, SandboxSessionState.FAILED, error = SandboxError(SandboxErrorCode.INSTALL_FAILED, "The installation could not be resumed.", "missing installSessionId", Recoverability.TERMINAL)))
   return
  }
  val targetPackage = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("package_$installSessionId", null)
  ensureWorkProfilePermissions()
  if (!InstallConfirmationAvailability.available(this)) {
   packageManager.packageInstaller.getSessionInfo(installSessionId)?.let {
    packageManager.packageInstaller.abandonSession(installSessionId)
   }
   report(sessionId, SandboxStatusReport(sessionId, SandboxSessionState.FAILED,
    error = SandboxError(SandboxErrorCode.INSTALL_FAILED,
     "Enable APK Scope notifications in the Work Profile app settings, then start a new sandbox session. Notifications are required for Android installation confirmation.",
     "installation confirmation notification unavailable", Recoverability.REQUIRES_USER_ACTION)), targetPackage)
   return
  }
  val installer = packageManager.packageInstaller
  val callback = PendingIntent.getBroadcast(
   this, installSessionId,
   Intent(this, SandboxInstallResultReceiver::class.java).setAction(SandboxInstallResultReceiver.ACTION_INSTALL_RESULT),
   PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
  )
  try {
   PackageInstallerDiagnostics.logPrerequisites(this, sessionId, targetPackage ?: "?")
   report(sessionId, SandboxStatusReport(sessionId, SandboxSessionState.INSTALLING, installSessionId = installSessionId), targetPackage)
   installer.openSession(installSessionId).use {
    PackageInstallerDiagnostics.log("commit invoked installSessionId=$installSessionId sessionInfo.isActive=${installer.getSessionInfo(installSessionId)?.isActive}")
    it.commit(callback.intentSender)
   }
   PackageInstallerDiagnostics.log("commit returned (call itself did not throw) installSessionId=$installSessionId — the real outcome arrives asynchronously via the IntentSender callback, see SandboxInstallResultReceiver's own log lines")
   // INSTALLING is persisted before commit so it cannot overwrite a fast final callback.
  } catch (e: Exception) {
   PackageInstallerDiagnostics.log("commit threw installSessionId=$installSessionId detail=$e")
   report(sessionId, SandboxStatusReport(sessionId, SandboxSessionState.FAILED, error = SandboxError(SandboxErrorCode.INSTALL_FAILED, "The installation could not be started.", e.toString(), Recoverability.RETRYABLE)), targetPackage)
  }
  }
 }

 /**
  * Replaces a stale or already-sealed PackageInstaller session with a fresh session built from
  * the APK retained for this sandbox. Android does not reliably allow a previously committed
  * session to be committed again, so a retry must abandon the old session and stage/commit a new
  * one. The personal screen stays on this route throughout; only INSTALLED ends the retry state.
  */
 private suspend fun runReinstall(sessionId: String, previousInstallSessionId: Int) {
  installMutex.withLock {
  val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  val apkPath = prefs.getString("apk_$sessionId", null)
  val apkFile = apkPath?.let(::File)
  if (apkFile == null || !apkFile.exists()) {
   report(sessionId, SandboxStatusReport(sessionId, SandboxSessionState.FAILED,
    error = SandboxError(SandboxErrorCode.TEMP_APK_MISSING, "The APK copy for this session is no longer available.", "work-side path=$apkPath", Recoverability.RETRYABLE)))
   return@withLock
  }

  @Suppress("DEPRECATION")
  val targetPackage = packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)?.packageName
  if (targetPackage == null) {
   report(sessionId, SandboxStatusReport(sessionId, SandboxSessionState.FAILED,
    error = SandboxError(SandboxErrorCode.INSTALL_FAILED, "This file could not be read as an APK.", "getPackageArchiveInfo returned null", Recoverability.RETRYABLE)))
   return@withLock
  }
  if (previousInstallSessionId >= 0) {
   packageManager.packageInstaller.getSessionInfo(previousInstallSessionId)?.let {
    packageManager.packageInstaller.abandonSession(previousInstallSessionId)
   }
   getSystemService(NotificationManager::class.java).cancel(SandboxInstallResultReceiver.NOTIFICATION_ID_INSTALL)
   prefs.edit().remove("pending_$previousInstallSessionId").remove("session_$previousInstallSessionId").remove("package_$previousInstallSessionId").apply()
  }
  // Never treat an existing package as proof that this session's repacked APK is installed.
  // The package may be a stale APK from an earlier session, with an older target-bound Frida
  // token. Always create a fresh PackageInstaller session here; completion is reported only by
  // the installer callback after Android has actually accepted this APK.

  ensureWorkProfilePermissions()
  if (!InstallConfirmationAvailability.available(this)) {
   report(sessionId, SandboxStatusReport(sessionId, SandboxSessionState.FAILED,
    error = SandboxError(SandboxErrorCode.INSTALL_FAILED,
     "Enable APK Scope notifications in the Work Profile app settings, then tap Reinstall again.",
     "installation confirmation notification unavailable", Recoverability.REQUIRES_USER_ACTION)), targetPackage)
   return@withLock
  }

  val installer = packageManager.packageInstaller
  val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
  if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
  val installSessionId = installer.createSession(params)
  try {
   installer.openSession(installSessionId).use { session ->
    session.openWrite("base.apk", 0, apkFile.length()).use { out ->
     apkFile.inputStream().use { it.copyTo(out) }
     session.fsync(out)
    }
   }
   prefs.edit()
    .putString("session_$installSessionId", sessionId)
    .putString("package_$installSessionId", targetPackage)
    .apply()
   val callback = PendingIntent.getBroadcast(
    this,
    installSessionId,
    Intent(this, SandboxInstallResultReceiver::class.java).setAction(SandboxInstallResultReceiver.ACTION_INSTALL_RESULT),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
   )
   report(sessionId, SandboxStatusReport(sessionId, SandboxSessionState.INSTALLING, installSessionId = installSessionId), targetPackage)
   installer.openSession(installSessionId).use { session -> session.commit(callback.intentSender) }
   PackageInstallerDiagnostics.log("reinstall commit invoked previous=$previousInstallSessionId new=$installSessionId session=$sessionId package=$targetPackage")
  } catch (e: Exception) {
   installer.getSessionInfo(installSessionId)?.let { installer.abandonSession(installSessionId) }
   prefs.edit().remove("session_$installSessionId").remove("package_$installSessionId").apply()
   report(sessionId, SandboxStatusReport(sessionId, SandboxSessionState.FAILED,
    error = SandboxError(SandboxErrorCode.INSTALL_FAILED, "The installation could not be restarted.", e.toString(), Recoverability.RETRYABLE)), targetPackage)
  }
 }
 }

 /** Items 13/14/15/16: stop the sandboxed app, clear its data (waiting for the real completion callback), then request its removal — every step requires real, supported Android/user interaction, never automated. */
 private suspend fun runEndSessionSequence(sessionId: String, targetPackage: String) {
  // Revoke the live authenticated socket before removing the credential. Clearing preferences
  // alone does not invalidate an already-connected client or the in-memory expected token.
  FridaTrafficMonitor.shared.stop()
  FridaChannelConfig.clearToken(this, sessionId)
  // Checkpoint 5, item 17/28/34: stop monitoring and finalize the runtime-observation summary
  // *first* — "stop monitoring, finalize observed network activity, clear temporary app data and
  // remove the sandboxed APK" is the promised order (item 34), and it stays a genuinely separate
  // concern from VPN-readiness verification (item 28) — this only triggers and awaits
  // SandboxVpnService.closeAll()'s own internal stop-forwarding -> flush -> summarize ordering, it
  // does not itself perform any of those steps.
  val hadActiveSession = SandboxVpnService.isForwardingActive
  val vpnClosed = WorkSessionTeardown.closeAndAwait(this, expectedSessionId = sessionId)
  if (!vpnClosed) {
   Log.e("ApkScopeNet", "runEndSessionSequence: VPN did not close cleanly, session=$sessionId")
  }
  if (hadActiveSession) {
   var waitedMs = 0
   while (SandboxVpnService.lastClosedSummary == null && waitedMs < 5000) { delay(100); waitedMs += 100 }
   val summary = SandboxVpnService.lastClosedSummary
   if (summary == null) {
    Log.w("ApkScopeNet", "runEndSessionSequence: timed out waiting for runtime-observation summary, session=$sessionId")
   } else {
    Log.i("ApkScopeNet", "runEndSessionSequence: runtime summary ready, session=$sessionId connections=${summary.connectionCount} dnsQueries=${summary.dnsQueryCount} dropped=${summary.droppedObservationCount}")
   }
  }

  val enforcer = PolicyEnforcer(requireNotNull(dpm), admin)
  // Item 14: the strongest verified supported mechanism — Android does not guarantee this kills
  // the process, only that the package is suspended from user-initiated launch/use.
  val suspendResult = enforcer.suspendPackage(targetPackage)

  val dataClearRequestedAt = System.currentTimeMillis()
  val dataClearResult = clearApplicationData(targetPackage)
  val dataClearCompletedAt = System.currentTimeMillis()

  report(sessionId, SandboxStatusReport(
   sessionId, SandboxSessionState.CLEARING_DATA, enforcements = listOf(suspendResult),
   dataClearRequestedAtEpochMs = dataClearRequestedAt, dataClearCompletedAtEpochMs = dataClearCompletedAt, dataClearResult = dataClearResult,
  ), targetPackage)

  val stillInstalled = try { packageManager.getPackageInfo(targetPackage, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
  if (!stillInstalled) {
   // Already gone (item 20's "package already removed") — skip straight to the same cleanup
   // reporting `SandboxInstallResultReceiver` would otherwise perform after a real uninstall result.
   report(sessionId, buildCleanupReport(sessionId, targetPackage, apkRemoved = true), targetPackage)
   return
  }

  val callback = PendingIntent.getBroadcast(
   this, targetPackage.hashCode(),
   Intent(this, SandboxInstallResultReceiver::class.java).setAction(SandboxInstallResultReceiver.ACTION_UNINSTALL_RESULT).putExtra(EXTRA_SESSION_ID, sessionId).putExtra(EXTRA_PACKAGE_NAME, targetPackage),
   PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
  )
  try {
   packageManager.packageInstaller.uninstall(targetPackage, callback.intentSender)
   report(sessionId, SandboxStatusReport(sessionId, SandboxSessionState.WAITING_FOR_UNINSTALL_CONFIRMATION), targetPackage)
  } catch (e: Exception) {
   report(sessionId, SandboxStatusReport(sessionId, SandboxSessionState.CLEANUP_REQUIRED, error = SandboxError(SandboxErrorCode.UNINSTALL_FAILED, "Removal could not be started for this app.", e.toString(), Recoverability.RETRYABLE)), targetPackage)
  }
 }

 /**
  * Checkpoint 5, item 24/25: only ever reached once Personal has already durably persisted the
  * imported runtime artifact — never gates on anything itself, since a lost ack simply leaves the
  * artifact available for a later retry (item 24's explicit tolerance). Marks the durable
  * [com.nadeem.apkscope.core.database.WorkRuntimeSummaryEntity] acknowledged (kept, not deleted — a
  * small, useful record survives independent of the bulk row data below) and deletes:
  *  - the detailed `work_network_observations` rows for this session (item 25 — Personal now holds
  *    its own durable copy, so this is exactly the "Work-profile history must not grow indefinitely"
  *    bulk data item 25 is about, not the tiny one-row summary);
  *  - the on-disk exported-artifact file, if one is still sitting in `work_query_export/` from the
  *    last pull (item 24's "Work deletes the exported artifact").
  */
  private suspend fun runAckRuntimeArtifact(sessionId: String) {
   val dao = com.nadeem.apkscope.core.database.WorkEvidenceDatabaseProvider.get(this).workNetworkObservationDao()
   dao.markSummaryAcknowledged(sessionId)
   dao.deleteForSession(sessionId)
   File(filesDir, "work_query_export/$sessionId.json").delete()
  }

  /**
   * Checkpoint 6: Work-side half of the AndroidEvidenceArtifact acknowledgment.
   * Marks summary acknowledged and deletes work-side DNS/Connect evidence rows and exported file.
   */
  private suspend fun runAckAndroidEvidence(sessionId: String) {
   val dao = com.nadeem.apkscope.core.database.WorkEvidenceDatabaseProvider.get(this).workAndroidEvidenceDao()
   dao.markAcknowledged(sessionId, System.currentTimeMillis())
   dao.deleteDnsForSession(sessionId)
   dao.deleteConnectForSession(sessionId)
   File(filesDir, "work_query_export/$sessionId-android-evidence.json").delete()
  }

 private suspend fun clearApplicationData(targetPackage: String): Boolean = withTimeoutOrNull(10_000) {
  suspendCancellableCoroutine { cont ->
   try {
    dpm?.clearApplicationUserData(admin, targetPackage, ContextCompat.getMainExecutor(this@SandboxWorkerService)) { _, success -> if (cont.isActive) cont.resume(success) }
   } catch (e: Exception) {
    if (cont.isActive) cont.resume(false)
   }
  }
 } ?: false

 /**
  * Checkpoint 4.1 §5: durably records [statusReport] into the Work-local [evidenceStore] **first**
  * — this is the fact that must survive regardless of what happens next — then attempts the
  * existing best-effort cross-profile push (kept, since it still succeeds often enough to be worth
  * trying, and it is the only path for a plain "confirmation required" info card to appear
  * promptly). A push that never arrives no longer loses anything: `SandboxSessionCoordinator.
  * importEvidence` (a foreground-initiated pull) is the authoritative recovery path.
  */
 private suspend fun report(sessionId: String, statusReport: SandboxStatusReport, targetPackage: String? = null) {
  // Checkpoint 5.3 root-cause fix — see WorkSessionTeardown's doc comment: a FAILED/CANCELLED
  // report reached from anywhere before RUNNING must not leave Work's VPN tunnel orphaned.
  WorkSessionTeardown.tearDownIfEarlyTermination(this, sessionId, statusReport.state)
  evidenceStore.recordFact(sessionId, targetPackage.orEmpty(), statusReport)
  val file = File(filesDir, "reports/$sessionId-${System.nanoTime()}.json")
  file.parentFile?.mkdirs()
  file.writeText(statusReport.toJson().toString())
  val action = if (statusReport.error != null) CrossProfileContract.ACTION_SANDBOX_ERROR else CrossProfileContract.ACTION_SANDBOX_READY
  try { Handoff.send(this, file, action, sessionId, SANDBOX_FILE_PROVIDER_AUTHORITY) } catch (_: Exception) { /* best-effort only — see evidenceStore above */ }
 }

  private fun ensureWorkProfilePermissions() {
   val dpm = getSystemService(DevicePolicyManager::class.java)
   val admin = ComponentName(this, SandboxAdminReceiver::class.java)
   if (dpm != null && dpm.isProfileOwnerApp(packageName)) {
    try {
     dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
    } catch (e: Exception) {
     Log.w("SandboxWorkerService", "clearUserRestriction failed: $e")
    }
    // Grant REQUEST_INSTALL_PACKAGES permission (required for canRequestPackageInstalls() to return true)
    try {
     val grantedInstall = dpm.setPermissionGrantState(admin, packageName, android.Manifest.permission.REQUEST_INSTALL_PACKAGES, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
     Log.i("SandboxWorkerService", "dpm setPermissionGrantState REQUEST_INSTALL_PACKAGES=$grantedInstall")
    } catch (e: Exception) {
     Log.w("SandboxWorkerService", "grant REQUEST_INSTALL_PACKAGES failed: $e")
    }
    if (Build.VERSION.SDK_INT >= 33) {
     try {
      val granted = dpm.setPermissionGrantState(admin, packageName, android.Manifest.permission.POST_NOTIFICATIONS, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
      Log.i("SandboxWorkerService", "dpm setPermissionGrantState POST_NOTIFICATIONS=$granted")
     } catch (e: Exception) {
      Log.w("SandboxWorkerService", "grant POST_NOTIFICATIONS failed: $e")
     }
    }
   }
  }

 override fun onDestroy() {
  scope.cancel()
  super.onDestroy()
 }
}

/**
 * Built once a real uninstall result (or an already-absent package) is confirmed — shared by
 * [SandboxWorkerService.runEndSessionSequence]'s short-circuit and [SandboxInstallResultReceiver]'s
 * real callback path. Only claims the three facts the **work** side can actually verify
 * (`appDataCleared`/`apkRemoved`/`workTempApkDeleted`) — the three personal-side-only facts
 * (`personalTempApkDeleted`/`uriGrantReleased`/`networkSessionClosed`) are left `false` here on
 * purpose (item 18's "if cleanup is partial, do not report full completion"): only the personal-side
 * coordinator can verify those, and it merges this report with its own results into the final
 * summary that actually gates `COMPLETED`.
 */
internal fun buildCleanupReport(sessionId: String, targetPackage: String, apkRemoved: Boolean): SandboxStatusReport {
 val summary = com.nadeem.apkscope.core.model.CleanupSummary(
  appDataCleared = true, apkRemoved = apkRemoved, workTempApkDeleted = true,
  personalTempApkDeleted = false, uriGrantReleased = false, networkSessionClosed = false,
 )
 return SandboxStatusReport(sessionId, SandboxSessionState.CLEANUP_REQUIRED, cleanup = summary)
}
