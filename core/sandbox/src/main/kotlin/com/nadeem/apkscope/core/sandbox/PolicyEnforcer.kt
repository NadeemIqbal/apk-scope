package com.nadeem.apkscope.core.sandbox

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import com.nadeem.apkscope.core.model.EnforcementMechanism
import com.nadeem.apkscope.core.model.EnforcementStatus
import com.nadeem.apkscope.core.model.PolicyEnforcementResult
import com.nadeem.apkscope.core.model.SandboxPolicyType

/**
 * Wraps every `DevicePolicyManager` policy call the spike proved out (see
 * `HARDENING_GATE_ROOT_CAUSE.md`'s "Re-run the essential sandbox controls" section and
 * `PIXEL8_PHYSICAL_VALIDATION.md`'s Step 6) so callers get a [PolicyEnforcementResult] instead of
 * a bare boolean/exception — the exact "never report a restriction as active unless Android
 * confirmed it" requirement from the v0.1 promotion review.
 *
 * Every method here **reads the state back** after calling the setter and only reports
 * [EnforcementStatus.ENFORCED] if the readback confirms it — a setter not throwing is not
 * sufficient evidence, which is exactly how the sensors-permission limitation below was found:
 * `setPermissionGrantState` for CAMERA/RECORD_AUDIO/ACCESS_FINE_LOCATION returns `false` (not an
 * exception) on a BYOD Work Profile whose `dumpsys device_policy` reports "Admin can grant
 * sensors permission: false".
 */
class PolicyEnforcer(private val dpm: DevicePolicyManager, private val admin: ComponentName) {

 /** Per-app runtime-permission denial (CAMERA/RECORD_AUDIO/ACCESS_FINE_LOCATION) — verified NOT_SUPPORTED for sensor permissions on the tested BYOD Work Profile. Distinct from [disableCameraProfileWide]: these are two different mechanisms and must not be conflated. */
 fun denyRuntimePermission(policy: SandboxPolicyType, targetPackage: String, androidPermission: String): PolicyEnforcementResult {
  val requested = try {
   dpm.setPermissionGrantState(admin, targetPackage, androidPermission, DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED)
  } catch (e: Exception) {
   return PolicyEnforcementResult(policy, EnforcementStatus.FAILED, EnforcementMechanism.DEVICE_POLICY_MANAGER, e.toString())
  }
  if (!requested) {
   // setPermissionGrantState returned false without throwing — Android declined the request.
   // This is the exact shape of the Pixel 8 finding: not an error, a platform boundary.
   return PolicyEnforcementResult(policy, EnforcementStatus.NOT_SUPPORTED, EnforcementMechanism.DEVICE_POLICY_MANAGER,
    "setPermissionGrantState returned false — Android declined to let this admin control $androidPermission (observed on sensor permissions on a BYOD Work Profile: \"Admin can grant sensors permission: false\")")
  }
  val actual = dpm.getPermissionGrantState(admin, targetPackage, androidPermission)
  return if (actual == DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED) {
   PolicyEnforcementResult(policy, EnforcementStatus.ENFORCED, EnforcementMechanism.DEVICE_POLICY_MANAGER, null)
  } else {
   PolicyEnforcementResult(policy, EnforcementStatus.FAILED, EnforcementMechanism.DEVICE_POLICY_MANAGER, "readback did not confirm denial (state=$actual)")
  }
 }

 /** Profile-wide camera disable — verified working (ENFORCED) on the physical Pixel 8 gate. */
 fun disableCameraProfileWide(): PolicyEnforcementResult = runWithReadback(
  SandboxPolicyType.CAMERA_PROFILE_WIDE_DISABLE,
  set = { dpm.setCameraDisabled(admin, true); true },
  confirm = { dpm.getCameraDisabled(admin) },
 )

