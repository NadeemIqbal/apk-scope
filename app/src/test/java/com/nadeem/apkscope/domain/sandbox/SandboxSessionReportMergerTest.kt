package com.nadeem.apkscope.domain.sandbox

import com.nadeem.apkscope.core.model.CleanupSummary
import com.nadeem.apkscope.core.model.EnforcementMechanism
import com.nadeem.apkscope.core.model.EnforcementStatus
import com.nadeem.apkscope.core.model.PolicyEnforcementResult
import com.nadeem.apkscope.core.model.Recoverability
import com.nadeem.apkscope.core.model.SandboxError
import com.nadeem.apkscope.core.model.SandboxErrorCode
import com.nadeem.apkscope.core.model.SandboxPolicy
import com.nadeem.apkscope.core.model.SandboxPolicyType
import com.nadeem.apkscope.core.model.SandboxSession
import com.nadeem.apkscope.core.model.SandboxSessionState
import com.nadeem.apkscope.sandbox.SandboxStatusReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Checkpoint 4.1 §19: the merge logic shared by both the push (`SandboxReportActivity`) and pull (`DefaultSandboxSessionCoordinator.importEvidence`) delivery paths — a report is a report regardless of transport. */
class SandboxSessionReportMergerTest {
 private fun session(state: SandboxSessionState = SandboxSessionState.PREPARING) = SandboxSession(
  id = "s1", analysisId = "a1", packageName = "com.example.fixture", state = state,
  requestedPolicy = SandboxPolicy(), createdAt = Instant.EPOCH,
 )

 @Test fun explicitRetryRejectsAllReportsFromPriorGenerationBeforeNewIdArrives() {
  val failed = session(SandboxSessionState.FAILED).copy(installSessionId = 10, installAttemptId = 2,
   error = SandboxError(SandboxErrorCode.INSTALL_USER_CANCELLED, "cancelled", null, Recoverability.RETRYABLE))
  val retry = failed.retryInstallation()
  assertEquals(SandboxSessionState.PREPARING, retry.state)
  assertEquals(3L, retry.installAttemptId)
  assertNull(retry.error)
  for (state in listOf(SandboxSessionState.INSTALLING, SandboxSessionState.FAILED, SandboxSessionState.INSTALLED)) {
   assertTrue(SandboxSessionReportMerger.isStaleInstallReport(retry,
    SandboxStatusReport("s1", state, installSessionId = 10, installAttemptId = 2)))
  }
  val success = SandboxStatusReport("s1", SandboxSessionState.INSTALLED, installSessionId = 12, installAttemptId = 3)
  assertTrue(!SandboxSessionReportMerger.isStaleInstallReport(retry, success))
  val installed = SandboxSessionReportMerger.advanceThroughOperationalStates(
   SandboxSessionReportMerger.mergeFacts(retry, success), success.state)!!
  assertEquals(SandboxSessionState.INSTALLED, installed.state)
  assertEquals(3L, installed.installAttemptId)
  assertTrue(SandboxSessionReportMerger.isStaleInstallReport(installed, success.copy(state = SandboxSessionState.INSTALLING)))
 }

 @Test fun confirmedCurrentSuccessRepairsLegacyInferredFailure() {
  val failed = session(SandboxSessionState.FAILED).copy(installSessionId = 10,
   error = SandboxError(SandboxErrorCode.INSTALL_USER_CANCELLED, "inferred cancellation", null, Recoverability.RETRYABLE))
  val report = SandboxStatusReport("s1", SandboxSessionState.INSTALLED, installSessionId = 10, installAttemptId = 1)
  val merged = SandboxSessionReportMerger.mergeFacts(failed, report)
  assertNull(merged.error)
  assertEquals(SandboxSessionState.INSTALLED,
   SandboxSessionReportMerger.advanceThroughOperationalStates(merged, report.state)?.state)
 }

 @Test(expected = IllegalArgumentException::class) fun retryCannotReviveACompletedSession() {
  session(SandboxSessionState.COMPLETED).retryInstallation()
 }

 @Test fun delayedProgressCannotEraseCurrentCancellation() {
  val failed = session(SandboxSessionState.FAILED).copy(installAttemptId = 2,
   error = SandboxError(SandboxErrorCode.INSTALL_USER_CANCELLED, "cancelled", null, Recoverability.RETRYABLE))
  assertTrue(SandboxSessionReportMerger.isStaleInstallReport(failed,
   SandboxStatusReport("s1", SandboxSessionState.INSTALLING, installAttemptId = 2)))
 }

