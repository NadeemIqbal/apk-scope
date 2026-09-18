package com.nadeem.apkscope.ui.screens.sandbox

import com.nadeem.apkscope.core.model.EnforcementStatus
import com.nadeem.apkscope.core.model.PolicyEnforcementResult
import com.nadeem.apkscope.core.model.SandboxPolicyType
import com.nadeem.apkscope.ui.common.UiStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Checkpoint 4, item 10/21's explicit security invariant: "no fake green checks" / "cannot report
 * an unsupported restriction as enforced." [Ready to Run][SandboxReadyScreen] renders every
 * restriction through [toUiStatus] — this test pins that mapping so [UiStatus.READY] can only ever
 * come from a real [EnforcementStatus.ENFORCED] result, never from [EnforcementStatus.NOT_SUPPORTED]
 * or [EnforcementStatus.FAILED].
 */
class SandboxEnforcementStatusMappingTest {
 private fun result(status: EnforcementStatus) = PolicyEnforcementResult(SandboxPolicyType.ALWAYS_ON_VPN_LOCKDOWN, status, null, null)

 @Test fun enforcedMapsToReady() {
  assertEquals(UiStatus.READY, result(EnforcementStatus.ENFORCED).toUiStatus())
 }

 @Test fun notSupportedNeverMapsToReady() {
  assertNotEquals(UiStatus.READY, result(EnforcementStatus.NOT_SUPPORTED).toUiStatus())
  assertEquals(UiStatus.UNAVAILABLE, result(EnforcementStatus.NOT_SUPPORTED).toUiStatus())
 }

 @Test fun failedNeverMapsToReady() {
  assertNotEquals(UiStatus.READY, result(EnforcementStatus.FAILED).toUiStatus())
  assertEquals(UiStatus.ERROR, result(EnforcementStatus.FAILED).toUiStatus())
 }

 @Test fun missingResultNeverMapsToReady() {
  assertNotEquals(UiStatus.READY, (null as PolicyEnforcementResult?).toUiStatus())
  assertEquals(UiStatus.CHECKING, (null as PolicyEnforcementResult?).toUiStatus())
 }
}
