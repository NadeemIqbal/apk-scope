package com.nadeem.apkscope.domain

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.CrossProfileApps
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.UserManager
import com.nadeem.apkscope.core.crossprofile.Handoff
import com.nadeem.apkscope.MainActivity
import com.nadeem.apkscope.spike.SandboxAdminReceiver
import com.nadeem.apkscope.ui.common.UiStatus

data class EnvironmentState(val workProfile: UiStatus, val networkIsolation: UiStatus, val vpnLockdown: UiStatus) {
 val allReady get() = workProfile == UiStatus.READY && networkIsolation == UiStatus.READY && vpnLockdown == UiStatus.READY
}

/**
 * The real, current state of the three environment checks Home's dashboard card and Settings show.
 * - In the Work Profile, reads the real DPM Profile Owner, always-on VPN, and lockdown status.
 * - In the Personal Profile, checks whether cross-profile handoff is configured (confirming the
 *   Work Profile exists and is operational). If not configured, checks [PackageManager.FEATURE_MANAGED_USERS]
 *   and [DevicePolicyManager.isProvisioningAllowed] to distinguish unconfigured from unsupported/disallowed.
 */
class EnvironmentRepository(private val context: Context) {
 private val dpm = context.getSystemService(DevicePolicyManager::class.java)
 private val admin = ComponentName(context, SandboxAdminReceiver::class.java)

 fun currentState(): EnvironmentState {
  val isProfileOwner = try { dpm?.isProfileOwnerApp(context.packageName) == true } catch (_: Exception) { false }
  val isManagedProfile = try { context.getSystemService(UserManager::class.java)?.isManagedProfile == true } catch (_: Exception) { false }

  if (isProfileOwner && isManagedProfile) {
   val vpnLockdown = try {
    if (dpm?.getAlwaysOnVpnPackage(admin) == context.packageName && dpm.isAlwaysOnVpnLockdownEnabled(admin)) UiStatus.READY else UiStatus.NOT_CONFIGURED
   } catch (e: Exception) { UiStatus.ERROR }
   val networkIsolation = vpnLockdown
   return EnvironmentState(UiStatus.READY, networkIsolation, vpnLockdown)
  }

  // Personal profile: check if Work Profile exists and cross-profile forwarder is configured
  val handoffConfigured = try { Handoff.isConfigured(context) } catch (_: Exception) { false }
  if (handoffConfigured) {
   return EnvironmentState(UiStatus.READY, UiStatus.READY, UiStatus.READY)
  }

  val workProfiles = try { context.getSystemService(CrossProfileApps::class.java)?.targetUserProfiles.orEmpty() } catch (_: Exception) { emptyList() }
  val selfInstalledInWork = workProfiles.any { workHandle ->
   try { context.getSystemService(LauncherApps::class.java)?.getApplicationInfo(context.packageName, 0, workHandle) != null } catch (_: Exception) { false }
  }
  if (selfInstalledInWork) {
   return EnvironmentState(UiStatus.READY, UiStatus.NOT_CONFIGURED, UiStatus.NOT_CONFIGURED)
  }

  val hasManagedUsers = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS)
  if (!hasManagedUsers) {
   return EnvironmentState(UiStatus.UNAVAILABLE, UiStatus.UNAVAILABLE, UiStatus.UNAVAILABLE)
  }

  val isProvisioningAllowed = try {
   dpm?.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE) == true
  } catch (_: Exception) { false }

  val workStatus = if (isProvisioningAllowed) UiStatus.NOT_CONFIGURED else UiStatus.UNAVAILABLE
  return EnvironmentState(workStatus, UiStatus.NOT_CONFIGURED, UiStatus.NOT_CONFIGURED)
 }

 fun isWorkProfileConfigured(): Boolean {
  val isProfileOwner = try { dpm?.isProfileOwnerApp(context.packageName) == true } catch (_: Exception) { false }
  val isManagedProfile = try { context.getSystemService(UserManager::class.java)?.isManagedProfile == true } catch (_: Exception) { false }
  if (isProfileOwner && isManagedProfile) return true
  if (try { Handoff.isConfigured(context) } catch (_: Exception) { false }) return true
  return workProfileAppInstalled()
 }

 fun needsHandoffRepair(): Boolean = workProfileAppInstalled() && !(try { Handoff.isConfigured(context) } catch (_: Exception) { false })

 fun openWorkProfileApp() {
  val crossProfileApps = context.getSystemService(CrossProfileApps::class.java)
  val workHandle = crossProfileApps?.targetUserProfiles?.firstOrNull()
  if (workHandle != null) {
   crossProfileApps.startMainActivity(ComponentName(context, MainActivity::class.java), workHandle)
  }
 }

 fun isProvisioningAllowed(): Boolean {
  val hasManagedUsers = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS)
  if (!hasManagedUsers) return false
  return try {
   dpm?.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE) == true
  } catch (_: Exception) { false }
 }

 fun createProvisioningIntent(): android.content.Intent {
  return android.content.Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
   putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, admin)
  }
 }

 private fun workProfileAppInstalled(): Boolean {
  val workProfiles = try { context.getSystemService(CrossProfileApps::class.java)?.targetUserProfiles.orEmpty() } catch (_: Exception) { emptyList() }
  return workProfiles.any { workHandle ->
   try { context.getSystemService(LauncherApps::class.java)?.getApplicationInfo(context.packageName, 0, workHandle) != null } catch (_: Exception) { false }
  }
 }
}
