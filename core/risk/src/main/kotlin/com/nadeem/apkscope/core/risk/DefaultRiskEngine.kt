package com.nadeem.apkscope.core.risk

import com.nadeem.apkscope.core.model.ApkAnalysisInput
import com.nadeem.apkscope.core.model.DeclaredCapability
import com.nadeem.apkscope.core.model.StaticRiskAssessment
import com.nadeem.apkscope.core.model.riskLevelFor

/**
 * The full, ordered rule set this engine version evaluates (item 11/19) — the single source of
 * truth for "what rules exist"; both [DefaultRiskEngine] and every test in this module reference
 * this list rather than re-declaring it.
 */
object DefaultRiskRules {
 val all: List<RiskRule> = listOf(
  // Single declared-capability rules (item 9).
  AccessibilityServiceRule,
  OverlayPermissionRule,
  ReadSmsRule,
  ReceiveSmsRule,
  SendSmsRule,
  RequestInstallPackagesRule,
  BootReceiverRule,
  BackgroundLocationRule,
  QueryAllPackagesRule,
  // APK-structure rules (item 9/12/13/14).
  NativeLibrariesPresentRule,
  DebuggableApkRule,
  OldTargetSdkRule,
  SignatureVerificationFailedRule,
  ManyExportedComponentsRule,
  // Combination rules (item 10).
  AccessibilityOverlayCombinationRule,
  AccessibilityBootCombinationRule,
  SmsInternetCombinationRule,
  ContactsInternetCombinationRule,
  BackgroundLocationInternetCombinationRule,
 )
}

/**
 * The real, production static risk engine (item 5-7). Deterministic: the same [ApkAnalysisInput]
 * always evaluates to the same [StaticRiskAssessment] — no randomness, no wall-clock dependence, no
 * hidden mutable state (verified by `DefaultRiskEngineTest.evaluationIsDeterministic`).
 *
 * **Scoring policy** (item 6/10, documented per the checkpoint's explicit request — these weights
 * are this engine's own design, not copied from anywhere): every firing [RiskRule] contributes its
 * own flat `scoreContribution` (chosen per rule by its [com.nadeem.apkscope.core.model.RiskSeverity]:
 * roughly INFO=3, LOW=5, MEDIUM=10, HIGH=15-20). The total score is the **sum** of every finding's
 * contribution, clamped to `0..100` (item 7's "clamp the final score to 100", plus a symmetric
 * floor at 0 since a sum of non-negative contributions can never go negative but the clamp is kept
 * explicit rather than assumed). A combination rule's contribution is **additive on top of** its
 * component capabilities' own individual contributions — e.g. accessibility (+20) declared together
 * with overlay (+10) plus the accessibility+overlay combination (+20) sums to 50, not 20. This is
 * deliberate: a combination represents materially higher exposure than either capability in
 * isolation, and deserves its own explainable line item rather than a discount against facts that
 * already, independently, contributed to the score. It is not accidental double-counting — it is
 * the intended, documented policy asked for by item 10.
 *
 * [StaticRiskAssessment.basedOnDeclaredCapabilities] is derived directly from
 * [ApkAnalysisInput.requestedPermissions] (item 8: a [StaticRiskAssessment] may only be built from
 * [DeclaredCapability] facts) — never from any other evidence source.
 */
class DefaultRiskEngine(private val rules: List<RiskRule> = DefaultRiskRules.all) : RiskEngine {
 override fun evaluate(input: ApkAnalysisInput): StaticRiskAssessment {
  val findings = rules.mapNotNull { it.evaluate(input) }.sortedByDescending { it.scoreContribution }
  val rawScore = findings.sumOf { it.scoreContribution }
  val score = rawScore.coerceIn(0, 100)
  return StaticRiskAssessment(
   score = score,
   level = riskLevelFor(score),
   findings = findings,
   basedOnDeclaredCapabilities = input.requestedPermissions.map { DeclaredCapability(name = it) },
   engineVersion = RISK_ENGINE_VERSION,
  )
 }
}
