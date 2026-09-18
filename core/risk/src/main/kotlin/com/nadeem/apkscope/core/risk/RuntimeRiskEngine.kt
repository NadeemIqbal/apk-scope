package com.nadeem.apkscope.core.risk

import com.nadeem.apkscope.core.model.AssessmentType
import com.nadeem.apkscope.core.model.CorroboratedBehavior
import com.nadeem.apkscope.core.model.CorrelatedDnsEntry
import com.nadeem.apkscope.core.model.EvidenceReference
import com.nadeem.apkscope.core.model.EvidenceSource
import com.nadeem.apkscope.core.model.NetworkObservation
import com.nadeem.apkscope.core.model.RiskFinding
import com.nadeem.apkscope.core.model.RiskLevel
import com.nadeem.apkscope.core.model.RiskSeverity
import com.nadeem.apkscope.core.model.RuntimeRiskAssessment
import com.nadeem.apkscope.core.model.RuntimeRiskInput
import com.nadeem.apkscope.core.model.riskLevelFor

/**
 * Evaluates runtime risk rules on observed behavior and Android-recorded evidence.
 */
fun interface RuntimeRiskEngine {
 fun evaluate(input: RuntimeRiskInput): RuntimeRiskAssessment
}

/**
 * Real production runtime risk engine (runtime-v1).
 *
 * Deterministic: the same [RuntimeRiskInput] always evaluates to the exact same findings and score.
 * Corroboration is not duplication: overlapping VPN and DPM evidence attach to the same finding
 * rather than double-counting score.
 */
class DefaultRuntimeRiskEngine : RuntimeRiskEngine {