 fun setAutoDenyPermissionPolicy(): PolicyEnforcementResult = runWithReadback(
  SandboxPolicyType.AUTO_DENY_PERMISSION_POLICY,
  set = { dpm.setPermissionPolicy(admin, DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY); true },
  confirm = { dpm.getPermissionPolicy(admin) == DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY },
 )

 fun hideApplication(targetPackage: String): PolicyEnforcementResult = runWithReadback(
  SandboxPolicyType.APPLICATION_HIDDEN,
  set = { dpm.setApplicationHidden(admin, targetPackage, true) },
  confirm = { dpm.isApplicationHidden(admin, targetPackage) },
 )

 fun suspendPackage(targetPackage: String): PolicyEnforcementResult = runWithReadback(
  SandboxPolicyType.PACKAGES_SUSPENDED,
  set = { dpm.setPackagesSuspended(admin, arrayOf(targetPackage), true).isEmpty() },
  confirm = { dpm.isPackageSuspended(admin, targetPackage) },
 )

 fun unsuspendPackage(targetPackage: String): Boolean = try {
  if (dpm.isPackageSuspended(admin, targetPackage)) {
   dpm.setPackagesSuspended(admin, arrayOf(targetPackage), false).isEmpty()
  } else {
   true
  }
 } catch (_: Exception) {
  false
 }

 fun blockUninstall(targetPackage: String): PolicyEnforcementResult = runWithReadback(
  SandboxPolicyType.UNINSTALL_BLOCKED,
  set = { dpm.setUninstallBlocked(admin, targetPackage, true); true },
  confirm = { dpm.isUninstallBlocked(admin, targetPackage) },
 )

 fun disableUserControl(packages: List<String>): PolicyEnforcementResult = runWithReadback(
  SandboxPolicyType.USER_CONTROL_DISABLED,
  set = { dpm.setUserControlDisabledPackages(admin, packages); true },
  confirm = { dpm.getUserControlDisabledPackages(admin).containsAll(packages) },
 )

 fun enableNetworkLogging(): PolicyEnforcementResult = runWithReadback(
  SandboxPolicyType.NETWORK_LOGGING_ENABLED,
  set = { dpm.setNetworkLoggingEnabled(admin, true); true },
  confirm = { dpm.isNetworkLoggingEnabled(admin) },
 )

 fun enableAlwaysOnVpnLockdown(vpnPackage: String): PolicyEnforcementResult = runWithReadback(
  SandboxPolicyType.ALWAYS_ON_VPN_LOCKDOWN,
  set = { dpm.setAlwaysOnVpnPackage(admin, vpnPackage, true); true },
  confirm = { dpm.getAlwaysOnVpnPackage(admin) == vpnPackage && dpm.isAlwaysOnVpnLockdownEnabled(admin) },
 )

 private inline fun runWithReadback(policy: SandboxPolicyType, set: () -> Boolean, confirm: () -> Boolean): PolicyEnforcementResult {
  val setSucceeded = try { set() } catch (e: Exception) {
   return PolicyEnforcementResult(policy, EnforcementStatus.FAILED, EnforcementMechanism.DEVICE_POLICY_MANAGER, e.toString())
  }
  if (!setSucceeded) return PolicyEnforcementResult(policy, EnforcementStatus.FAILED, EnforcementMechanism.DEVICE_POLICY_MANAGER, "setter reported failure")
  val confirmed = try { confirm() } catch (e: Exception) {
   return PolicyEnforcementResult(policy, EnforcementStatus.FAILED, EnforcementMechanism.DEVICE_POLICY_MANAGER, "readback threw: $e")
  }
  return if (confirmed) PolicyEnforcementResult(policy, EnforcementStatus.ENFORCED, EnforcementMechanism.DEVICE_POLICY_MANAGER, null)
  else PolicyEnforcementResult(policy, EnforcementStatus.FAILED, EnforcementMechanism.DEVICE_POLICY_MANAGER, "readback did not confirm the change")
 }
}
