package com.nadeem.apkscope.core.model

/**
 * The Physical Pixel 8 Validation Gate's central empirical finding, made structural: this system
 * must never report a sandbox restriction as active unless Android actually confirmed it took
 * effect. On the tested BYOD Work Profile, `DevicePolicyManager.setPermissionGrantState(...,
 * PERMISSION_GRANT_STATE_DENIED)` returned `false` for `CAMERA`, `RECORD_AUDIO`, and
 * `ACCESS_FINE_LOCATION` — `dumpsys device_policy` reported `Admin can grant sensors permission:
 * false` — while the identical call pattern for other packages/policies (setApplicationHidden,
 * setPackagesSuspended, setUninstallBlocked) succeeded on the same device. A capability model of
 * the form `cameraAllowed = false` cannot represent this: it has no way to distinguish "we asked
 * Android to deny it and Android confirmed" from "we asked and Android silently declined" from
 * "we never asked." See `PIXEL8_PHYSICAL_VALIDATION.md` for the full evidence.
 */
enum class SandboxPolicyType {
 CAMERA_RUNTIME_PERMISSION_DENIAL,
 MICROPHONE_RUNTIME_PERMISSION_DENIAL,
 LOCATION_RUNTIME_PERMISSION_DENIAL,
 /** Profile-wide `DevicePolicyManager.setCameraDisabled` — verified working independently of the per-app runtime-permission denial above; these are NOT equivalent and must be modeled/reported separately. */
 CAMERA_PROFILE_WIDE_DISABLE,
 /** The AUTO_DENY-style blanket `setPermissionPolicy` — a policy *default*, distinct from denying one specific already-installed package's one specific runtime permission via `setPermissionGrantState`. */
 AUTO_DENY_PERMISSION_POLICY,
 APPLICATION_HIDDEN,
 PACKAGES_SUSPENDED,
 UNINSTALL_BLOCKED,
 USER_CONTROL_DISABLED,
 NETWORK_LOGGING_ENABLED,
 ALWAYS_ON_VPN_LOCKDOWN,
}

/** How a [SandboxPolicyType] would be (or was) enforced — recorded so a [PolicyEnforcementResult] can say not just *whether* it worked but *what mechanism was tried*. */
enum class EnforcementMechanism {
 DEVICE_POLICY_MANAGER,
 VPN_SERVICE,
 PACKAGE_MANAGER,
}

enum class EnforcementStatus {
 /** Android's own API confirmed the change took effect — verified by reading the state back (e.g. `getPermissionGrantState` echoing what was just set), not merely by the setter not throwing. */
 ENFORCED,
 /** This device/Android build/profile configuration does not support enforcing this policy at all — e.g. `setPermissionGrantState` returning `false` for a sensor permission on a BYOD Work Profile. Distinct from [FAILED]: this is an expected, permanent platform boundary, not a transient error. */
 NOT_SUPPORTED,
 /** Enforcement was attempted and did not take effect, or threw, for a reason other than a known platform limitation (e.g. the target package is not yet installed in this profile). */
 FAILED,
}

data class PolicyEnforcementResult(
 val policy: SandboxPolicyType,
 val status: EnforcementStatus,
 val mechanism: EnforcementMechanism?,
 val message: String?,
) {
 /** Convenience for UI/report code: only [EnforcementStatus.ENFORCED] may ever be presented to a user as an active restriction — see the "Restrictions unavailable on this device" UI language requirement. */
 val isActive: Boolean get() = status == EnforcementStatus.ENFORCED
}
