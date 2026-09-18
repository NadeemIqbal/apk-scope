package com.nadeem.apkscope.sandbox

import com.nadeem.apkscope.core.model.SandboxSessionState
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checkpoint 4.1 §16/19: the security-validation gate on every pulled evidence envelope —
 * "wrong sessionId rejected", "wrong packageName rejected" from the exit criteria's test list.
 */
class WorkEvidenceEnvelopeTest {
 private fun envelope(sessionId: String = "session-1", packageName: String? = "com.example.fixture", withReport: Boolean = true): WorkEvidenceEnvelope {
  val report = if (withReport) SandboxStatusReport(sessionId, SandboxSessionState.INSTALLED, installedVersionCode = 7L) else null
  return WorkEvidenceEnvelope(sessionId, packageName, report)
 }

 @Test fun validWhenSessionAndPackageMatch() {
  assertTrue(envelope().isValidFor(expectedSessionId = "session-1", expectedPackageName = "com.example.fixture"))
 }

 @Test fun rejectedWhenSessionIdDiffers() {
  assertFalse(envelope(sessionId = "session-1").isValidFor(expectedSessionId = "session-2", expectedPackageName = "com.example.fixture"))
 }

 @Test fun rejectedWhenSessionIdEmpty() {
  assertFalse(envelope(sessionId = "").isValidFor(expectedSessionId = "", expectedPackageName = "com.example.fixture"))
 }

 @Test fun rejectedWhenPackageNameDiffers() {
  assertFalse(envelope(packageName = "com.evil.other").isValidFor(expectedSessionId = "session-1", expectedPackageName = "com.example.fixture"))
 }

 @Test fun validWhenPackageNameNotYetKnownEitherSide() {
  // Checkpoint 4.1 §5: an early Work-side failure (before the APK archive was parsed) records an
  // empty packageName — this must not be treated as a hard mismatch.
  assertTrue(envelope(packageName = null).isValidFor(expectedSessionId = "session-1", expectedPackageName = ""))
  assertTrue(envelope(packageName = null).isValidFor(expectedSessionId = "session-1", expectedPackageName = "com.example.fixture"))
 }

 @Test fun fromJsonParsesReportWhenPresent() {
  val json = JSONObject().put("sessionId", "session-1").put("packageName", "com.example.fixture")
   .put("report", SandboxStatusReport("session-1", SandboxSessionState.READY).toJson())
  val parsed = WorkEvidenceEnvelope.fromJson(json)
  assertTrue(parsed.isValidFor("session-1", "com.example.fixture"))
  assertTrue(parsed.report != null)
 }

 @Test fun fromJsonToleratesMissingReport() {
  val json = JSONObject().put("sessionId", "session-1")
  val parsed = WorkEvidenceEnvelope.fromJson(json)
  assertNull(parsed.report)
  assertTrue(parsed.isValidFor("session-1", "")) // still a valid answer — just "nothing known yet"
 }
}
