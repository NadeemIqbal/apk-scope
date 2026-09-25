package com.nadeem.apkscope.sandbox

import com.nadeem.apkscope.core.model.CleanupSummary
import com.nadeem.apkscope.core.model.EnforcementMechanism
import com.nadeem.apkscope.core.model.EnforcementStatus
import com.nadeem.apkscope.core.model.PolicyEnforcementResult
import com.nadeem.apkscope.core.model.Recoverability
import com.nadeem.apkscope.core.model.SandboxError
import com.nadeem.apkscope.core.model.SandboxErrorCode
import com.nadeem.apkscope.core.model.SandboxPolicyType
import com.nadeem.apkscope.core.model.SandboxSessionState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The wire format every work→personal status report round-trips through (checkpoint 4, item 9/16) — `org.json` is a real, functional implementation in Android's unit-test jar (unlike most `android.*` stub classes), so this runs as a plain local JVM test. */
class SandboxStatusReportTest {
 @Test fun minimalReportRoundTrips() {
  val report = SandboxStatusReport(sessionId = "s1", state = SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION, installSessionId = 42, installAttemptId = 7L)
  val restored = SandboxStatusReport.fromJson(report.toJson())
  assertEquals(report, restored)
 }

 @Test fun reportWithEnforcementsRoundTrips() {
  val report = SandboxStatusReport(
   sessionId = "s2", state = SandboxSessionState.FAILED,
   enforcements = listOf(
    PolicyEnforcementResult(SandboxPolicyType.CAMERA_RUNTIME_PERMISSION_DENIAL, EnforcementStatus.NOT_SUPPORTED, EnforcementMechanism.DEVICE_POLICY_MANAGER, "setPermissionGrantState returned false"),
    PolicyEnforcementResult(SandboxPolicyType.ALWAYS_ON_VPN_LOCKDOWN, EnforcementStatus.ENFORCED, EnforcementMechanism.DEVICE_POLICY_MANAGER, null),
   ),
  )
  val restored = SandboxStatusReport.fromJson(report.toJson())
  assertEquals(report.enforcements, restored.enforcements)
 }

 @Test fun reportWithErrorRoundTrips() {
  val error = SandboxError(SandboxErrorCode.PACKAGE_MISMATCH, "The installed app does not match.", "expected=a actual=b", Recoverability.TERMINAL)
  val report = SandboxStatusReport(sessionId = "s3", state = SandboxSessionState.FAILED, error = error)
  val restored = SandboxStatusReport.fromJson(report.toJson())
  assertEquals(error, restored.error)
 }

 @Test fun reportWithCleanupSummaryRoundTrips() {
  val summary = CleanupSummary(appDataCleared = true, apkRemoved = true, workTempApkDeleted = true, personalTempApkDeleted = false, uriGrantReleased = false, networkSessionClosed = false)
  val report = SandboxStatusReport(sessionId = "s4", state = SandboxSessionState.CLEANUP_REQUIRED, cleanup = summary)
  val restored = SandboxStatusReport.fromJson(report.toJson())
  assertEquals(summary, restored.cleanup)
  assertEquals(false, restored.cleanup?.isComplete)
 }

 @Test fun reportWithDataClearFieldsRoundTrips() {
  val report = SandboxStatusReport(sessionId = "s5", state = SandboxSessionState.CLEARING_DATA, dataClearRequestedAtEpochMs = 1000L, dataClearCompletedAtEpochMs = 1050L, dataClearResult = true)
  val restored = SandboxStatusReport.fromJson(report.toJson())
  assertEquals(1000L, restored.dataClearRequestedAtEpochMs)
  assertEquals(1050L, restored.dataClearCompletedAtEpochMs)
  assertEquals(true, restored.dataClearResult)
 }
}
