package com.nadeem.apkscope.core.risk.audit

import com.nadeem.apkscope.core.model.ApkAnalysisInput

/**
 * One full audit run's result — every rule in [StaticAuditRules.all] evaluated once, in order,
 * against the same [ApkAnalysisInput]. [engineVersion] pins which catalog produced this report (see
 * [SECURITY_AUDIT_ENGINE_VERSION]) so a persisted report can never be silently reinterpreted under a
 * later catalog's rule set.
 */
data class SecurityAuditReport(
 val engineVersion: String,
 val findings: List<AuditFinding>,
) {
 fun countOf(outcome: AuditOutcome): Int = findings.count { it.outcome == outcome }
 val findingCount: Int get() = countOf(AuditOutcome.FINDING_DETECTED)
 val passCount: Int get() = countOf(AuditOutcome.CHECK_PASSED)
 val needsReviewCount: Int get() = countOf(AuditOutcome.NEEDS_REVIEW)
 val notTestedCount: Int get() = countOf(AuditOutcome.NOT_TESTED)
 val notApplicableCount: Int get() = countOf(AuditOutcome.NOT_APPLICABLE)
 val collectionFailedCount: Int get() = countOf(AuditOutcome.COLLECTION_FAILED)
}

/**
 * The production boundary for static security audits (Milestone 10, Phase 10.1) — nothing outside
 * this evaluates the static audit catalog. Guided-session (Phase 10.2) and evidence-based-reporting
 * (Phase 10.3) engines are separate, later additions; this one only ever sees declared/structural
 * facts, never a runtime observation (mirrors `core:risk`'s own static/runtime separation — see
 * docs/SUPERVISOR_CONTEXT.md item 18).
 */
fun interface SecurityAuditEngine {
 fun audit(input: ApkAnalysisInput): SecurityAuditReport
}

class DefaultSecurityAuditEngine(
 private val rules: List<AuditRule> = StaticAuditRules.all,
) : SecurityAuditEngine {
 override fun audit(input: ApkAnalysisInput): SecurityAuditReport = SecurityAuditReport(
  engineVersion = SECURITY_AUDIT_ENGINE_VERSION,
  findings = rules.map { it.evaluate(input) },
 )
}
