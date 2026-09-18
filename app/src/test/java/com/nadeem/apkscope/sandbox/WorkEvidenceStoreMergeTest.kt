package com.nadeem.apkscope.sandbox

import com.nadeem.apkscope.core.model.CleanupSummary
import com.nadeem.apkscope.core.model.EnforcementMechanism
import com.nadeem.apkscope.core.model.EnforcementStatus
import com.nadeem.apkscope.core.model.PolicyEnforcementResult
import com.nadeem.apkscope.core.model.SandboxPolicyType
import com.nadeem.apkscope.core.model.SandboxSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checkpoint 4.1 §5/§19: [WorkEvidenceStore.mergeReports] is the accumulation logic that makes the
 * Work-local evidence store durable and cumulative rather than "last write wins, older facts lost"
 * — this is what "evidence survives Work service restart" and "duplicate artifact does not
 * duplicate state" actually rest on at the unit level (the Room-backed persistence itself is
 * covered by `WorkEvidenceDaoTest`, an instrumented test).
 */
class WorkEvidenceStoreMergeTest {
 @Test fun firstPatchBecomesTheCumulativeReport() {
  val patch = SandboxStatusReport("s1", SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION, installSessionId = 5)
  assertEquals(patch, WorkEvidenceStore.mergeReports(null, patch))
 }

 @Test fun laterPatchNeverErasesAnEarlierFactItLeavesUnset() {
  val existing = SandboxStatusReport("s1", SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION, installSessionId = 5)
  val patch = SandboxStatusReport("s1", SandboxSessionState.INSTALLING) // installSessionId unset in this patch
  val merged = WorkEvidenceStore.mergeReports(existing, patch)
  assertEquals(5, merged.installSessionId)
  assertEquals(SandboxSessionState.INSTALLING, merged.state) // state always takes the newest patch's value
 }

 @Test fun applyingTheSamePatchTwiceIsIdempotent() {
  val patch = SandboxStatusReport("s1", SandboxSessionState.INSTALLED, installedVersionCode = 42L)
  val once = WorkEvidenceStore.mergeReports(null, patch)
  val twice = WorkEvidenceStore.mergeReports(once, patch)
  assertEquals(once, twice)
 }

 @Test fun enforcementsAccumulateAcrossPartialPatches() {
  val vpnOnly = listOf(PolicyEnforcementResult(SandboxPolicyType.ALWAYS_ON_VPN_LOCKDOWN, EnforcementStatus.ENFORCED, EnforcementMechanism.VPN_SERVICE, null))
  val existing = SandboxStatusReport("s1", SandboxSessionState.PREPARING, enforcements = vpnOnly)
  val cameraDenied = listOf(PolicyEnforcementResult(SandboxPolicyType.CAMERA_RUNTIME_PERMISSION_DENIAL, EnforcementStatus.NOT_SUPPORTED, EnforcementMechanism.DEVICE_POLICY_MANAGER, null))
  val patch = SandboxStatusReport("s1", SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION, enforcements = cameraDenied)
  val merged = WorkEvidenceStore.mergeReports(existing, patch)
  assertEquals(vpnOnly + cameraDenied, merged.enforcements)
 }

 @Test fun newerEnforcementResultReplacesOnlyTheSamePolicy() {
  val oldVpn = PolicyEnforcementResult(SandboxPolicyType.ALWAYS_ON_VPN_LOCKDOWN, EnforcementStatus.FAILED, EnforcementMechanism.DEVICE_POLICY_MANAGER, "old")
  val newVpn = oldVpn.copy(status = EnforcementStatus.ENFORCED, mechanism = EnforcementMechanism.VPN_SERVICE, message = null)
  val existing = SandboxStatusReport("s1", SandboxSessionState.PREPARING, enforcements = listOf(oldVpn))
  val patch = SandboxStatusReport("s1", SandboxSessionState.READY, enforcements = listOf(newVpn))
  val merged = WorkEvidenceStore.mergeReports(existing, patch)
  assertEquals(listOf(newVpn), merged.enforcements)
 }

 @Test fun emptyEnforcementsInPatchPreservesExisting() {
  val vpnOnly = listOf(PolicyEnforcementResult(SandboxPolicyType.ALWAYS_ON_VPN_LOCKDOWN, EnforcementStatus.ENFORCED, EnforcementMechanism.VPN_SERVICE, null))
  val existing = SandboxStatusReport("s1", SandboxSessionState.PREPARING, enforcements = vpnOnly)
  val patch = SandboxStatusReport("s1", SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION) // no enforcements in this patch
  val merged = WorkEvidenceStore.mergeReports(existing, patch)
  assertEquals(vpnOnly, merged.enforcements)
 }

 @Test fun dataClearFactsAccumulateAcrossSeparatePatches() {
  val requested = SandboxStatusReport("s1", SandboxSessionState.CLEARING_DATA, dataClearRequestedAtEpochMs = 1000L)
  val merged1 = WorkEvidenceStore.mergeReports(null, requested)
  val completed = SandboxStatusReport("s1", SandboxSessionState.CLEARING_DATA, dataClearCompletedAtEpochMs = 2000L, dataClearResult = true)
  val merged2 = WorkEvidenceStore.mergeReports(merged1, completed)
  assertEquals(1000L, merged2.dataClearRequestedAtEpochMs)
  assertEquals(2000L, merged2.dataClearCompletedAtEpochMs)
  assertTrue(merged2.dataClearResult == true)
 }

 @Test fun cleanupSummaryFromLatestPatchWins() {
  val partial = CleanupSummary(appDataCleared = true, apkRemoved = false, workTempApkDeleted = false, personalTempApkDeleted = false, uriGrantReleased = false, networkSessionClosed = false)
  val existing = SandboxStatusReport("s1", SandboxSessionState.CLEANUP_REQUIRED, cleanup = partial)
  val complete = CleanupSummary(true, true, true, true, true, true)
  val patch = SandboxStatusReport("s1", SandboxSessionState.COMPLETED, cleanup = complete)
  val merged = WorkEvidenceStore.mergeReports(existing, patch)
  assertEquals(complete, merged.cleanup)
 }
}
