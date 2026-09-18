package com.nadeem.apkscope.core.risk

import com.nadeem.apkscope.core.model.AndroidConnectEvidence
import com.nadeem.apkscope.core.model.AndroidDnsEvidence
import com.nadeem.apkscope.core.model.AndroidEvidenceStatus
import com.nadeem.apkscope.core.model.DeclaredCapability
import com.nadeem.apkscope.core.model.EvidenceSource
import com.nadeem.apkscope.core.model.NetworkObservation
import com.nadeem.apkscope.core.model.RiskLevel
import com.nadeem.apkscope.core.model.RuntimeRiskInput
import com.nadeem.apkscope.core.model.StaticRiskAssessment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Checkpoint 7, items 6 & 33: Corroboration is not duplication.
 *
 * When both VPN and DPM sensors observe the same socket or DNS activity,
 * they must corroborate the observation with independent evidence references
 * rather than double- or triple-counting the risk score.
 */
class DoubleCountingPreventionTest {

 private val runtimeEngine = DefaultRuntimeRiskEngine()
 private val combinedEngine = DefaultCombinedRiskEngine()
 private val now = Instant.parse("2026-09-09T10:00:00Z")

 @Test
 fun vpnAndDpmCoObservationDoesNotDoubleCountNetworkActivity() {
  val vpnOnlyInput = RuntimeRiskInput(
   sessionId = "s1",
   packageName = "com.example.app",
   observations = listOf(
    NetworkObservation.DnsResponse(now, 1, "example.com", listOf("104.20.23.154"), 53000),
    NetworkObservation.ConnectionOpened(now.plusMillis(10), 1L, NetworkObservation.Protocol.TCP, "104.20.23.154", 443),
   ),
  )
  val vpnOnlyResult = runtimeEngine.evaluate(vpnOnlyInput)

  val bothSensorsInput = RuntimeRiskInput(
   sessionId = "s1",
   packageName = "com.example.app",
   observations = listOf(
    NetworkObservation.DnsResponse(now, 1, "example.com", listOf("104.20.23.154"), 53000),
    NetworkObservation.ConnectionOpened(now.plusMillis(10), 1L, NetworkObservation.Protocol.TCP, "104.20.23.154", 443),
   ),
   androidConnect = listOf(
    AndroidConnectEvidence(101L, 1L, "com.example.app", now, now.plusMillis(10), "104.20.23.154", 443),
   ),
   androidStatus = AndroidEvidenceStatus.READY,
  )
  val bothSensorsResult = runtimeEngine.evaluate(bothSensorsInput)

  // Score must be identical: 5 points for network activity, NOT 10 points!
  assertEquals(5, vpnOnlyResult.score)
  assertEquals(5, bothSensorsResult.score)

  // There must be exactly ONE network activity finding, not two
  val findings = bothSensorsResult.findings.filter { it.ruleId == RuleIds.RUNTIME_NETWORK_ACTIVITY }
  assertEquals(1, findings.size)

  // But the single finding must cite BOTH independent evidence sources
  val finding = findings.first()
  assertTrue(finding.evidence.any { it.source == EvidenceSource.OBSERVED_BEHAVIOR })
  assertTrue(finding.evidence.any { it.source == EvidenceSource.ANDROID_EVIDENCE })

  // Corroborated behavior list must contain the matched connection
  assertEquals(1, bothSensorsResult.corroboratedBehaviors.size)
  val corroborated = bothSensorsResult.corroboratedBehaviors.first()
  assertEquals(2, corroborated.evidence.size)
 }

 @Test
 fun dnsPlusConnectPlusVpnDoesNotTripleCount() {
  val fullTripleInput = RuntimeRiskInput(
   sessionId = "s1",
   packageName = "com.example.app",
   observations = listOf(
    NetworkObservation.DnsResponse(now, 1, "example.com", listOf("104.20.23.154"), 53000),
    NetworkObservation.ConnectionOpened(now.plusMillis(20), 1L, NetworkObservation.Protocol.TCP, "104.20.23.154", 443),
   ),
   androidDns = listOf(
    AndroidDnsEvidence(201L, 1L, "com.example.app", now, now.plusMillis(50), "example.com", listOf("104.20.23.154"), 1),
   ),
   androidConnect = listOf(
    AndroidConnectEvidence(202L, 1L, "com.example.app", now.plusMillis(20), now.plusMillis(50), "104.20.23.154", 443),
   ),
   androidStatus = AndroidEvidenceStatus.READY,
  )
  val result = runtimeEngine.evaluate(fullTripleInput)

  // Score must remain 5 (network activity: 5, DNS: 0)
  assertEquals(5, result.score)
  assertEquals(RiskLevel.LOW, result.level)

  // Correlated DNS should detect the connection-to-DNS relation
  assertTrue(result.correlatedDns.isNotEmpty())
  val dnsCorrelation = result.correlatedDns.first()
  assertEquals("example.com", dnsCorrelation.hostname)
  assertEquals("104.20.23.154", dnsCorrelation.ipAddress)
  assertEquals(443, dnsCorrelation.port)
 }

 @Test
 fun combinedScoreDoesNotStackRedundantPointsForCorroboratedEvents() {
  val static = StaticRiskAssessment(
   score = 10,
   level = RiskLevel.LOW,
   findings = emptyList(),
   basedOnDeclaredCapabilities = listOf(DeclaredCapability("android.permission.READ_CONTACTS")),
   engineVersion = RISK_ENGINE_VERSION,
  )

  val input = RuntimeRiskInput(
   sessionId = "s1",
   packageName = "com.example.app",
   observations = listOf(
    NetworkObservation.DnsResponse(now, 1, "example.com", listOf("104.20.23.154"), 53000),
    NetworkObservation.ConnectionOpened(now.plusMillis(10), 1L, NetworkObservation.Protocol.TCP, "104.20.23.154", 443),
   ),
   androidConnect = listOf(
    AndroidConnectEvidence(101L, 1L, "com.example.app", now, now.plusMillis(10), "104.20.23.154", 443),
   ),
  )
  val runtime = runtimeEngine.evaluate(input)
  val combined = combinedEngine.evaluate(static, runtime, input)

  // 10 static + 5 runtime (network activity, NOT 10) + 15 combined (contacts + network) = 30
  assertEquals(30, combined.overallScore)
  assertEquals(RiskLevel.MODERATE, combined.overallLevel)
 }

 @Test
 fun distinctDestinationsUnifiesIpAcrossVpnAndDpm() {
  // Same 5 IPs observed across both VPN and DPM
  val ips = listOf("203.0.113.1", "203.0.113.2", "203.0.113.3", "203.0.113.4", "203.0.113.5")
  val input = RuntimeRiskInput(
   sessionId = "s1",
   packageName = "com.example.app",
   observations = ips.mapIndexed { idx, ip ->
    NetworkObservation.ConnectionOpened(now, idx.toLong(), NetworkObservation.Protocol.TCP, ip, 443)
   },
   androidConnect = ips.mapIndexed { idx, ip ->
    AndroidConnectEvidence(100L + idx, 1L, "com.example.app", now, now, ip, 443)
   },
  )
  val result = runtimeEngine.evaluate(input)
  // Distinct destinations should count exactly 5 unique IPs, not 10
  val finding = result.findings.firstOrNull { it.ruleId == RuleIds.RUNTIME_MANY_DISTINCT_DESTINATIONS }
  assertNotNull(finding)
  assertEquals(10, finding!!.scoreContribution)
  assertTrue(finding.explanation.contains("5 distinct"))
 }
}
