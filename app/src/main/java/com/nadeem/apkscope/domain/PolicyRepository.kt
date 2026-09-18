package com.nadeem.apkscope.domain

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.nadeem.apkscope.core.model.EnforcementStatus
import com.nadeem.apkscope.core.model.PolicyEnforcementResult
import com.nadeem.apkscope.core.model.SandboxPolicyType
import com.nadeem.apkscope.core.sandbox.PolicyEnforcer
import com.nadeem.apkscope.spike.SandboxAdminReceiver

/**
 * Configure/Prepare Sandbox's bridge to `core:sandbox`'s [PolicyEnforcer] (item 11/12) — every
 * call here is real, not simulated: on a device where this app is not yet a profile owner (the
 * production provisioning flow is Slice 4, not this checkpoint), these calls legitimately return
 * [EnforcementStatus.FAILED] with the real `SecurityException` message, which is the *correct*,
 * honest thing to show — never silently promoted to a fake "Ready".
 */
class PolicyRepository(private val context: Context) {
 private val dpm = context.getSystemService(DevicePolicyManager::class.java)
 private val admin = ComponentName(context, SandboxAdminReceiver::class.java)
 private val enforcer = PolicyEnforcer(dpm, admin)

 /** Camera/microphone/location runtime-permission denial for [targetPackage] — the exact three policies the Physical Pixel 8 gate found sensor permissions [EnforcementStatus.NOT_SUPPORTED] for on a BYOD Work Profile. */
 fun denyCamera(targetPackage: String): PolicyEnforcementResult = safe(SandboxPolicyType.CAMERA_RUNTIME_PERMISSION_DENIAL) {
  enforcer.denyRuntimePermission(SandboxPolicyType.CAMERA_RUNTIME_PERMISSION_DENIAL, targetPackage, "android.permission.CAMERA")
 }
 fun denyMicrophone(targetPackage: String): PolicyEnforcementResult = safe(SandboxPolicyType.MICROPHONE_RUNTIME_PERMISSION_DENIAL) {
  enforcer.denyRuntimePermission(SandboxPolicyType.MICROPHONE_RUNTIME_PERMISSION_DENIAL, targetPackage, "android.permission.RECORD_AUDIO")
 }
 fun denyLocation(targetPackage: String): PolicyEnforcementResult = safe(SandboxPolicyType.LOCATION_RUNTIME_PERMISSION_DENIAL) {
  enforcer.denyRuntimePermission(SandboxPolicyType.LOCATION_RUNTIME_PERMISSION_DENIAL, targetPackage, "android.permission.ACCESS_FINE_LOCATION")
 }
 fun enableAlwaysOnVpnLockdown(): PolicyEnforcementResult = safe(SandboxPolicyType.ALWAYS_ON_VPN_LOCKDOWN) { enforcer.enableAlwaysOnVpnLockdown(context.packageName) }

 /** [dpm] itself can be null on very unusual configurations (no device-policy service) — degrade to a clean [EnforcementStatus.FAILED] rather than crash the screen. */
 private inline fun safe(policy: SandboxPolicyType, block: () -> PolicyEnforcementResult): PolicyEnforcementResult =
  if (dpm == null) PolicyEnforcementResult(policy, EnforcementStatus.FAILED, null, "DevicePolicyManager unavailable on this device")
  else try { block() } catch (e: Exception) { PolicyEnforcementResult(policy, EnforcementStatus.FAILED, null, e.toString()) }
}