 @Test fun mergeFactsAppliesEveryNonNullField() {
  val report = SandboxStatusReport(
   "s1", SandboxSessionState.INSTALLED, installSessionId = 3, installedVersionCode = 9L,
   dataClearRequestedAtEpochMs = 100L, dataClearCompletedAtEpochMs = 200L, dataClearResult = true,
  )
  val merged = SandboxSessionReportMerger.mergeFacts(session(), report)
  assertEquals(3, merged.installSessionId)
  assertEquals(9L, merged.installedVersionCode)
  assertEquals(Instant.ofEpochMilli(100L), merged.dataClearRequestedAt)
  assertEquals(Instant.ofEpochMilli(200L), merged.dataClearCompletedAt)
  assertEquals(true, merged.dataClearResult)
 }

 @Test fun mergeFactsLeavesUnsetFieldsAlone() {
  val original = session().copy(installedVersionCode = 42L)
  val report = SandboxStatusReport("s1", SandboxSessionState.WAITING_FOR_UNINSTALL_CONFIRMATION) // no installedVersionCode
  val merged = SandboxSessionReportMerger.mergeFacts(original, report)
  assertEquals(42L, merged.installedVersionCode)
 }

 @Test fun terminalReportFromAnOlderInstallerSessionIsIgnored() {
  val current = session(SandboxSessionState.READY).copy(installSessionId = 11)
  val stale = SandboxStatusReport("s1", SandboxSessionState.FAILED, installSessionId = 10)
  assertTrue(SandboxSessionReportMerger.isStaleInstallReport(current, stale))
 }

 @Test fun newerInstallingReportCanRecoverWhenPushWasMissed() {
  val current = session(SandboxSessionState.INSTALLING).copy(installSessionId = 10)
  val retry = SandboxStatusReport("s1", SandboxSessionState.INSTALLING, installSessionId = 11)
  assertTrue(!SandboxSessionReportMerger.isStaleInstallReport(current, retry))
 }

 @Test fun terminalReportForAReplacementCanRecoverBeforeInstallingPushArrives() {
  val current = session(SandboxSessionState.PREPARING).copy(installSessionId = 10)
  val retry = SandboxStatusReport("s1", SandboxSessionState.INSTALLED, installSessionId = 11)
  assertTrue(!SandboxSessionReportMerger.isStaleInstallReport(current, retry))
 }

 @Test fun mergeFactsPreservesEarlierPoliciesWhenPatchIsPartial() {
  val vpn = listOf(PolicyEnforcementResult(SandboxPolicyType.ALWAYS_ON_VPN_LOCKDOWN, EnforcementStatus.ENFORCED, EnforcementMechanism.VPN_SERVICE, null))
  val original = session().copy(enforcementResults = vpn)
  val camera = PolicyEnforcementResult(SandboxPolicyType.CAMERA_RUNTIME_PERMISSION_DENIAL, EnforcementStatus.NOT_SUPPORTED, EnforcementMechanism.DEVICE_POLICY_MANAGER, null)
  val merged = SandboxSessionReportMerger.mergeFacts(original, SandboxStatusReport("s1", SandboxSessionState.INSTALLED, enforcements = listOf(camera)))
  assertEquals(listOf(vpn.single(), camera), merged.enforcementResults)
 }

 @Test fun mergeFactsReplacesOnlyThePolicyUpdatedByPatch() {
  val oldVpn = PolicyEnforcementResult(SandboxPolicyType.ALWAYS_ON_VPN_LOCKDOWN, EnforcementStatus.FAILED, EnforcementMechanism.DEVICE_POLICY_MANAGER, "old")
  val newVpn = oldVpn.copy(status = EnforcementStatus.ENFORCED, mechanism = EnforcementMechanism.VPN_SERVICE, message = null)
  val merged = SandboxSessionReportMerger.mergeFacts(
   session().copy(enforcementResults = listOf(oldVpn)),
   SandboxStatusReport("s1", SandboxSessionState.READY, enforcements = listOf(newVpn)),
  )
  assertEquals(listOf(newVpn), merged.enforcementResults)
 }

 @Test fun mergeFactsLeavesEnforcementsAloneWhenPatchIsEmpty() {
  val vpn = listOf(PolicyEnforcementResult(SandboxPolicyType.ALWAYS_ON_VPN_LOCKDOWN, EnforcementStatus.ENFORCED, EnforcementMechanism.VPN_SERVICE, null))
  val original = session().copy(enforcementResults = vpn)
  val merged = SandboxSessionReportMerger.mergeFacts(original, SandboxStatusReport("s1", SandboxSessionState.INSTALLED))
  assertEquals(vpn, merged.enforcementResults)
 }

 @Test fun mergeCleanupSummaryFillsInPersonalOnlyFacts() {
  val workVerified = CleanupSummary(appDataCleared = true, apkRemoved = true, workTempApkDeleted = true, personalTempApkDeleted = false, uriGrantReleased = false, networkSessionClosed = false)
  val merged = SandboxSessionReportMerger.mergeCleanupSummary(workVerified, personalCleanupDone = true)
  assertTrue(merged.personalTempApkDeleted)
  assertTrue(merged.uriGrantReleased)
  assertTrue(merged.networkSessionClosed)
  assertTrue(merged.isComplete)
 }

