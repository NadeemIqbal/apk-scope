package com.nadeem.apkscope.sandbox

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log

/**
 * Checkpoint 5.2, item 2/3: the exact runtime `PackageInstaller` prerequisite state and commit-
 * callback lifecycle, read back and logged rather than assumed — this checkpoint exists
 * specifically because a previous session assumed `commit()` not throwing meant the confirmation
 * UI would appear, and it did not, with nothing in this app's own code ever explaining why.
 *
 * Kept permanently (matching `HandoffDiagnostics`'s own precedent from Checkpoint 5.1) — the
 * install/uninstall flow is exactly the kind of "everything looks fine, nothing throws, the user
 * just never sees the dialog" failure mode this trace is built to make visible immediately instead
 * of after a lengthy investigation.
 */
object PackageInstallerDiagnostics {
 private const val TAG = "ApkScopeInstall"

 fun log(message: String) { Log.i(TAG, "t=${android.os.SystemClock.elapsedRealtime()} $message") }

 /** Item 2's exact requested prerequisite list — read live, never inferred. */
 fun logPrerequisites(context: Context, sessionId: String, targetPackage: String) {
  val dpm = context.getSystemService(DevicePolicyManager::class.java)
  val userManager = context.getSystemService(UserManager::class.java)
  val isProfileOwner = try { dpm?.isProfileOwnerApp(context.packageName) == true } catch (e: Exception) { false }
  val canRequestInstalls = context.packageManager.canRequestPackageInstalls()
  val declaresPermission = try {
   context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    .requestedPermissions?.contains(android.Manifest.permission.REQUEST_INSTALL_PACKAGES) == true
  } catch (e: Exception) { false }
  val disallowUnknownSources = try { userManager?.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) == true } catch (e: Exception) { false }
  val disallowUnknownSourcesGlobally = try {
   Build.VERSION.SDK_INT >= 24 && userManager?.hasUserRestriction("no_install_unknown_sources_globally") == true
  } catch (e: Exception) { false }
  log(
   "prerequisites session=$sessionId targetPackage=$targetPackage " +
    "callingUser=${android.os.Process.myUserHandle()} callingProcess=${android.os.Process.myPid()} " +
    "isProfileOwnerApp=$isProfileOwner declaresRequestInstallPackages=$declaresPermission " +
    "canRequestPackageInstalls=$canRequestInstalls " +
    "disallowInstallUnknownSources=$disallowUnknownSources disallowInstallUnknownSourcesGlobally=$disallowUnknownSourcesGlobally " +
    "targetSdk=${context.applicationInfo.targetSdkVersion} apiLevel=${Build.VERSION.SDK_INT}",
  )
 }
}
