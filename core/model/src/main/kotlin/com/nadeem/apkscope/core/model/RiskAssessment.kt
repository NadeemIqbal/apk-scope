package com.nadeem.apkscope.core.model

import java.time.Instant
import java.util.UUID

/**
 * Checkpoint 7: The interpretation and reporting layer.
 *
 * Risk score measures the presence and combination of security-relevant capabilities and
 * observed behavior. It is not a probability that the APK is malware.
 */
enum class RiskSeverity { INFO, LOW, MEDIUM, HIGH }

/**
 * The 0-100 score's coarse band:
 * 0-24 LOW, 25-49 MODERATE, 50-74 HIGH, 75-100 CRITICAL.
 */
enum class RiskLevel { LOW, MODERATE, HIGH, CRITICAL }

fun riskLevelFor(score: Int): RiskLevel = when {
 score <= 24 -> RiskLevel.LOW
 score <= 49 -> RiskLevel.MODERATE
 score <= 74 -> RiskLevel.HIGH
 else -> RiskLevel.CRITICAL
}

/**
 * Explicit provenance for evidence references. Never flatten or merge
 * DeclaredCapability, ObservedBehavior, and AndroidEvidence into an ambiguous type.
 */
enum class EvidenceSource {
 DECLARED_CAPABILITY,
 OBSERVED_BEHAVIOR,
 ANDROID_EVIDENCE,
}

data class EvidenceReference(
 val source: EvidenceSource,
 val evidenceId: String,
 val description: String,
) {
 /** Backward-compatibility constructor for plain-text static capability descriptions. */
 constructor(description: String) : this(
  source = EvidenceSource.DECLARED_CAPABILITY,
  evidenceId = "static",
  description = description,
 )
}

typealias RiskEvidence = EvidenceReference

enum class AssessmentType {
 STATIC,
 RUNTIME,
 COMBINED,
}

/**
 * One line item behind a risk score. Every score point traces back to exactly one finding.
 * Findings must identify exactly which evidence supports them.
 */
data class RiskFinding(
 val ruleId: String,
 val severity: RiskSeverity,
 val title: String,
 val explanation: String,
 val scoreContribution: Int,
 val evidence: List<EvidenceReference>,
 val assessmentType: AssessmentType = AssessmentType.STATIC,
 val ruleEngineVersion: String = "static-v1",
 val id: String = ruleId,
) {
 val description: String get() = explanation
}

/**
 * Corroborated behavior: independent evidence sources (e.g. VPN + DPM) corroborating the same
 * event rather than duplicating score.
 */
data class CorroboratedBehavior(
 val behaviorType: String,
 val description: String,
 val evidence: List<EvidenceReference>,
)

/**
 * DNS correlation: conservative timestamp/IP correlation between DNS queries and socket connections.
 * Explicitly marked inferred.
 */
data class CorrelatedDnsEntry(
 val hostname: String,
 val ipAddress: String,
 val port: Int,
 val dnsSource: EvidenceSource,
 val connectionSource: EvidenceSource,
 val inferredDescription: String,
)

data class ObservedBehaviorSummary(
 val sessionId: String,
 val connectionCount: Int,
 val dnsQueryCount: Int,
 val uniqueObservedDomains: Int,
 val uploadedBytes: Long,
 val downloadedBytes: Long,
 val blockedConnectionCount: Int,
 val failedConnectionCount: Int,
)

data class AppIdentity(
 val packageName: String,
 val appName: String?,
 val versionName: String?,
 val versionCode: Long,
 val sha256: String,
)

data class RuleVersions(
 val staticVersion: String,
 val runtimeVersion: String?,
 val combinedVersion: String?,
)

data class EvidenceCompleteness(
 val staticComplete: Boolean,
 val runtimeComplete: Boolean,
 val androidEvidenceStatus: AndroidEvidenceStatus,
)

/**
 * The real, current static risk assessment for one APK (static-v1).
 */
data class StaticRiskAssessment(
 val score: Int,
 val level: RiskLevel,
 val findings: List<RiskFinding>,
 val basedOnDeclaredCapabilities: List<DeclaredCapability>,
 val engineVersion: String,
)

/**
 * The runtime risk assessment for one sandbox session (runtime-v1).
 */
data class RuntimeRiskAssessment(
 val score: Int,
 val level: RiskLevel,
 val findings: List<RiskFinding>,
 val engineVersion: String = "runtime-v1",
 val corroboratedBehaviors: List<CorroboratedBehavior> = emptyList(),
 val correlatedDns: List<CorrelatedDnsEntry> = emptyList(),
)

/**
 * The combined risk assessment joining static capabilities and observed runtime facts (combined-v1).
 */
data class CombinedRiskAssessment(
 val static: StaticRiskAssessment,
 val runtime: RuntimeRiskAssessment?,
 val overallScore: Int,
 val overallLevel: RiskLevel,
 val combinedFindings: List<RiskFinding>,
 val engineVersion: String = "combined-v1",
 val isRuntimeComplete: Boolean = runtime != null,
)

/**
 * The durable, explainable final report domain model.
 */
data class ApkScopeReport(
 val reportId: String,
 val analysisId: String,
 val sessionId: String?,
 val generatedAt: Instant,
 val appIdentity: AppIdentity,
 val staticAssessment: StaticRiskAssessment,
 val runtimeAssessment: RuntimeRiskAssessment?,
 val combinedAssessment: CombinedRiskAssessment?,
 val declaredCapabilities: List<DeclaredCapability>,
 val observedBehaviorSummary: ObservedBehaviorSummary?,
 val androidEvidenceSummary: AndroidEvidenceSummary?,
 val evidenceCompleteness: EvidenceCompleteness,
 val ruleVersions: RuleVersions,
) {
 val overallScore: Int get() = combinedAssessment?.overallScore ?: staticAssessment.score
 val overallLevel: RiskLevel get() = combinedAssessment?.overallLevel ?: staticAssessment.level
}

/**
 * Normalized input representation for evaluating runtime risk rules (runtime-v1).
 */
data class RuntimeRiskInput(
 val sessionId: String,
 val packageName: String,
 val observations: List<NetworkObservation> = emptyList(),
 val androidDns: List<AndroidDnsEvidence> = emptyList(),
 val androidConnect: List<AndroidConnectEvidence> = emptyList(),
 val androidStatus: AndroidEvidenceStatus = AndroidEvidenceStatus.NOT_AVAILABLE,
 val summary: ObservedBehaviorSummary? = null,
)

