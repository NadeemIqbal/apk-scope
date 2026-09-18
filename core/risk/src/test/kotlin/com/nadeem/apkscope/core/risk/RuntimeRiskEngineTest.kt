package com.nadeem.apkscope.core.risk

import com.nadeem.apkscope.core.model.AndroidConnectEvidence
import com.nadeem.apkscope.core.model.AndroidDnsEvidence
import com.nadeem.apkscope.core.model.AndroidEvidenceStatus
import com.nadeem.apkscope.core.model.EvidenceSource
import com.nadeem.apkscope.core.model.NetworkObservation
import com.nadeem.apkscope.core.model.ObservedBehaviorSummary
import com.nadeem.apkscope.core.model.RiskLevel
import com.nadeem.apkscope.core.model.RiskSeverity
import com.nadeem.apkscope.core.model.RuntimeRiskInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RuntimeRiskEngineTest {

 private val engine = DefaultRuntimeRiskEngine()
 private val now = Instant.parse("2026-09-09T10:00:00Z")

 @Test
 fun emptyInputYieldsZeroScoreAndNoFindings() {
  val input = RuntimeRiskInput(sessionId = "s1", packageName = "com.example.app")
  val result = engine.evaluate(input)
  assertEquals(0, result.score)
  assertEquals(RiskLevel.LOW, result.level)
  assertTrue(result.findings.isEmpty())
 }

 @Test
 fun networkActivityObservedFiresForVpnConnection() {
  val input = RuntimeRiskInput(
   sessionId = "s1",
   packageName = "com.example.app",
   observations = listOf(
    NetworkObservation.ConnectionOpened(now, 1L, NetworkObservation.Protocol.TCP, "93.184.216.34", 443),
   ),
  )
  val result = engine.evaluate(input)
  val finding = result.findings.firstOrNull { it.ruleId == RuleIds.RUNTIME_NETWORK_ACTIVITY }
  assertNotNull(finding)
  assertEquals(5, finding!!.scoreContribution)
  assertEquals(RiskSeverity.LOW, finding.severity)
  assertTrue(finding.evidence.any { it.source == EvidenceSource.OBSERVED_BEHAVIOR })
 }

 @Test
 fun networkActivityObservedFiresForDpmConnect() {
  val input = RuntimeRiskInput(
   sessionId = "s1",
   packageName = "com.example.app",
   androidConnect = listOf(
    AndroidConnectEvidence(101L, 1L, "com.example.app", now, now, "93.184.216.34", 443),
   ),
  )
  val result = engine.evaluate(input)
  val finding = result.findings.firstOrNull { it.ruleId == RuleIds.RUNTIME_NETWORK_ACTIVITY }
  assertNotNull(finding)
  assertEquals(5, finding!!.scoreContribution)
  assertTrue(finding.evidence.any { it.source == EvidenceSource.ANDROID_EVIDENCE })
 }

 @Test
 fun directRawIpConnectionFiresWhenNoDnsResolutionPreceded() {
  val input = RuntimeRiskInput(
   sessionId = "s1",
   packageName = "com.example.app",
   observations = listOf(
    NetworkObservation.ConnectionOpened(now, 1L, NetworkObservation.Protocol.TCP, "198.51.100.1", 443),
   ),
  )
  val result = engine.evaluate(input)
  val rawIpFinding = result.findings.firstOrNull { it.ruleId == RuleIds.RUNTIME_RAW_IP_CONNECTION }
  assertNotNull(rawIpFinding)
  assertEquals(10, rawIpFinding!!.scoreContribution)
  assertEquals(RiskSeverity.MEDIUM, rawIpFinding.severity)
 }

 @Test
 fun rawIpConnectionDoesNotFireWhenResolvedByDns() {
  val input = RuntimeRiskInput(
   sessionId = "s1",
   packageName = "com.example.app",
   observations = listOf(
    NetworkObservation.DnsResponse(now, 1, "example.com", listOf("198.51.100.1"), 53000),
    NetworkObservation.ConnectionOpened(now.plusSeconds(1), 1L, NetworkObservation.Protocol.TCP, "198.51.100.1", 443),
   ),
  )
  val result = engine.evaluate(input)
  val rawIpFinding = result.findings.firstOrNull { it.ruleId == RuleIds.RUNTIME_RAW_IP_CONNECTION }
  assertNull(rawIpFinding)
 }

 @Test
 fun privateNetworkAccessFiresForVpnPolicyDenial() {
  val input = RuntimeRiskInput(
   sessionId = "s1",
   packageName = "com.example.app",
   observations = listOf(
    NetworkObservation.ConnectionFailed(
     now, NetworkObservation.Protocol.TCP, "192.168.1.1", 80,
     NetworkObservation.FailureReason.POLICY_DENIED, "RFC1918 blocked",
    ),
   ),
  )
  val result = engine.evaluate(input)
  val finding = result.findings.firstOrNull { it.ruleId == RuleIds.RUNTIME_POLICY_DENIED_DESTINATION }
  assertNotNull(finding)
  assertEquals(20, finding!!.scoreContribution)
  assertEquals(RiskSeverity.HIGH, finding.severity)
  assertTrue(finding.evidence.any { it.source == EvidenceSource.OBSERVED_BEHAVIOR })
 }

 @Test
 fun highOutboundDataFiresAboveOneMegabyteThreshold() {
  // Boundary: exactly 1 MB (1048576) does not fire
  val exact1Mb = RuntimeRiskInput(
   sessionId = "s1",
   packageName = "com.example.app",
   summary = ObservedBehaviorSummary(
    sessionId = "s1", connectionCount = 1, dnsQueryCount = 1, uniqueObservedDomains = 1,
    uploadedBytes = 1048576L, downloadedBytes = 0L, blockedConnectionCount = 0, failedConnectionCount = 0,
   ),
  )
  assertNull(engine.evaluate(exact1Mb).findings.firstOrNull { it.ruleId == RuleIds.RUNTIME_HIGH_OUTBOUND_DATA })

  // Boundary: 1 MB + 1 byte fires
  val above1Mb = RuntimeRiskInput(
   sessionId = "s1",
   packageName = "com.example.app",
   summary = ObservedBehaviorSummary(
    sessionId = "s1", connectionCount = 1, dnsQueryCount = 1, uniqueObservedDomains = 1,
    uploadedBytes = 1048577L, downloadedBytes = 0L, blockedConnectionCount = 0, failedConnectionCount = 0,
   ),
  )
  val finding = engine.evaluate(above1Mb).findings.firstOrNull { it.ruleId == RuleIds.RUNTIME_HIGH_OUTBOUND_DATA }
  assertNotNull(finding)
  assertEquals(15, finding!!.scoreContribution)
  assertEquals(RiskSeverity.MEDIUM, finding.severity)
 }

 @Test
 fun manyDistinctDestinationsFiresAtThresholdFive() {
  // 4 destinations: does not fire
  val fourDests = RuntimeRiskInput(
   sessionId = "s1",
   packageName = "com.example.app",
   observations = (1..4).map { i ->
    NetworkObservation.ConnectionOpened(now, i.toLong(), NetworkObservation.Protocol.TCP, "203.0.113.$i", 443)
   },
  )
  assertNull(engine.evaluate(fourDests).findings.firstOrNull { it.ruleId == RuleIds.RUNTIME_MANY_DISTINCT_DESTINATIONS })

  // 5 destinations: fires
  val fiveDests = RuntimeRiskInput(
   sessionId = "s1",
   packageName = "com.example.app",
   observations = (1..5).map { i ->
    NetworkObservation.ConnectionOpened(now, i.toLong(), NetworkObservation.Protocol.TCP, "203.0.113.$i", 443)
   },
  )
  val finding = engine.evaluate(fiveDests).findings.firstOrNull { it.ruleId == RuleIds.RUNTIME_MANY_DISTINCT_DESTINATIONS }
  assertNotNull(finding)
  assertEquals(10, finding!!.scoreContribution)
 }

 @Test
 fun dnsActivityIsInformationalAndZeroScore() {
  val input = RuntimeRiskInput(
   sessionId = "s1",
   packageName = "com.example.app",
   observations = listOf(
    NetworkObservation.DnsQuery(now, 1, "example.com", 53000),
   ),
  )
  val result = engine.evaluate(input)
  val finding = result.findings.firstOrNull { it.ruleId == RuleIds.RUNTIME_DNS_ACTIVITY }
  assertNotNull(finding)
  assertEquals(0, finding!!.scoreContribution)
  assertEquals(RiskSeverity.INFO, finding.severity)
  assertEquals(0, result.score)
 }

 @Test
 fun ordinarySingleHttpsTrafficScoresLowExpectedRisk() {
  val input = RuntimeRiskInput(
   sessionId = "s1",
   packageName = "com.example.app",
   observations = listOf(
    NetworkObservation.DnsResponse(now, 1, "api.example.com", listOf("93.184.216.34"), 53000),
    NetworkObservation.ConnectionOpened(now.plusMillis(10), 1L, NetworkObservation.Protocol.TCP, "93.184.216.34", 443),
    NetworkObservation.ConnectionClosed(now.plusMillis(500), 1L, NetworkObservation.Protocol.TCP, "93.184.216.34", 443, now, now.plusMillis(500), 1200L, 8500L),
   ),
   androidDns = listOf(
    AndroidDnsEvidence(1L, 100L, "com.example.app", now, now.plusMillis(50), "api.example.com", listOf("93.184.216.34"), 1),
   ),
   androidConnect = listOf(
    AndroidConnectEvidence(2L, 100L, "com.example.app", now.plusMillis(10), now.plusMillis(50), "93.184.216.34", 443),
   ),
   androidStatus = AndroidEvidenceStatus.READY,
  )
  val result = engine.evaluate(input)
  // Network activity (+5) + DNS (+0) = 5
  assertEquals(5, result.score)
  assertEquals(RiskLevel.LOW, result.level)
 }

 @Test
 fun evaluationIsDeterministic() {
  val input = RuntimeRiskInput(
   sessionId = "session-123",
   packageName = "com.example.app",
   observations = listOf(
    NetworkObservation.DnsResponse(now, 1, "example.com", listOf("93.184.216.34"), 53000),
    NetworkObservation.ConnectionOpened(now, 1L, NetworkObservation.Protocol.TCP, "93.184.216.34", 443),
    NetworkObservation.ConnectionFailed(now, NetworkObservation.Protocol.TCP, "10.0.0.1", 80, NetworkObservation.FailureReason.POLICY_DENIED, "RFC1918"),
   ),
  )
  val first = engine.evaluate(input)
  val second = engine.evaluate(input)
  assertEquals(first, second)
 }
}