 @Test fun mergeCleanupSummaryHonestlyReflectsFailedPersonalCleanup() {
  val workVerified = CleanupSummary(true, true, true, false, false, false)
  val merged = SandboxSessionReportMerger.mergeCleanupSummary(workVerified, personalCleanupDone = false)
  assertTrue(!merged.personalTempApkDeleted)
  assertTrue(!merged.isComplete)
 }

 @Test fun safeTransitionAppliesALegalMove() {
  val next = SandboxSessionReportMerger.safeTransition(session(SandboxSessionState.CREATED), SandboxSessionState.PREPARING)
  assertEquals(SandboxSessionState.PREPARING, next?.state)
 }

 @Test fun safeTransitionReturnsSameSessionWhenAlreadyAtTarget() {
  val current = session(SandboxSessionState.READY)
  assertEquals(current, SandboxSessionReportMerger.safeTransition(current, SandboxSessionState.READY))
 }

 @Test fun safeTransitionReturnsNullOnIllegalMove_andNeverThrows() {
  // CREATED -> READY skips the entire chain — illegal.
  assertNull(SandboxSessionReportMerger.safeTransition(session(SandboxSessionState.CREATED), SandboxSessionState.READY))
 }

 @Test fun installedReportRecoversWaitingSessionWhenInstallingReportWasLost() {
  val next = SandboxSessionReportMerger.safeTransition(
   session(SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION),
   SandboxSessionState.INSTALLED,
  )
  assertEquals(SandboxSessionState.INSTALLED, next?.state)
 }

 @Test fun installedReportWalksPastMissedPreparationCallbacks() {
  val next = SandboxSessionReportMerger.advanceThroughOperationalStates(
   session(SandboxSessionState.PREPARING),
   SandboxSessionState.INSTALLED,
  )
  assertEquals(SandboxSessionState.INSTALLED, next?.state)
 }

 @Test fun readyReportWalksFromPreparingThroughInstallation() {
  val next = SandboxSessionReportMerger.advanceThroughOperationalStates(
   session(SandboxSessionState.PREPARING),
   SandboxSessionState.READY,
  )
  assertEquals(SandboxSessionState.READY, next?.state)
 }

 @Test fun operationalRecoveryDoesNotInferTerminalOrCleanupStates() {
  assertNull(
   SandboxSessionReportMerger.advanceThroughOperationalStates(
    session(SandboxSessionState.PREPARING),
    SandboxSessionState.FAILED,
   )
  )
  assertNull(
   SandboxSessionReportMerger.advanceThroughOperationalStates(
    session(SandboxSessionState.PREPARING),
    SandboxSessionState.CLEANUP_REQUIRED,
   )
  )
 }

 /**
  * Regression test for the "APK too large for the cross-profile handoff" bug (confirmed on-device: a
  * real 131 MiB APK exceeding the handoff's old 128 MiB limit): before the fix, the copy failure was
  * caught by `SandboxWorkerActivity.onImportFailed` and silently discarded — no report was ever sent,
  * so a `PREPARING` session had no path to `FAILED` and the "Preparing Sandbox" screen spun forever.
  * `onImportFailed` now builds and sends exactly the [SandboxStatusReport] shape asserted here; this
  * test proves the receiving side (`SandboxReportActivity.onImportComplete`, which calls
  * [SandboxSessionReportMerger.safeTransition] exactly like this) actually unsticks a `PREPARING`
  * session into `FAILED` once that report arrives — the transition this whole fix depends on.
  */
 @Test fun aHandoffFailedErrorReportUnsticksAPreparingSessionIntoFailed() {
  val stuckInPreparing = session(SandboxSessionState.PREPARING)
  val error = SandboxError(
   SandboxErrorCode.HANDOFF_FAILED,
   "The APK could not be transferred into the sandbox: Transfer too large.",
   "java.lang.IllegalArgumentException: Transfer too large",
   Recoverability.RETRYABLE,
  )
  val report = SandboxStatusReport(sessionId = stuckInPreparing.id, state = SandboxSessionState.FAILED, error = error)

  // Exactly SandboxReportActivity.onImportComplete's own sequence for report.error != null.
  var next = SandboxSessionReportMerger.mergeFacts(stuckInPreparing, report)
  next = SandboxSessionReportMerger.safeTransition(next, SandboxSessionState.FAILED)?.copy(error = report.error) ?: next

  assertEquals("a PREPARING session must not remain stuck once a HANDOFF_FAILED report arrives", SandboxSessionState.FAILED, next.state)
  assertEquals(SandboxErrorCode.HANDOFF_FAILED, next.error?.code)
  assertEquals(Recoverability.RETRYABLE, next.error?.recoverability)
 }
}
