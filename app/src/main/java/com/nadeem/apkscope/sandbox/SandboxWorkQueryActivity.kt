package com.nadeem.apkscope.sandbox

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nadeem.apkscope.core.crossprofile.CrossProfileContract
import com.nadeem.apkscope.core.crossprofile.Handoff
import com.nadeem.apkscope.core.database.WorkEvidenceDatabaseProvider
import com.nadeem.apkscope.core.database.WorkNetworkObservationEntity
import com.nadeem.apkscope.core.model.EnforcementMechanism
import com.nadeem.apkscope.core.model.EnforcementStatus
import com.nadeem.apkscope.core.model.PolicyEnforcementResult
import com.nadeem.apkscope.core.model.SandboxPolicyType
import com.nadeem.apkscope.core.sandbox.PolicyEnforcer
import com.nadeem.apkscope.spike.SandboxAdminReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Checkpoint 4.1's Work-side half of the pull/reconciliation transport (see
 * `V0.1_CHECKPOINT_4_1.md` §"Cross-profile transport decision"). Handles
 * [CrossProfileContract.ACTION_WORK_QUERY], always started with `startActivityForResult` from a
 * **foreground** Personal Activity (never from a background Work process) — the whole design point
 * is that the round trip only ever happens while Personal is already visible, so Android's
 * background-activity-launch restrictions never apply to either leg: the outbound request is a
 * foreground-initiated cross-profile Activity launch (already proven reliable throughout checkpoint
 * 4's install/uninstall confirmation flow), and the *return* leg is not a new Activity launch at
 * all — it is the OS delivering an `ActivityResult` to the task that is still sitting there waiting
 * for it.
 *
 * Uses a fully **transparent, displayable** theme (`TransparentQueryActivity`, not `Theme.NoDisplay`)
 * — unlike [SandboxWorkerActivity]/[SandboxReportActivity], which finish synchronously in `onCreate`
 * and so can safely use `Theme.NoDisplay`, this Activity does real asynchronous work (a VPN
 * establish-and-poll, a Room read) before it can call [setResult]/[finish]. `Theme.NoDisplay` hard-
 * requires `finish()` before `onResume()` completes and crashes with `IllegalStateException` otherwise
 * — hit for real during this checkpoint's own testing, not a theoretical concern (see
 * `V0.1_CHECKPOINT_4_1.md`).
 *
 * Two query types, deliberately unified into one Activity rather than two near-identical ones
 * (checkpoint 4.1 item 18's "consolidate duplicate relay code where safe"):
 * - [QUERY_TYPE_VERIFY_VPN]: item 9/10/12's fresh, authoritative tunnel check. Reads
 *   [SandboxVpnService.isForwardingActive] — an in-process `@Volatile` flag, valid because every
 *   component of this app runs in one process per profile (no custom `android:process`) — and, if
 *   it is false, attempts one real re-establish + re-verify before answering, never before.
 * - [QUERY_TYPE_EXPORT_EVIDENCE]: item 6/8's durable-evidence pull. Reads the Work-local
 *   [WorkEvidenceStore] for the requested session and hands back whatever cumulative fact-set is
 *   durably known — independent of whether any earlier push for those same facts ever arrived.
 *
 * Both answers are handed back as a small bounded JSON file via the exact same `FileProvider` +
 * temporary read-URI-grant pattern this codebase already uses for every other cross-profile
 * artifact (checkpoint 4.1 item 16) — chosen over plain `Intent` extras specifically so this same
 * shape can grow to carry a genuinely large artifact later (a batch `runtime-observations.json`)
 * without a transport redesign, even though today's payloads are a few hundred bytes.
 */
class SandboxWorkQueryActivity : Activity() {
 private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

 companion object {
  const val QUERY_TYPE_VERIFY_VPN = "VERIFY_VPN"
  const val QUERY_TYPE_EXPORT_EVIDENCE = "EXPORT_EVIDENCE"
  /** Checkpoint 5.3, item 2/4: Work's own authoritative answer to "is a sandbox session currently active, and which one" — the one fact [SessionReconciliation]/the one-active-session invariant are built on. Never inferred from a notification or a Room row Work itself does not trust; reads [SandboxVpnService]'s own live in-process state. */
  const val QUERY_TYPE_ACTIVE_SESSION = "ACTIVE_SESSION"
  /** Checkpoint 5, item 19/20: the runtime-observation-artifact pull — a genuinely larger payload than the other two query types, hence [RuntimeObservationArtifact.MAX_ARTIFACT_BYTES] rather than [MAX_RESULT_BYTES] below. */
  const val QUERY_TYPE_EXPORT_RUNTIME_ARTIFACT = "EXPORT_RUNTIME_ARTIFACT"
  /** Checkpoint 6: export Android evidence artifact recorded by DPM. */
  const val QUERY_TYPE_EXPORT_ANDROID_EVIDENCE = "EXPORT_ANDROID_EVIDENCE"
  const val QUERY_TYPE_EXPORT_URL_EVIDENCE = "EXPORT_URL_EVIDENCE"
  const val QUERY_TYPE_REQUEST_UNINSTALL = CrossProfileContract.QUERY_TYPE_REQUEST_UNINSTALL
  const val QUERY_TYPE_HTTPS_POC = "HTTPS_POC"
  /**
   * Milestone 9 (Pixel 8 acceptance, fifth pass): a fresh, live read of this Work-profile process's
   * own `canRequestPackageInstalls()` — the exact fact `SandboxWorkerService`'s install failure
   * depends on. Answered synchronously (no Activity launch), unlike [QUERY_TYPE_OPEN_INSTALL_SETTINGS]
   * below.
   */
  const val QUERY_TYPE_CHECK_INSTALL_PERMISSION = "CHECK_INSTALL_PERMISSION"
  /**
   * Milestone 9 (Pixel 8 acceptance, fifth pass): opens `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`
   * for this app's own package from *within this Work-profile process* — the actual fix for the
   * confirmed defect where the previous remediation opened Personal's own Settings instead. Same
   * `startActivityForResult`-then-respond-in-`onActivityResult` shape as [QUERY_TYPE_REQUEST_UNINSTALL]
   * above (see [launchUninstall]) — the response only completes once the user has returned from
   * Settings (or the launch definitively failed), so the caller can safely re-check the permission
   * immediately afterward without a stale answer.
   */
  const val QUERY_TYPE_OPEN_INSTALL_SETTINGS = "OPEN_INSTALL_SETTINGS"
  private const val REQUEST_CODE_UNINSTALL = 2001
  /** Bytes — [QUERY_TYPE_VERIFY_VPN]/[QUERY_TYPE_EXPORT_EVIDENCE] are small hand-built JSON objects, not arbitrary user content; a generous but real bound (item 16). */
  private const val MAX_RESULT_BYTES = 64L * 1024
  private const val VPN_RECOVERY_TIMEOUT_MS = 4000
  /** Item 16: the Live Monitor never loads a whole session into memory at once; the artifact export follows the same discipline, paging out of Room rather than reading a single unbounded query result. */
  private const val EXPORT_PAGE_SIZE = 500
 }

 private var activeUninstallSessionId: String? = null
 private var activeUninstallPackage: String? = null

 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  if (android.os.Build.VERSION.SDK_INT >= 34) {
   overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
   overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
  } else {
   @Suppress("DEPRECATION")
   overridePendingTransition(0, 0)
  }
  val sessionId = intent.getStringExtra(CrossProfileContract.SESSION_ID)
  val queryType = intent.getStringExtra(CrossProfileContract.QUERY_TYPE)
  val targetPackage = intent.getStringExtra(CrossProfileContract.PACKAGE_NAME)
  val operationId = intent.getStringExtra(CrossProfileContract.OPERATION_ID)
  if (sessionId == null || queryType == null) { setResult(RESULT_CANCELED); finish(); return }

  scope.launch {
   when (queryType) {
    QUERY_TYPE_VERIFY_VPN -> respondJson(sessionId, verifyVpn(sessionId, targetPackage))
    QUERY_TYPE_EXPORT_EVIDENCE -> respondJson(sessionId, exportEvidence(sessionId))
    QUERY_TYPE_ACTIVE_SESSION -> respondJson(sessionId, activeSession())
    QUERY_TYPE_EXPORT_RUNTIME_ARTIFACT -> respondJson(sessionId, exportRuntimeArtifact(sessionId), RuntimeObservationArtifact.MAX_ARTIFACT_BYTES)
    QUERY_TYPE_EXPORT_ANDROID_EVIDENCE -> respondJson(sessionId, exportAndroidEvidence(sessionId), AndroidEvidenceArtifact.MAX_ARTIFACT_BYTES, "$sessionId-android-evidence.json")
    QUERY_TYPE_EXPORT_URL_EVIDENCE -> {
     val opId = operationId ?: sessionId
     respondJson(sessionId, exportUrlEvidence(sessionId, opId), UrlEvidenceArtifact.MAX_ARTIFACT_BYTES, "$sessionId-url-evidence.json", urlEvidenceOperationId = opId)
    }
    QUERY_TYPE_REQUEST_UNINSTALL -> launchUninstall(sessionId, targetPackage)
    QUERY_TYPE_CHECK_INSTALL_PERMISSION -> respondJson(sessionId, checkInstallPermission())
    QUERY_TYPE_OPEN_INSTALL_SETTINGS -> openInstallSettings(sessionId)
    QUERY_TYPE_HTTPS_POC -> respondJson(sessionId, handleHttpsPoc(intent))
    CrossProfileContract.QUERY_TYPE_CHECK_PROFILE_OWNER -> respondJson(sessionId, checkProfileOwner())
    CrossProfileContract.QUERY_TYPE_DESTROY_WORK_PROFILE -> destroyWorkProfile(sessionId)
    else -> respondJson(sessionId, JSONObject().put("error", "unknown queryType"))
   }
  }
 }

 private fun checkProfileOwner(): JSONObject {
  val dpm = getSystemService(DevicePolicyManager::class.java)
  val um = getSystemService(UserManager::class.java)
  val admin = ComponentName(this, SandboxAdminReceiver::class.java)
  val isProfileOwner = um?.isManagedProfile == true &&
   dpm?.isProfileOwnerApp(packageName) == true &&
   dpm.isAdminActive(admin)
  return JSONObject().put("isProfileOwner", isProfileOwner)
 }

 private fun destroyWorkProfile(sessionId: String) {
  val dpm = getSystemService(DevicePolicyManager::class.java)
  val um = getSystemService(UserManager::class.java)
  val admin = ComponentName(this, SandboxAdminReceiver::class.java)
  val isProfileOwner = um?.isManagedProfile == true &&
   dpm?.isProfileOwnerApp(packageName) == true &&
   dpm.isAdminActive(admin)
  if (isProfileOwner && dpm != null) {
   respondJson(sessionId, JSONObject().put("wiping", true).put("success", true))
   try {
    dpm.wipeData(0)
   } catch (e: Exception) {
    PackageInstallerDiagnostics.log("wipeData threw: $e")
   }
  } else {
   respondJson(sessionId, JSONObject().put("error", "Unauthorized").put("wiping", false))
  }
 }

 private suspend fun launchUninstall(sessionId: String, targetPackage: String?) {
  val pkg = targetPackage?.ifEmpty { null } ?: WorkEvidenceStore(this).getPackageName(sessionId)
  if (pkg.isNullOrEmpty()) {
   respondJson(sessionId, JSONObject().put("error", "missing target package").put("uninstalled", false))
   return
  }
  val isInstalled = try { packageManager.getPackageInfo(pkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
  if (!isInstalled) {
   respondJson(sessionId, JSONObject().put("uninstalled", true).put("alreadyRemoved", true))
   return
  }

  // Ensure notification 46 is posted to the shade as a reliable fallback
  try {
   SandboxInstallResultReceiver.notifyUninstallForPackage(this, pkg)
  } catch (e: Exception) {
   PackageInstallerDiagnostics.log("Failed to post uninstall notification from query activity: $e")
  }

  activeUninstallSessionId = sessionId
  activeUninstallPackage = pkg
  @Suppress("DEPRECATION")
  val uninstallIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
   data = Uri.parse("package:$pkg")
   putExtra(Intent.EXTRA_RETURN_RESULT, true)
  }
  try {
   @Suppress("DEPRECATION")
   startActivityForResult(uninstallIntent, REQUEST_CODE_UNINSTALL)
  } catch (e: Exception) {
   PackageInstallerDiagnostics.log("launchUninstall startActivityForResult threw: $e")
   respondJson(sessionId, JSONObject().put("error", e.toString()).put("uninstalled", false))
  }
 }

 /** Milestone 9 (Pixel 8 acceptance, fifth pass): a live binder call to the system's AppOpsManager via `PackageManager`, not a value this process could have cached — reflects whatever was last actually granted/revoked for this exact Work-profile package instance, regardless of how recently. */
 private fun checkInstallPermission(): JSONObject =
  JSONObject().put("canRequestPackageInstalls", packageManager.canRequestPackageInstalls())

 /**
  * Milestone 9 (Pixel 8 acceptance, fifth pass): the fix for the confirmed wrong-profile-Settings
  * defect. `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` is launched from *this* Activity — already
  * running as the Work-profile process, the same way [SandboxAdminReceiver]/every other Work-side
  * component in this codebase does its own profile's work — so Android opens the Work profile's own
  * copy of that settings screen, never Personal's. No `UserHandle`/user id is named anywhere in this
  * call; no additional permission is requested. `resolveActivity` is checked first specifically so an
  * unresolvable intent (a device stripped of the Settings app, or a future OEM change) reports a
  * definite `opened=false` rather than throwing or silently doing nothing — the caller must show
  * accurate manual guidance in that case, never fall back to Personal's own settings.
  *
  * **Corrected twice, same pass, after on-device investigation of two successive focused-test
  * failures** (both real platform behavior, not test artifacts — see
  * `WorkInstallPermissionRemediationInstrumentedTest`'s own history and `PackageInstallerDiagnostics`'
  * logcat evidence from each run):
  *
  * 1. This originally used `startActivityForResult` and waited for [onActivityResult], on the
  *    (reasonable-looking, and correct for [launchUninstall]'s own `ACTION_UNINSTALL_PACKAGE` dialog)
  *    assumption that it reliably signals "the user has returned." It does not, for this specific
  *    target: `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` resolves to
  *    `Settings$ManageAppExternalSourcesActivity`, one of the newer "SPA" (Settings Panel) framework
  *    screens — genuinely displayed, genuinely in the Work profile (`myUserHandle=UserHandle{11}`
  *    logged directly from this process), genuinely left again once a Back press fired — but this
  *    Activity's own `onDestroy` fired with `isFinishing=true` and the tracked session id still set,
  *    meaning `onActivityResult` was never reached at all: the whole task hosting this Activity was
  *    torn down as a side effect of backing out of that screen, not returned to normally.
  * 2. The first fix responded synchronously right after a successful `startActivityForResult` call,
  *    without waiting for `onActivityResult` — but *keeping* `startActivityForResult` (rather than a
  *    plain launch) turned out to itself be the remaining problem: with Settings still open on top,
  *    calling `finish()` (inside [respondJson]) on an Activity that still has a **pending nested
  *    for-result child** does not actually propagate outward to this Activity's *own* caller
  *    (Personal, across the profile boundary) until that pending child relationship also resolves —
  *    confirmed on-device: `respondJson` ran and this Activity's own `onDestroy` fired within
  *    milliseconds every time, yet `CrossProfileQueryBridge.launchForResult` on the Personal side
  *    stayed suspended for the full timeout, because Settings was still sitting open with nothing
  *    resolving *its* pending result.
  *
  * Fixed by not creating that nested for-result relationship at all: a plain `startActivity` (no
  * result requested) launches Settings exactly as reliably, and this Activity has nothing left
  * pending on itself once that call returns — `finish()` from [respondJson] then propagates back to
  * Personal immediately, regardless of whether Settings is still open. `opened` means "genuinely
  * launched in the correct profile," the one fact this call can honestly guarantee — never "the user
  * has already returned" or "the permission was actually granted," which only a fresh
  * [checkInstallPermission] call (made separately, by the caller) can answer. The caller
  * ([SandboxPreparingViewModel.openInstallSettings]) already rechecks the real permission immediately
  * after this returns and falls back to a manual, user-driven Retry when it is not yet granted — the
  * correct behavior regardless of exactly when the user acts in Settings.
  */
 private fun openInstallSettings(sessionId: String) {
  val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
  val resolved = intent.resolveActivity(packageManager)
  // Kept permanently (matching PackageInstallerDiagnostics's own launchUninstall precedent), not a
  // throwaway log — this exact call's on-device behavior was found through it, twice.
  PackageInstallerDiagnostics.log("openInstallSettings session=$sessionId resolvedActivity=$resolved myUserHandle=${android.os.Process.myUserHandle()}")
  if (resolved == null) {
   respondJson(sessionId, JSONObject().put("opened", false).put("error", "ACTION_MANAGE_UNKNOWN_APP_SOURCES not resolvable in this profile"))
   return
  }
  try {
   // Plain startActivity, deliberately not startActivityForResult — see this function's own doc
   // comment for why a pending for-result child here blocks this Activity's *own* result from ever
   // reaching Personal while Settings remains open.
   startActivity(intent)
   PackageInstallerDiagnostics.log("openInstallSettings session=$sessionId startActivity returned normally — responding now")
   respondJson(sessionId, JSONObject().put("opened", true))
  } catch (e: Exception) {
   PackageInstallerDiagnostics.log("openInstallSettings session=$sessionId startActivity threw: $e")
   respondJson(sessionId, JSONObject().put("opened", false).put("error", e.toString()))
  }
 }

 @Deprecated("Deprecated in Java")
 override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
  super.onActivityResult(requestCode, resultCode, data)
  if (requestCode == REQUEST_CODE_UNINSTALL) {
   val sessionId = activeUninstallSessionId ?: return
   val pkg = activeUninstallPackage
   val actuallyGone = if (pkg != null) {
    try { packageManager.getPackageInfo(pkg, 0); false } catch (_: PackageManager.NameNotFoundException) { true }
   } else false
   val uninstalled = resultCode == RESULT_OK || actuallyGone
   respondJson(sessionId, JSONObject().put("uninstalled", uninstalled).put("resultCode", resultCode))
  }
  // No REQUEST_CODE_INSTALL_SETTINGS branch: openInstallSettings() deliberately uses a plain
  // startActivity (see its own doc comment) specifically so nothing here ever needs to wait for or
  // react to a result from Settings.
 }

 /**
  * Item 9/10/12/28: the fresh, real tunnel check — never trusts a cached policy-configured flag,
  * and attempts exactly one real recovery before answering. Item 28: this re-establish call now
  * carries the same session/package attribution [SandboxWorkerService.runPrepareSequence]'s does
  * (looked up from the durable [WorkEvidenceStore], since this Activity — unlike that service —
  * does not already have the target package in hand) — VPN readiness and observation-session
  * attribution remain separate concerns; this only ensures a recovered tunnel is attributed
  * correctly if a recovery genuinely has to happen.
  */
 private suspend fun verifyVpn(sessionId: String, targetPackage: String? = null): JSONObject {
  val packageName = targetPackage?.ifEmpty { null } ?: WorkEvidenceStore(this).getPackageName(sessionId)
  var tunnelActive = SandboxVpnService.isForwardingActive
  var recoveryAttempted = false
  if (!tunnelActive) {
   // Not active — attempt one real recovery (item 12) before reporting failure.
   startForegroundService(
    Intent(this, SandboxVpnService::class.java).setAction(SandboxVpnService.ACTION_ESTABLISH)
     .putExtra(SandboxVpnService.EXTRA_SESSION_ID, sessionId)
     .putExtra(SandboxVpnService.EXTRA_PACKAGE_NAME, packageName),
   )
   var waitedMs = 0
   while (!SandboxVpnService.isForwardingActive && waitedMs < VPN_RECOVERY_TIMEOUT_MS) { delay(100); waitedMs += 100 }
   tunnelActive = SandboxVpnService.isForwardingActive
   recoveryAttempted = true
  }

  // Phase 9.1: a rescope-on-verify step lived here (re-fire ESTABLISH once the target is confirmed
  // installed, so `addAllowedApplication` — which needs the package to already resolve — could
  // succeed on this second attempt where the first, pre-install ESTABLISH could not). Removed: the
  // scoping mechanism itself is currently disabled in `SandboxVpnService` after on-device
  // verification found it breaks DNS resolution for the very app it allows, when combined with this
  // service's existing always-on VPN lockdown policy — see that file's own note. Re-firing ESTABLISH
  // here unconditionally would have been pure overhead with the mechanism disabled
  // (`scopedPackageName` never becomes non-null now, so the old condition was always true). `scoped`
  // below is reported honestly as always-false for the same reason, not silently dropped from the
  // response shape other callers already read.
  val scoped = !packageName.isNullOrEmpty() && SandboxVpnService.scopedPackageName == packageName

  var packageUnsuspended = true
  if (tunnelActive && !packageName.isNullOrEmpty()) {
   val dpm = getSystemService(DevicePolicyManager::class.java)
   val admin = ComponentName(this, SandboxAdminReceiver::class.java)
   if (dpm != null && dpm.isAdminActive(admin)) {
    val enforcer = PolicyEnforcer(dpm, admin)
    enforcer.unsuspendPackage(packageName)
    packageUnsuspended = !dpm.isPackageSuspended(admin, packageName)
   }
  }

  return JSONObject()
   .put("tunnelActive", tunnelActive)
   .put("recoveryAttempted", recoveryAttempted)
   .put("packageUnsuspended", packageUnsuspended)
   .put("scoped", scoped)
 }

 /**
  * Checkpoint 5.3, item 1/2/4: the authoritative snapshot [SessionReconciliation] and the
  * one-active-session invariant are both built on — [SandboxVpnService]'s own live `@Volatile`
  * state, never a Room row (Work's Room can lag or disagree, see [exportEvidence]'s own
  * reconciliation) and never Personal's Room (a different profile's private storage this process
  * cannot read). `active=false` here is unambiguous: if the tunnel is not genuinely forwarding,
  * nothing is active, regardless of what any stale notification might still claim.
  */
 private fun activeSession(): JSONObject = JSONObject().apply {
  put("active", SandboxVpnService.isForwardingActive)
  SandboxVpnService.activeSessionId?.let { put("sessionId", it) }
  SandboxVpnService.activePackageName?.let { put("packageName", it) }
  SandboxVpnService.activeSessionStartedAt?.let { put("startedAtEpochMs", it.toEpochMilli()) }
 }

 /** Item 6/8: hands back whatever cumulative evidence is durably known, independent of any earlier push's fate. */
 private suspend fun exportEvidence(sessionId: String): JSONObject {
  val store = WorkEvidenceStore(this)
  var report = store.getReport(sessionId)
  val packageName = store.getPackageName(sessionId)
  // Migration for reports written before enforcement facts were merged by policy: the install
  // result callback used to replace the earlier VPN record with only the three sensor results.
  // Read the Work profile's live DPM state and persist a positive VPN readback so an existing
  // session can recover without being recreated. A missing/negative readback is intentionally not
  // promoted to ENFORCED; launch still fails closed unless the tunnel is verified separately.
  val liveVpn = report?.let { readEnforcedVpnPolicy() }
  if (report != null && liveVpn != null && report.enforcements.none { it.policy == liveVpn.policy && it == liveVpn }) {
   store.recordFact(
    sessionId,
    packageName.orEmpty(),
    SandboxStatusReport(sessionId, report.state, enforcements = listOf(liveVpn)),
   )
   report = store.getReport(sessionId) ?: report.copy(enforcements = report.enforcements + liveVpn)
  }
  if (report?.state in setOf(
    com.nadeem.apkscope.core.model.SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION,
    com.nadeem.apkscope.core.model.SandboxSessionState.INSTALLING,
   ) && !packageName.isNullOrEmpty()) {
   val present = try { packageManager.getPackageInfo(packageName, 0); true }
    catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
   val sessions = packageManager.packageInstaller.mySessions
   val active = sessions.any { it.sessionId == report?.installSessionId }
   PackageInstallerDiagnostics.log("reconcile session=$sessionId state=${report?.state} packagePresent=$present mySessions=${sessions.map { it.sessionId }}")
   if (present) {
    @Suppress("DEPRECATION")
    val installedVersionCode = try {
     val info = packageManager.getPackageInfo(packageName, 0)
     if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    } catch (_: PackageManager.NameNotFoundException) {
     null
    }
    // A successful package-presence read is the authoritative fallback when the transient
    // INSTALLING report or final PackageInstaller callback was lost.
    store.recordFact(
     sessionId,
     packageName,
     SandboxStatusReport(
      sessionId,
      com.nadeem.apkscope.core.model.SandboxSessionState.INSTALLED,
      installSessionId = report?.installSessionId,
      installedVersionCode = installedVersionCode,
     ),
    )
    report = store.getReport(sessionId)
   } else if (com.nadeem.apkscope.core.model.InstallLifecycle.interrupted(report!!.state, present, active)) {
    // Only authoritative absence in this Work user permits interruption recovery.
    val patch = SandboxStatusReport(sessionId, com.nadeem.apkscope.core.model.SandboxSessionState.FAILED,
     error = com.nadeem.apkscope.core.model.SandboxError(com.nadeem.apkscope.core.model.SandboxErrorCode.INSTALL_USER_CANCELLED,
      "Installation was interrupted. Start a new sandbox session to retry.",
      "Package absent and PackageInstaller session no longer exists", com.nadeem.apkscope.core.model.Recoverability.RETRYABLE))
    // Checkpoint 5.3: this writes the FAILED fact directly via the store, bypassing both
    // SandboxWorkerService.report() and SandboxInstallResultReceiver.report() — the same orphaned-
    // VPN root cause applies here too, so the teardown must be repeated at this third call site.
    WorkSessionTeardown.tearDownIfEarlyTermination(this, sessionId, com.nadeem.apkscope.core.model.SandboxSessionState.FAILED)
    store.recordFact(sessionId, packageName, patch)
    report = store.getReport(sessionId)
   }
  }
  return JSONObject().apply {
   put("sessionId", sessionId)
   packageName?.let { put("packageName", it) }
   report?.let { put("report", it.toJson()) }
  }
 }

 private fun readEnforcedVpnPolicy(): PolicyEnforcementResult? {
  val dpm = getSystemService(DevicePolicyManager::class.java) ?: return null
  val admin = ComponentName(this, SandboxAdminReceiver::class.java)
  return try {
   if (!dpm.isProfileOwnerApp(packageName)) return null
   if (dpm.getAlwaysOnVpnPackage(admin) != packageName || !dpm.isAlwaysOnVpnLockdownEnabled(admin)) return null
   PolicyEnforcementResult(
    policy = SandboxPolicyType.ALWAYS_ON_VPN_LOCKDOWN,
    status = EnforcementStatus.ENFORCED,
    mechanism = EnforcementMechanism.DEVICE_POLICY_MANAGER,
    message = null,
   )
  } catch (_: Exception) {
   null
  }
 }

 /**
  * Item 19/20: builds the bounded [RuntimeObservationArtifact] for [sessionId] from the Work-local
  * Room store. The summary always reflects the *whole* session (computed once, at session-end, by
  * [WorkNetworkObservationSink.closeAndFinalize] — read back unchanged here, never recomputed from
  * whatever subset of rows fits in the export). Only the row-level [RuntimeObservationArtifact.observations]
  * list is bounded: rows are paged out oldest-first and appended until the next page would push the
  * serialized artifact over [RuntimeObservationArtifact.MAX_ARTIFACT_BYTES], at which point export
  * stops and [RuntimeObservationArtifact.truncated] is set — the dropped rows are never silently
  * absorbed into a smaller-looking total (item 20's "the summary remains valid, the artifact is
  * marked truncated, and dropped/exported counts are recorded").
  */
 private suspend fun exportRuntimeArtifact(sessionId: String): JSONObject {
  val dao = WorkEvidenceDatabaseProvider.get(this).workNetworkObservationDao()
  val summary = dao.getSummary(sessionId)
   ?: return JSONObject().put("error", "no runtime summary recorded for session $sessionId")
  val packageName = WorkEvidenceStore(this).getPackageName(sessionId).orEmpty()
  val totalCount = dao.count(sessionId)

  val entries = ArrayList<RuntimeObservationEntry>()
  var truncated = false
  var offset = 0
  // Reserve headroom for the envelope fields around the observations array itself, rather than
  // discovering the overflow only after serializing the very last row that broke the budget.
  val budget = RuntimeObservationArtifact.MAX_ARTIFACT_BYTES - 4096
  var approxBytes = 0L
  outer@ while (offset < totalCount) {
   val page = dao.page(sessionId, EXPORT_PAGE_SIZE, offset)
   if (page.isEmpty()) break
   for (row in page) {
    val entry = toEntry(row)
    val entryBytes = entry.toJson().toString().toByteArray().size.toLong() + 1
    if (approxBytes + entryBytes > budget) { truncated = true; break@outer }
    entries.add(entry)
    approxBytes += entryBytes
   }
   offset += page.size
  }

  val artifact = RuntimeObservationArtifact(
   schemaVersion = RuntimeObservationArtifact.SCHEMA_VERSION,
   sessionId = sessionId,
   packageName = packageName,
   startedAtEpochMs = summary.startedAtEpochMs,
   endedAtEpochMs = summary.endedAtEpochMs,
   summary = com.nadeem.apkscope.core.model.RuntimeObservationSummary(
    sessionId = sessionId,
    startedAt = java.time.Instant.ofEpochMilli(summary.startedAtEpochMs),
    endedAt = java.time.Instant.ofEpochMilli(summary.endedAtEpochMs),
    connectionCount = summary.connectionCount,
    dnsQueryCount = summary.dnsQueryCount,
    uniqueObservedDomains = summary.uniqueObservedDomains,
    uploadedBytes = summary.uploadedBytes,
    downloadedBytes = summary.downloadedBytes,
    blockedConnectionCount = summary.blockedConnectionCount,
    failedConnectionCount = summary.failedConnectionCount,
    droppedObservationCount = summary.droppedObservationCount,
   ),
   observations = entries,
   truncated = truncated,
   exportedObservationCount = entries.size,
   totalObservationCount = totalCount,
  )
  return artifact.toJson()
 }

 private fun toEntry(row: WorkNetworkObservationEntity): RuntimeObservationEntry = RuntimeObservationEntry(
  sequence = row.sequence, timestampEpochMs = row.timestampEpochMs, type = row.type,
  protocol = row.protocol, destinationIp = row.destinationIp, destinationPort = row.destinationPort, connectionId = row.connectionId,
  startTimeEpochMs = row.startTimeEpochMs, endTimeEpochMs = row.endTimeEpochMs,
  uploadedBytes = row.uploadedBytes, downloadedBytes = row.downloadedBytes,
  failureReason = row.failureReason, failureDetail = row.failureDetail,
  hostname = row.hostname, resolvedAddressesCsv = row.resolvedAddressesCsv,
  transactionId = row.transactionId, sourcePort = row.sourcePort,
  limitName = row.limitName, currentValue = row.currentValue, limitValue = row.limitValue,
 )

  /**
   * Checkpoint 6: builds the bounded [AndroidEvidenceArtifact] for [sessionId] from Work-local Room.
   */
  private suspend fun exportAndroidEvidence(sessionId: String): JSONObject {
   val dao = WorkEvidenceDatabaseProvider.get(this).workAndroidEvidenceDao()
   val summary = dao.getSummary(sessionId)
   val storedPackage = WorkEvidenceStore(this).getPackageName(sessionId)
   val dnsEvents = dao.getDnsEventsForSession(sessionId).map { row ->
    AndroidDnsEvidenceEntry(
     eventId = row.eventId,
     batchToken = row.batchToken,
     packageName = row.packageName,
     timestampEpochMs = row.timestampEpochMs,
     receivedAtEpochMs = row.receivedAtEpochMs,
     hostname = row.hostname,
     resolvedAddressesCsv = row.resolvedAddressesCsv,
     totalResolvedAddressCount = row.totalResolvedAddressCount,
    )
   }
   val connectEvents = dao.getConnectEventsForSession(sessionId).map { row ->
    AndroidConnectEvidenceEntry(
     eventId = row.eventId,
     batchToken = row.batchToken,
     packageName = row.packageName,
     timestampEpochMs = row.timestampEpochMs,
     receivedAtEpochMs = row.receivedAtEpochMs,
     destinationAddress = row.destinationAddress,
     destinationPort = row.destinationPort,
    )
   }
   val packageName = storedPackage.takeUnless { it.isNullOrEmpty() }
    ?: dnsEvents.firstOrNull()?.packageName
    ?: connectEvents.firstOrNull()?.packageName
    ?: ""
   val artifact = AndroidEvidenceArtifact(
    schemaVersion = AndroidEvidenceArtifact.CURRENT_SCHEMA_VERSION,
    sessionId = sessionId,
    targetPackageName = packageName,
    status = summary?.status ?: if (dnsEvents.isNotEmpty() || connectEvents.isNotEmpty()) "READY" else "PENDING",
    dnsEvents = dnsEvents,
    connectEvents = connectEvents,
    firstEventTimestampEpochMs = summary?.firstEventTimestampEpochMs,
    lastEventTimestampEpochMs = summary?.lastEventTimestampEpochMs,
   )
   return artifact.toJson()
  }

  /**
   * Checkpoint 8.9: Exports URL evidence from captured traffic for exact-URL correlation.
   * Only carries correlation fields (session, target, URL, method, status, timestamp);
   * no headers, bodies, or full transaction data — designed to be bounded and safe for
   * cross-profile transport to Personal profile for DexUrlCandidate matching.
   *
   * Milestone 9 (fourth acceptance rigor pass): the actual export construction (querying
   * `TrafficInspectionStore.forSession()`, building bounded `UrlEvidenceEntry`s, truncation) was
   * extracted to [UrlEvidenceExporter] specifically so it is directly testable without an Activity —
   * this method now only resolves `targetPackage` and delegates. See `UrlEvidenceExporterTest`.
   *
   * Milestone 9 (Pixel 8 acceptance, fifth pass, item 2): records stage (c) "Work side query
   * receipt" on entry and stages (d)/(e) "session and ownership filtering" / "artifact creation"
   * once the real export completes — counts and booleans only, never a captured URL — so a
   * physical-device run's diagnostics show whether the Work profile ever actually received and
   * processed this query at all, independent of whatever the Personal side's own log shows.
   */
  private suspend fun exportUrlEvidence(sessionId: String, operationId: String): JSONObject {
   val diag = com.nadeem.apkscope.core.crossprofile.UrlEvidencePipelineDiagnostics
   diag.record(this, operationId, "work", sessionId, "work_query_received", "queryType=EXPORT_URL_EVIDENCE")
   val storedPackage = WorkEvidenceStore(this).getPackageName(sessionId)
   val targetPackage = storedPackage.orEmpty()
   diag.record(this, operationId, "work", sessionId, "work_target_package_resolved", "resolved=${targetPackage.isNotEmpty()}")
   val artifact = UrlEvidenceExporter.export(sessionId, targetPackage)
   diag.record(
    this, operationId, "work", sessionId, "work_artifact_created",
    "exportedEntryCount=${artifact.exportedEntryCount} totalEntryCount=${artifact.totalEntryCount} truncated=${artifact.truncated}",
   )
   return artifact.toJson()
  }

 /**
  * Milestone 9 (Pixel 8 acceptance, fifth pass, item 2, stage f — "URI grant and result delivery"):
  * [urlEvidenceOperationId] is non-null only for the `QUERY_TYPE_EXPORT_URL_EVIDENCE` call site —
  * every other query type's response is unaffected, keeping this diagnostic narrowly scoped to the
  * one pipeline it was added for, rather than instrumenting every use of this shared helper.
  */
 private fun respondJson(sessionId: String, json: JSONObject, maxBytes: Long = MAX_RESULT_BYTES, fileName: String = "$sessionId.json", urlEvidenceOperationId: String? = null) {
  val diag = com.nadeem.apkscope.core.crossprofile.UrlEvidencePipelineDiagnostics
  try {
   val bytes = json.toString().toByteArray()
   require(bytes.size <= maxBytes) { "work-query result too large" }
   val file = File(filesDir, "work_query_export/$fileName")
   file.parentFile?.mkdirs()
   file.writeBytes(bytes)
   val uri = FileProvider.getUriForFile(this, SANDBOX_FILE_PROVIDER_AUTHORITY, file)
   val result = Intent().setDataAndType(uri, Handoff.MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    .putExtra(CrossProfileContract.SESSION_ID, sessionId)
   setResult(RESULT_OK, result)
   if (urlEvidenceOperationId != null) diag.record(this, urlEvidenceOperationId, "work", sessionId, "work_result_delivered", "bytes=${bytes.size} resultOk=true")
  } catch (e: Exception) {
   setResult(RESULT_CANCELED)
   // Milestone 9 (Pixel 8 acceptance, fifth pass, item 1 investigation): this is the one place a
   // cross-profile query response silently degrades to RESULT_CANCELED for *any* query type — was
   // previously unlogged for everything except the URL-evidence pipeline below. A caller getting an
   // unexpected `null`/`false` back from a query that otherwise looked like it ran to completion (the
   // exact symptom under investigation for QUERY_TYPE_OPEN_INSTALL_SETTINGS) is indistinguishable from
   // "never reached this code" without this line.
   android.util.Log.w("SandboxWorkQuery", "respondJson session=$sessionId fileName=$fileName failed, replying RESULT_CANCELED: ${e.javaClass.simpleName}: ${e.message}")
   if (urlEvidenceOperationId != null) diag.record(this, urlEvidenceOperationId, "work", sessionId, "work_result_delivery_failed", "${e.javaClass.simpleName}: ${e.message}")
  } finally {
    finish()
   }
  }

  private suspend fun handleHttpsPoc(intent: Intent): JSONObject {
   val action = intent.getStringExtra("poc_action") ?: "status"
   val result = JSONObject().put("action", action)
   val um = getSystemService(android.os.UserManager::class.java)
   val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
   val admin = android.content.ComponentName(this, com.nadeem.apkscope.spike.SandboxAdminReceiver::class.java)
   val isProfileOwner = um?.isManagedProfile == true && dpm?.isProfileOwnerApp(packageName) == true && dpm.isAdminActive(admin)

   result.put("isProfileOwner", isProfileOwner)
   result.put("userId", android.os.Process.myUid() / 100000)
   result.put("packageName", packageName)

   when (action) {
    "status" -> {
     result.put("vpnActive", SandboxVpnService.isForwardingActive)
     result.put("inspectionEnabled", com.nadeem.apkscope.core.network.https.HttpsInspectionConfig.isEnabled)
     result.put("caInstalled", CaInstaller.isCaInstalled(this))
     result.put("caGenerated", CaInstaller.getCaManager(this).hasCaCertificate())
     result.put("capturedCount", com.nadeem.apkscope.core.network.https.HttpsInspectionStore.all().size)
    }
    "setup_and_start" -> {
     val caInstalled = CaInstaller.installCa(this)
     result.put("caInstalled", caInstalled)
     com.nadeem.apkscope.core.network.https.HttpsInspectionConfig.isEnabled = true
     result.put("inspectionEnabled", true)
     startForegroundService(
      Intent(this, SandboxVpnService::class.java).setAction(SandboxVpnService.ACTION_ESTABLISH)
       .putExtra(SandboxVpnService.EXTRA_SESSION_ID, "poc-session")
       .putExtra(SandboxVpnService.EXTRA_PACKAGE_NAME, "com.apksandbox.fixture")
     )
     var waited = 0
     while (!SandboxVpnService.isForwardingActive && waited < 4000) {
      kotlinx.coroutines.delay(100); waited += 100
     }
     result.put("vpnActive", SandboxVpnService.isForwardingActive)
    }
    "trigger_fixture" -> {
     val trigger = intent.getStringExtra("trigger") ?: "runHttpsGet"
     val fixtureIntent = Intent().apply {
      component = android.content.ComponentName("com.apksandbox.fixture", "com.apksandbox.fixture.FixtureActivity")
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      putExtra(trigger, true)
     }
     startActivity(fixtureIntent)
     result.put("triggered", trigger)
    }
    "get_captures" -> {
     val array = org.json.JSONArray()
     com.nadeem.apkscope.core.network.https.HttpsInspectionStore.all().forEach { tx ->
      val obj = JSONObject()
       .put("method", tx.method)
       .put("url", tx.url)
       .put("host", tx.host)
       .put("statusCode", tx.statusCode)
       .put("statusMessage", tx.statusMessage)
       .put("state", tx.state.name)
       .put("requestBody", tx.requestBody)
       .put("responseBody", tx.responseBody)
       .put("durationMs", tx.durationMs)
       .put("failureDetails", tx.failureDetails)
      array.put(obj)
     }
     result.put("captures", array)
    }
    "reset" -> {
     CaInstaller.resetPoc(this)
     result.put("resetDone", true)
    }
   }
   return result
  }

  override fun finish() {
   super.finish()
   if (android.os.Build.VERSION.SDK_INT >= 34) {
    overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
   } else {
    @Suppress("DEPRECATION")
    overridePendingTransition(0, 0)
   }
  }

  override fun onDestroy() {
   // Milestone 9 (Pixel 8 acceptance, fifth pass): if this fires with activeUninstallSessionId still
   // set, the pending launchUninstall() query for it is being dropped silently with no other trace —
   // the same class of gap that motivated this section's diagnostics (see openInstallSettings()'s
   // own history, one of this file's doc comments, for why the equivalent gap there was fixed by
   // removing the pending-result relationship rather than only logging it).
   if (activeUninstallSessionId != null) {
    PackageInstallerDiagnostics.log("onDestroy isFinishing=$isFinishing activeUninstallSessionId=$activeUninstallSessionId — pending uninstall query dropped")
   }
   scope.cancel()
   super.onDestroy()
  }
}