 override fun evaluate(input: RuntimeRiskInput): RuntimeRiskAssessment {
  val findings = mutableListOf<RiskFinding>()
  val corroboratedBehaviors = mutableListOf<CorroboratedBehavior>()
  val correlatedDns = mutableListOf<CorrelatedDnsEntry>()

  // 1. Gather all connections and check external network activity
  val vpnConnectionsOpened = input.observations.filterIsInstance<NetworkObservation.ConnectionOpened>()
  val vpnConnectionsClosed = input.observations.filterIsInstance<NetworkObservation.ConnectionClosed>()
  val vpnPolicyDenied = input.observations.filterIsInstance<NetworkObservation.ConnectionFailed>()
   .filter { it.reason == NetworkObservation.FailureReason.POLICY_DENIED }
  val vpnDnsQueries = input.observations.filterIsInstance<NetworkObservation.DnsQuery>()
  val vpnDnsResponses = input.observations.filterIsInstance<NetworkObservation.DnsResponse>()

  val dpmConnectEvents = input.androidConnect
  val dpmDnsEvents = input.androidDns

  // Corroboration: Match VPN connection and DPM ConnectEvent by IP & port
  val allTargetIps = mutableSetOf<String>()
  for (open in vpnConnectionsOpened) {
   open.destinationIp.let { allTargetIps.add(it) }
   val matchingDpm = dpmConnectEvents.firstOrNull { it.destinationAddress == open.destinationIp && it.destinationPort == open.destinationPort }
   if (matchingDpm != null) {
    corroboratedBehaviors.add(
     CorroboratedBehavior(
      behaviorType = "CORROBORATED_CONNECTION",
      description = "Connection to ${open.destinationIp}:${open.destinationPort} observed by VPN and independently recorded by Android DPM",
      evidence = listOf(
       EvidenceReference(EvidenceSource.OBSERVED_BEHAVIOR, "vpn-conn-${open.connectionId}", "VPN observed TCP connection to ${open.destinationIp}:${open.destinationPort}"),
       EvidenceReference(EvidenceSource.ANDROID_EVIDENCE, "dpm-event-${matchingDpm.eventId}", "Android DPM ConnectEvent to ${matchingDpm.destinationAddress}:${matchingDpm.destinationPort} for ${matchingDpm.packageName}"),
      ),
     ),
    )
   }
  }
  for (dpm in dpmConnectEvents) {
   allTargetIps.add(dpm.destinationAddress)
  }

  // DNS Correlation: Match DPM / VPN DNS with connection IPs
  val dnsHostToIps = mutableMapOf<String, MutableSet<String>>()
  for (dns in dpmDnsEvents) {
   dnsHostToIps.getOrPut(dns.hostname) { mutableSetOf() }.addAll(dns.resolvedAddresses)
  }
  for (dns in vpnDnsResponses) {
   dns.hostname?.let { h ->
    dnsHostToIps.getOrPut(h) { mutableSetOf() }.addAll(dns.resolvedAddresses)
   }
  }

  for ((host, ips) in dnsHostToIps) {
   for (ip in ips) {
    val matchedVpn = vpnConnectionsOpened.firstOrNull { it.destinationIp == ip }
    val matchedDpm = dpmConnectEvents.firstOrNull { it.destinationAddress == ip }
    if (matchedVpn != null || matchedDpm != null) {
     val port = matchedVpn?.destinationPort ?: matchedDpm!!.destinationPort
     correlatedDns.add(
      CorrelatedDnsEntry(
       hostname = host,
       ipAddress = ip,
       port = port,
       dnsSource = if (dpmDnsEvents.any { it.hostname == host }) EvidenceSource.ANDROID_EVIDENCE else EvidenceSource.OBSERVED_BEHAVIOR,
       connectionSource = if (matchedVpn != null) EvidenceSource.OBSERVED_BEHAVIOR else EvidenceSource.ANDROID_EVIDENCE,
       inferredDescription = "Inferred correlation: $host resolved to $ip; connection observed to $ip:$port",
      ),
     )
    }
   }
  }

  // Check if external connections were made
  val hasConnections = vpnConnectionsOpened.isNotEmpty() || vpnConnectionsClosed.isNotEmpty() || dpmConnectEvents.isNotEmpty()
  if (hasConnections) {
   val evidenceList = mutableListOf<EvidenceReference>()
   if (vpnConnectionsOpened.isNotEmpty()) {
    val first = vpnConnectionsOpened.first()
    evidenceList.add(
     EvidenceReference(
      EvidenceSource.OBSERVED_BEHAVIOR,
      "vpn-conn-opened",
      "VPN observed ${vpnConnectionsOpened.size} network connection(s); first to ${first.destinationIp}:${first.destinationPort}",
     ),
    )
   }
   if (dpmConnectEvents.isNotEmpty()) {
    val first = dpmConnectEvents.first()
    evidenceList.add(
     EvidenceReference(
      EvidenceSource.ANDROID_EVIDENCE,
      "dpm-connect",
      "Android DPM recorded ${dpmConnectEvents.size} socket connection(s) attributed to ${input.packageName}; first to ${first.destinationAddress}:${first.destinationPort}",
     ),
    )
   }
   findings.add(
    RiskFinding(
     id = "${input.sessionId}_${RuleIds.RUNTIME_NETWORK_ACTIVITY}",
     ruleId = RuleIds.RUNTIME_NETWORK_ACTIVITY,
     severity = RiskSeverity.LOW,
     title = "External network activity observed",
     explanation = "The application established external network connections during this sandbox session.",
     scoreContribution = 5,
     evidence = evidenceList,
     assessmentType = AssessmentType.RUNTIME,
     ruleEngineVersion = RUNTIME_ENGINE_VERSION,
    ),
   )
  }

  // Check direct raw-IP connection: connection destination IP that does not appear in any DNS resolutions
  val allResolvedIps = dnsHostToIps.values.flatten().toSet()
  val rawIpConnections = vpnConnectionsOpened.filter { conn ->
   val ip = conn.destinationIp
   !isPrivateIp(ip) && ip !in allResolvedIps && conn.destinationPort != 53
  }
  if (rawIpConnections.isNotEmpty()) {
   val sample = rawIpConnections.first()
   findings.add(
    RiskFinding(
     id = "${input.sessionId}_${RuleIds.RUNTIME_RAW_IP_CONNECTION}",
     ruleId = RuleIds.RUNTIME_RAW_IP_CONNECTION,
     severity = RiskSeverity.MEDIUM,
     title = "Direct raw-IP connection observed",
     explanation = "The application connected directly to ${sample.destinationIp}:${sample.destinationPort}. No hostname was associated with this observation.",
     scoreContribution = 10,
     evidence = listOf(
      EvidenceReference(
       EvidenceSource.OBSERVED_BEHAVIOR,
       "vpn-raw-ip-${sample.connectionId}",
       "VPN observed direct connection to ${sample.destinationIp}:${sample.destinationPort} without prior DNS resolution",
      ),
     ),
     assessmentType = AssessmentType.RUNTIME,
     ruleEngineVersion = RUNTIME_ENGINE_VERSION,
    ),
   )
  }

  // Check private / local destination attempt (RFC1918, link-local, loopback, metadata)
  val dpmPrivateAttempts = dpmConnectEvents.filter { isPrivateIp(it.destinationAddress) }
  if (vpnPolicyDenied.isNotEmpty() || dpmPrivateAttempts.isNotEmpty()) {
   val deniedEvidence = mutableListOf<EvidenceReference>()
   val sampleDest = if (vpnPolicyDenied.isNotEmpty()) {
    val first = vpnPolicyDenied.first()
    deniedEvidence.add(
     EvidenceReference(
      EvidenceSource.OBSERVED_BEHAVIOR,
      "vpn-policy-denied",
      "VPN destination policy blocked connection to ${first.destinationIp}:${first.destinationPort} (${first.detail})",
     ),
    )
    "${first.destinationIp}:${first.destinationPort}"
   } else {
    val first = dpmPrivateAttempts.first()
    "${first.destinationAddress}:${first.destinationPort}"
   }
   if (dpmPrivateAttempts.isNotEmpty()) {
    val first = dpmPrivateAttempts.first()
    deniedEvidence.add(
     EvidenceReference(
      EvidenceSource.ANDROID_EVIDENCE,
      "dpm-private-connect",
      "Android DPM recorded socket attempt to private destination ${first.destinationAddress}:${first.destinationPort}",
     ),
    )
   }

   findings.add(
    RiskFinding(
     id = "${input.sessionId}_${RuleIds.RUNTIME_POLICY_DENIED_DESTINATION}",
     ruleId = RuleIds.RUNTIME_POLICY_DENIED_DESTINATION,
     severity = RiskSeverity.HIGH,
     title = "Private network access attempted",
     explanation = "The application attempted to connect to $sampleDest. APK Scope blocked the connection under its destination policy.",
     scoreContribution = 20,
     evidence = deniedEvidence,
     assessmentType = AssessmentType.RUNTIME,
     ruleEngineVersion = RUNTIME_ENGINE_VERSION,
    ),
   )
  }

  // Check high outbound data volume (> 1 MB = 1,048,576 bytes)
  val totalUploadedBytes = input.summary?.uploadedBytes
   ?: vpnConnectionsClosed.sumOf { it.uploadedBytes }
  if (totalUploadedBytes > 1024 * 1024) {
   val mb = "%.2f".format(totalUploadedBytes / (1024.0 * 1024.0))
   findings.add(
    RiskFinding(
     id = "${input.sessionId}_${RuleIds.RUNTIME_HIGH_OUTBOUND_DATA}",
     ruleId = RuleIds.RUNTIME_HIGH_OUTBOUND_DATA,
     severity = RiskSeverity.MEDIUM,
     title = "Large outbound transfer observed",
     explanation = "The application uploaded $mb MB during the sandbox session. High outbound data volume may warrant review.",
     scoreContribution = 15,
     evidence = listOf(
      EvidenceReference(
       EvidenceSource.OBSERVED_BEHAVIOR,
       "vpn-uploaded-bytes",
       "Total uploaded data: $totalUploadedBytes bytes ($mb MB)",
      ),
     ),
     assessmentType = AssessmentType.RUNTIME,
     ruleEngineVersion = RUNTIME_ENGINE_VERSION,
    ),
   )
  }

  // Check distinct external destinations (>= 5 distinct external IP addresses)
  val externalDistinctIps = allTargetIps.filter { !isPrivateIp(it) && it != "127.0.0.1" }.toSet()
  if (externalDistinctIps.size >= 5) {
   val sampleIps = externalDistinctIps.take(5).joinToString(", ")
   findings.add(
    RiskFinding(
     id = "${input.sessionId}_${RuleIds.RUNTIME_MANY_DISTINCT_DESTINATIONS}",
     ruleId = RuleIds.RUNTIME_MANY_DISTINCT_DESTINATIONS,
     severity = RiskSeverity.MEDIUM,
     title = "Multiple distinct external destinations contacted",
     explanation = "The application contacted ${externalDistinctIps.size} distinct external destinations during the session ($sampleIps...).",
     scoreContribution = 10,
     evidence = listOf(
      EvidenceReference(
       EvidenceSource.OBSERVED_BEHAVIOR,
       "vpn-distinct-destinations",
       "Contacted ${externalDistinctIps.size} distinct external destinations: $sampleIps",
      ),
     ),
     assessmentType = AssessmentType.RUNTIME,
     ruleEngineVersion = RUNTIME_ENGINE_VERSION,
    ),
   )
  }

  // DNS activity: informational finding (score 0)
  val allUniqueDomains = dnsHostToIps.keys
  if (allUniqueDomains.isNotEmpty() || vpnDnsQueries.isNotEmpty()) {
   val domainCount = if (allUniqueDomains.isNotEmpty()) allUniqueDomains.size else vpnDnsQueries.map { it.hostname }.distinct().size
   val sampleDomains = (allUniqueDomains.ifEmpty { vpnDnsQueries.map { it.hostname }.distinct() }).take(3).joinToString(", ")
   val dnsEvidence = mutableListOf<EvidenceReference>()
   if (vpnDnsQueries.isNotEmpty()) {
    dnsEvidence.add(
     EvidenceReference(
      EvidenceSource.OBSERVED_BEHAVIOR,
      "vpn-dns-queries",
      "VPN directly observed ${vpnDnsQueries.size} DNS queries ($sampleDomains)",
     ),
    )
   }
   if (dpmDnsEvents.isNotEmpty()) {
    dnsEvidence.add(
     EvidenceReference(
      EvidenceSource.ANDROID_EVIDENCE,
      "dpm-dns-events",
      "Android DPM recorded ${dpmDnsEvents.size} DNS resolution events for ${input.packageName}",
     ),
    )
   }
   findings.add(
    RiskFinding(
     id = "${input.sessionId}_${RuleIds.RUNTIME_DNS_ACTIVITY}",
     ruleId = RuleIds.RUNTIME_DNS_ACTIVITY,
     severity = RiskSeverity.INFO,
     title = "DNS activity observed",
     explanation = "The application performed DNS lookups for $domainCount unique domains ($sampleDomains).",
     scoreContribution = 0,
     evidence = dnsEvidence,
     assessmentType = AssessmentType.RUNTIME,
     ruleEngineVersion = RUNTIME_ENGINE_VERSION,
    ),
   )
  }

  // Deterministic sorting: score contribution descending, then ruleId
  val sortedFindings = findings.sortedWith(
   compareByDescending<RiskFinding> { it.scoreContribution }
    .thenBy { it.ruleId }
    .thenBy { it.id }
  )
  val rawScore = sortedFindings.sumOf { it.scoreContribution }
  val score = rawScore.coerceIn(0, 100)

  return RuntimeRiskAssessment(
   score = score,
   level = riskLevelFor(score),
   findings = sortedFindings,
   engineVersion = RUNTIME_ENGINE_VERSION,
   corroboratedBehaviors = corroboratedBehaviors,
   correlatedDns = correlatedDns,
  )
 }

 private fun isPrivateIp(ip: String): Boolean {
  val clean = ip.trim()
  if (clean == "localhost" || clean.startsWith("127.")) return true
  if (clean.startsWith("10.")) return true
  if (clean.startsWith("192.168.")) return true
  if (clean.startsWith("169.254.")) return true // link-local & metadata
  if (clean.startsWith("172.")) {
   val parts = clean.split(".")
   if (parts.size >= 2) {
    val second = parts[1].toIntOrNull()
    if (second != null && second in 16..31) return true
   }
  }
  if (clean.startsWith("fc00:") || clean.startsWith("fe80:") || clean == "::1") return true
  return false
 }
}
