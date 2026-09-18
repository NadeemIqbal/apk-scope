package com.nadeem.apkscope.core.risk

import com.nadeem.apkscope.core.model.DeclaredCapability
import com.nadeem.apkscope.core.model.EvidenceSource
import com.nadeem.apkscope.core.model.NetworkObservation
import com.nadeem.apkscope.core.model.RiskFinding
import com.nadeem.apkscope.core.model.RiskLevel
import com.nadeem.apkscope.core.model.RiskSeverity
import com.nadeem.apkscope.core.model.RuntimeRiskAssessment
import com.nadeem.apkscope.core.model.RuntimeRiskInput
import com.nadeem.apkscope.core.model.StaticRiskAssessment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CombinedRiskEngineTest {

 private val combinedEngine = DefaultCombinedRiskEngine()
 private val runtimeEngine = DefaultRuntimeRiskEngine()
 private val now = Instant.parse("2026-09-09T10:00:00Z")

 private fun staticAssessment(
  score: Int = 10,
  permissions: List<String> = emptyList(),
  findings: List<RiskFinding> = emptyList(),
 ) = StaticRiskAssessment(
  score = score,
  level = RiskLevel.LOW,
  findings = findings,
  basedOnDeclaredCapabilities = permissions.map { DeclaredCapability(it) },
  engineVersion = RISK_ENGINE_VERSION,
 )

 private fun runtimeWithNetwork(sessionId: String = "s1"): Pair<RuntimeRiskInput, RuntimeRiskAssessment> {
  val input = RuntimeRiskInput(
   sessionId = sessionId,
   packageName = "com.example.app",
   observations = listOf(
    NetworkObservation.DnsResponse(now, 1, "example.com", listOf("93.184.216.34"), 53000),
    NetworkObservation.ConnectionOpened(now.plusMillis(10), 1L, NetworkObservation.Protocol.TCP, "93.184.216.34", 443),
   ),
  )
  return input to runtimeEngine.evaluate(input)
 }

 @Test
 fun staticOnlyWithoutRuntimeProducesNoCombinedFindings() {
  val static = staticAssessment(score = 20, permissions = listOf("android.permission.READ_CONTACTS"))
  val combined = combinedEngine.evaluate(static, null, null)
  assertFalse(combined.isRuntimeComplete)
  assertTrue(combined.combinedFindings.isEmpty())
  assertEquals(20, combined.overallScore)
  assertNull(combined.runtime)
 }

 @Test
 fun runtimeWithoutMatchingStaticCapabilitiesProducesNoCombinedFindings() {
  val static = staticAssessment(score = 0, permissions = listOf("android.permission.VIBRATE"))
  val (input, runtime) = runtimeWithNetwork()
  val combined = combinedEngine.evaluate(static, runtime, input)
  assertTrue(combined.isRuntimeComplete)
  assertTrue(combined.combinedFindings.isEmpty())
  assertEquals(5, combined.overallScore) // 0 static + 5 runtime = 5
 }

 @Test
 fun contactsWithNetworkActivityTriggersCombinedFinding() {
  val static = staticAssessment(score = 10, permissions = listOf("android.permission.READ_CONTACTS"))
  val (input, runtime) = runtimeWithNetwork()
  val combined = combinedEngine.evaluate(static, runtime, input)

  val finding = combined.combinedFindings.firstOrNull { it.ruleId == RuleIds.COMBINED_CONTACTS_NETWORK }
  assertNotNull(finding)
  assertEquals(15, finding!!.scoreContribution)
  assertEquals(RiskSeverity.MEDIUM, finding.severity)
  assertTrue(finding.evidence.any { it.source == EvidenceSource.DECLARED_CAPABILITY })
  assertTrue(finding.evidence.any { it.source == EvidenceSource.OBSERVED_BEHAVIOR })
  // Total score: 10 static + 5 runtime + 15 combined = 30
  assertEquals(30, combined.overallScore)
  assertEquals(RiskLevel.MODERATE, combined.overallLevel)
 }

 @Test
 fun smsWithNetworkActivityTriggersCombinedFinding() {
  val static = staticAssessment(score = 15, permissions = listOf("android.permission.READ_SMS"))
  val (input, runtime) = runtimeWithNetwork()
  val combined = combinedEngine.evaluate(static, runtime, input)

  val finding = combined.combinedFindings.firstOrNull { it.ruleId == RuleIds.COMBINED_SMS_NETWORK }
  assertNotNull(finding)
  assertEquals(20, finding!!.scoreContribution)
  assertEquals(RiskSeverity.HIGH, finding.severity)
 }

 @Test
 fun locationWithNetworkActivityTriggersCombinedFinding() {
  val static = staticAssessment(score = 10, permissions = listOf("android.permission.ACCESS_FINE_LOCATION"))
  val (input, runtime) = runtimeWithNetwork()
  val combined = combinedEngine.evaluate(static, runtime, input)

  val finding = combined.combinedFindings.firstOrNull { it.ruleId == RuleIds.COMBINED_LOCATION_NETWORK }
  assertNotNull(finding)
  assertEquals(15, finding!!.scoreContribution)
 }

 @Test
 fun accessibilityWithNetworkActivityTriggersCombinedFinding() {
  val static = staticAssessment(score = 20, permissions = listOf("android.permission.BIND_ACCESSIBILITY_SERVICE"))
  val (input, runtime) = runtimeWithNetwork()
  val combined = combinedEngine.evaluate(static, runtime, input)

  val finding = combined.combinedFindings.firstOrNull { it.ruleId == RuleIds.COMBINED_ACCESSIBILITY_NETWORK }
  assertNotNull(finding)
  assertEquals(25, finding!!.scoreContribution)
 }

 @Test
 fun accessibilityAndOverlayWithNetworkActivityTriggersHigherWeightedFinding() {
  val static = staticAssessment(
   score = 30,
   permissions = listOf("android.permission.BIND_ACCESSIBILITY_SERVICE", "android.permission.SYSTEM_ALERT_WINDOW"),
  )
  val (input, runtime) = runtimeWithNetwork()
  val combined = combinedEngine.evaluate(static, runtime, input)

  val finding = combined.combinedFindings.firstOrNull { it.ruleId == RuleIds.COMBINED_ACCESSIBILITY_OVERLAY_NETWORK }
  assertNotNull(finding)
  assertEquals(35, finding!!.scoreContribution)
  // Ensure individual accessibility rule did not also fire redundantly
  assertNull(combined.combinedFindings.firstOrNull { it.ruleId == RuleIds.COMBINED_ACCESSIBILITY_NETWORK })
 }

 @Test
 fun bootPersistenceWithNetworkActivityTriggersCombinedFinding() {
  val static = staticAssessment(score = 10, permissions = listOf("android.permission.RECEIVE_BOOT_COMPLETED"))
  val (input, runtime) = runtimeWithNetwork()
  val combined = combinedEngine.evaluate(static, runtime, input)

  val finding = combined.combinedFindings.firstOrNull { it.ruleId == RuleIds.COMBINED_BOOT_PERSISTENCE_NETWORK }
  assertNotNull(finding)
  assertEquals(15, finding!!.scoreContribution)
 }

 @Test
 fun installPackagesWithNetworkActivityTriggersCombinedFinding() {
  val static = staticAssessment(score = 15, permissions = listOf("android.permission.REQUEST_INSTALL_PACKAGES"))
  val (input, runtime) = runtimeWithNetwork()
  val combined = combinedEngine.evaluate(static, runtime, input)

  val finding = combined.combinedFindings.firstOrNull { it.ruleId == RuleIds.COMBINED_INSTALL_PACKAGES_NETWORK }
  assertNotNull(finding)
  assertEquals(20, finding!!.scoreContribution)
 }

 @Test
 fun sensitiveCapabilitiesWithPrivateNetworkAccessAttemptTriggersHighRiskFinding() {
  val static = staticAssessment(
   score = 25,
   permissions = listOf("android.permission.READ_CONTACTS", "android.permission.CAMERA"),
  )
  val input = RuntimeRiskInput(
   sessionId = "s1",
   packageName = "com.example.app",
   observations = listOf(
    NetworkObservation.ConnectionFailed(
     now, NetworkObservation.Protocol.TCP, "192.168.1.50", 8080,
     NetworkObservation.FailureReason.POLICY_DENIED, "RFC1918 blocked",
    ),
   ),
  )
  val runtime = runtimeEngine.evaluate(input)
  val combined = combinedEngine.evaluate(static, runtime, input)

  val finding = combined.combinedFindings.firstOrNull { it.ruleId == RuleIds.COMBINED_SENSITIVE_CAPABILITY_PRIVATE_DESTINATION }
  assertNotNull(finding)
  assertEquals(30, finding!!.scoreContribution)
  assertEquals(RiskSeverity.HIGH, finding.severity)
 }

 @Test
 fun evaluationIsDeterministic() {
  val static = staticAssessment(score = 20, permissions = listOf("android.permission.READ_CONTACTS", "android.permission.READ_SMS"))
  val (input, runtime) = runtimeWithNetwork()
  val first = combinedEngine.evaluate(static, runtime, input)
  val second = combinedEngine.evaluate(static, runtime, input)
  assertEquals(first, second)
 }
}
