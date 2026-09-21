package com.nadeem.apkscope.domain.sandbox

import android.content.Context
import android.content.pm.CrossProfileApps
import android.content.pm.LauncherApps
import android.os.UserHandle
import com.nadeem.apkscope.core.crossprofile.Handoff
import com.nadeem.apkscope.core.model.EnforcementStatus
import com.nadeem.apkscope.core.model.LaunchReadiness
import com.nadeem.apkscope.core.model.Recoverability
import com.nadeem.apkscope.core.model.SandboxError
import com.nadeem.apkscope.core.model.SandboxErrorCode
import com.nadeem.apkscope.core.model.SandboxPolicyType
import com.nadeem.apkscope.core.model.SandboxSession
import java.io.File

/**
 * Checkpoint 4, item 4/6: the real environment-preflight and launch-readiness checks, shared by
 * [SandboxSessionCoordinator] (personal-side `prepare`/`launch`) and [com.nadeem.apkscope.sandbox.SandboxReportActivity]
 * (which needs the exact same readiness check to decide whether an `INSTALLED` report should
 * immediately auto-advance to `READY`). Every check here is real: `CrossProfileApps`/`LauncherApps`
 * are genuinely cross-profile-queryable from the personal profile without profile-owner authority
 * (the same real mechanism the spike's "Launch work fixture from personal" button already used) —
 * this is not a simulated or hardcoded check.
 *
 * What this can**not** verify from the personal side — whether the Work Profile's own
 * `DevicePolicyManager` profile-owner state is itself valid, and whether an always-on VPN
 * configuration is actually *available* to be applied — is deliberately left to the work-side
 * sequence (`SandboxWorkerService`), which attempts those operations for real and reports the
 * outcome back; [checkEnvironment] here only blocks starting that sequence at all when a
 * personal-side-visible precondition is already known to be missing.
 */
object SandboxEnvironmentPreflight {
 fun checkEnvironment(context: Context, session: SandboxSession): SandboxError? {
  val crossProfileApps = context.getSystemService(CrossProfileApps::class.java)
  val workProfiles = crossProfileApps?.targetUserProfiles.orEmpty()
  if (workProfiles.isEmpty()) {
   return SandboxError(SandboxErrorCode.WORK_PROFILE_MISSING, "Set up the secure sandbox profile before preparing a session.", "CrossProfileApps.targetUserProfiles is empty", Recoverability.REQUIRES_USER_ACTION)
  }
  val workHandle = workProfiles.first()

  val launcherApps = context.getSystemService(LauncherApps::class.java)
  val selfInstalledInWork = try { launcherApps?.getApplicationInfo(context.packageName, 0, workHandle) != null } catch (_: Exception) { false }
  if (!selfInstalledInWork) {
   return SandboxError(SandboxErrorCode.WORK_PROFILE_MISSING, "APK Scope is not set up inside the secure profile yet.", "LauncherApps.getApplicationInfo(own package) failed for the work profile", Recoverability.REQUIRES_USER_ACTION)
  }

  if (!Handoff.isConfigured(context)) {
   return SandboxError(SandboxErrorCode.HANDOFF_UNAVAILABLE, "Sandbox handoff has not been configured on this device yet.", "no cross-profile intent forwarder resolved for ACTION_IMPORT_APK", Recoverability.REQUIRES_USER_ACTION)
  }

  val apkPath = session.personalApkPath
  if (apkPath == null || !File(apkPath).exists()) {
   return SandboxError(SandboxErrorCode.TEMP_APK_MISSING, "The APK for this analysis is no longer available — please choose it again.", "personalApkPath=$apkPath", Recoverability.TERMINAL)
  }

  val isInstalledInPersonal = try {
   @Suppress("DEPRECATION")
   context.packageManager.getPackageInfo(session.packageName, 0) != null
  } catch (_: Exception) {
   false
  }
  if (isInstalledInPersonal) {
   return SandboxError(
    SandboxErrorCode.ALREADY_INSTALLED_IN_PERSONAL,
    "Check if app is already installed in personal profile then uninstall and retry.",
    "package=${session.packageName} already installed in personal profile",
    Recoverability.REQUIRES_USER_ACTION,
   )
  }
  return null
 }

 fun workProfileHandle(context: Context): UserHandle? = context.getSystemService(CrossProfileApps::class.java)?.targetUserProfiles?.firstOrNull()

 /** Item 6's exact invariant, fed with real, already-determined facts (never re-derived by guesswork). */
 fun computeLaunchReadiness(context: Context, session: SandboxSession): LaunchReadiness {
  val vpnResult = session.enforcementResults.firstOrNull { it.policy == SandboxPolicyType.ALWAYS_ON_VPN_LOCKDOWN }
  val networkIsolationActive = vpnResult?.status == EnforcementStatus.ENFORCED
  return LaunchReadiness(
   // A package with the same name may be an older APK. Only the current install callback's
   // version fact is safe to use for this session; package presence alone caused stale Frida
   // tokens to be accepted as if the new target had been installed.
   installationConfirmed = session.installedVersionCode != null,
   environmentValid = checkEnvironment(context, session) == null,
   // Camera/microphone/location denial is explicitly non-mandatory (item 5 — a NOT_SUPPORTED
   // sensor-permission denial, the real Pixel 8 finding, must never fail the whole sandbox); the
   // always-on VPN lockdown is the one policy this checkpoint treats as mandatory for launch.
   requiredPoliciesSatisfied = networkIsolationActive,
   networkIsolationActive = networkIsolationActive,
  )
 }
}
