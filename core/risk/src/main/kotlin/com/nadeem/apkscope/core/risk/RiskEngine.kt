package com.nadeem.apkscope.core.risk

import com.nadeem.apkscope.core.model.ApkAnalysisInput
import com.nadeem.apkscope.core.model.RiskFinding
import com.nadeem.apkscope.core.model.StaticRiskAssessment

/** The production boundary (item 5) — nothing outside `core:risk` computes a risk score or a `RiskFinding`. Compose/ViewModels only ever read an already-computed [StaticRiskAssessment]. */
fun interface RiskEngine {
 fun evaluate(input: ApkAnalysisInput): StaticRiskAssessment
}

/**
 * One independently testable scoring rule. [id] is stable (item 11) — used for persistence,
 * versioning, and test identification, and must never change once a rule ships (rename the rule's
 * [RiskRule.evaluate]-produced [RiskFinding.title]/explanation instead if wording needs to change).
 * Returns `null` when the rule's trigger condition is not met — a rule that cannot determine its
 * input reliably must not fire a finding with fabricated/default evidence (item 9).
 */
interface RiskRule {
 val id: String
 fun evaluate(input: ApkAnalysisInput): RiskFinding?
}

/** Identifies which rule set produced a [StaticRiskAssessment] (item 19) — bump when [DefaultRiskRules.all] changes so a persisted assessment can never be silently reinterpreted as if a different rule set produced it. */
const val RISK_ENGINE_VERSION = "static-v1"
