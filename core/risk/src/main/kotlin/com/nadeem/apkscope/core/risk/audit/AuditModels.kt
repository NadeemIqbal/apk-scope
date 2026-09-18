package com.nadeem.apkscope.core.risk.audit

/**
 * Security Audit's rule catalog (Milestone 10, Phase 10.1) is deliberately independent of the
 * additive static-v1/runtime-v1/combined-v1 risk score (see docs/SECURITY_AUDIT.md) — every audit
 * rule produces an explicit, individually reportable outcome rather than contributing a numeric
 * weight, so an individual check can be cited and tracked on its own without a score's meaning
 * shifting as unrelated rules change.
 *
 * This vocabulary — not a simple pass/fail/warn — is the one specified for the full Security Audit
 * capability: a rule's *outcome* (did it find something, or could it not even run) is a distinct
 * question from its [Severity] (how bad, if found) and its [Confidence] (how sure). Conflating them
 * (as an earlier draft of this catalog did with a 3-value PASS/WARN/FAIL enum) loses the difference
 * between "this app is fine" and "this catalog couldn't check that."
 */
enum class AuditOutcome {
 /** The rule's trigger condition was met — something the catalog considers worth surfacing was found. */
 FINDING_DETECTED,
 /** The rule's trigger condition was evaluated and did not fire, within this rule's actual scope. A real, reportable outcome — not the absence of a check. */
 CHECK_PASSED,
 /** The rule's condition is ambiguous or borderline enough that a human should look, rather than the catalog asserting a clean or a flagged result on its own. */
 NEEDS_REVIEW,
 /** This rule was not evaluated for this analysis — e.g. it requires guided-session evidence that does not exist yet. Distinct from [NOT_APPLICABLE]: this rule *could* apply, it just was not run. */
 NOT_TESTED,
 /** This rule's precondition does not hold for this specific APK — the check is not meaningful here, not merely unrun. */
 NOT_APPLICABLE,
 /** The rule attempted to evaluate but could not obtain reliable input (a parse failure, a truncated artifact, a bounds limit reached) — distinct from a clean pass; reported so a missing finding is never mistaken for "nothing wrong." */
 COLLECTION_FAILED,
}

/** How severe a [FINDING_DETECTED] or [NEEDS_REVIEW] outcome is, if true. Meaningless (always [INFO]) for [CHECK_PASSED]/[NOT_TESTED]/[NOT_APPLICABLE]/[COLLECTION_FAILED] — a rule must not report a non-trivial severity for an outcome that found nothing. */
enum class Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }

/** How sure the rule is about its own outcome — independent of severity. A CRITICAL finding reported at LOW confidence (e.g. a heuristic secret-candidate match) must be presented as less certain than a HIGH-confidence one, even though the two might carry the same severity if confirmed. */
enum class Confidence { HIGH, MEDIUM, LOW }

/**
 * One evaluated audit rule's result. [outcome], [severity], and [confidence] are three independent
 * axes — see their own doc comments. Every [AuditRule] always returns a finding, even when the
 * outcome is [AuditOutcome.CHECK_PASSED] or [AuditOutcome.NOT_APPLICABLE], so a missing finding is
 * never mistaken for "not checked" (see docs/SECURITY_AUDIT_VERIFICATION.md's status vocabulary).
 */
data class AuditFinding(
 val ruleId: String,
 val outcome: AuditOutcome,
 val severity: Severity,
 val confidence: Confidence,
 val title: String,
 val detail: String,
 /** Actionable remediation guidance for this specific finding — always non-blank, even for a
  * [AuditOutcome.CHECK_PASSED] outcome (states there is nothing to remediate), so a UI never has to
  * special-case a missing value. See docs/SECURITY_AUDIT_RULES.md's dossier for the source each
  * rule's remediation text is drawn from. */
 val remediation: String,
)

/**
 * One independently testable, versioned audit rule. [id] is stable — see [AuditRuleIds] for the
 * full v1 catalog and docs/SECURITY_AUDIT_RULES.md for the human-readable catalog these mirror
 * exactly. Unlike core:risk's `RiskRule` (which returns `null` when its trigger condition is not
 * met), an [AuditRule] always returns a finding — [AuditOutcome.CHECK_PASSED] is a real, reportable
 * audit outcome, not the absence of one.
 */
interface AuditRule {
 val id: String
 fun evaluate(input: com.nadeem.apkscope.core.model.ApkAnalysisInput): AuditFinding
}

/**
 * Identifies which rule set produced a [SecurityAuditReport] — bump when the static rule catalog
 * changes so a persisted report can never be silently reinterpreted as if a different catalog
 * produced it. Mirrors `core:risk`'s own `RISK_ENGINE_VERSION` convention.
 */
const val SECURITY_AUDIT_ENGINE_VERSION = "security-audit-v1"
