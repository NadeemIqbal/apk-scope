package com.nadeem.apkscope.core.risk

import com.nadeem.apkscope.core.model.AssessmentType
import com.nadeem.apkscope.core.model.CombinedRiskAssessment
import com.nadeem.apkscope.core.model.EvidenceReference
import com.nadeem.apkscope.core.model.EvidenceSource
import com.nadeem.apkscope.core.model.RiskFinding
import com.nadeem.apkscope.core.model.RiskSeverity
import com.nadeem.apkscope.core.model.RuntimeRiskAssessment
import com.nadeem.apkscope.core.model.RuntimeRiskInput
import com.nadeem.apkscope.core.model.StaticRiskAssessment
import com.nadeem.apkscope.core.model.riskLevelFor

/**
 * Evaluates combined risk rules joining static capabilities with runtime observations.
 */
fun interface CombinedRiskEngine {
 fun evaluate(
  staticAssessment: StaticRiskAssessment,
  runtimeAssessment: RuntimeRiskAssessment?,
  runtimeInput: RuntimeRiskInput?,
 ): CombinedRiskAssessment
}

/**
 * Real production combined risk engine (combined-v1).
 *
 * Joins declared capabilities with observed runtime behavior where defensible.
 * Never claims data was uploaded when only co-occurrence of capability and network activity is known.
 * Deterministic, explainable scoring with explicit double-count protection.
 */
class DefaultCombinedRiskEngine : CombinedRiskEngine {

 override fun evaluate(
  staticAssessment: StaticRiskAssessment,
  runtimeAssessment: RuntimeRiskAssessment?,
  runtimeInput: RuntimeRiskInput?,
 ): CombinedRiskAssessment {
  // If runtime is not run or unavailable, degrade cleanly to static-only
  if (runtimeAssessment == null) {
   return CombinedRiskAssessment(
    static = staticAssessment,
    runtime = null,
    overallScore = staticAssessment.score,
    overallLevel = staticAssessment.level,
    combinedFindings = emptyList(),
    engineVersion = COMBINED_ENGINE_VERSION,
    isRuntimeComplete = false,
   )
  }

  val combinedFindings = mutableListOf<RiskFinding>()
  val declaredPermissions = staticAssessment.basedOnDeclaredCapabilities.map { it.name }.toSet()
  val staticFindingRuleIds = staticAssessment.findings.map { it.ruleId }.toSet()

  // Check if runtime external network activity was observed
  val networkActivityFinding = runtimeAssessment.findings.firstOrNull { it.ruleId == RuleIds.RUNTIME_NETWORK_ACTIVITY }
  val hasNetworkActivity = networkActivityFinding != null

  // Check if runtime private network attempt occurred
  val privateAccessFinding = runtimeAssessment.findings.firstOrNull { it.ruleId == RuleIds.RUNTIME_POLICY_DENIED_DESTINATION }
  val hasPrivateAccessAttempt = privateAccessFinding != null

  val sessionId = runtimeInput?.sessionId ?: "session"

  if (hasNetworkActivity) {
   val networkRef = EvidenceReference(
    EvidenceSource.OBSERVED_BEHAVIOR,
    "runtime-network",
    "External network connections observed during sandbox session",
   )

   // 1. Contacts + network activity
   if ("android.permission.READ_CONTACTS" in declaredPermissions) {
    combinedFindings.add(
     RiskFinding(
      id = "${sessionId}_${RuleIds.COMBINED_CONTACTS_NETWORK}",
      ruleId = RuleIds.COMBINED_CONTACTS_NETWORK,
      severity = RiskSeverity.MEDIUM,
      title = "Contacts access capability with network activity",
      explanation = "The APK declares permission to read contacts (READ_CONTACTS) and made external network connections during this sandbox session.",
      scoreContribution = 15,
      evidence = listOf(
       EvidenceReference(EvidenceSource.DECLARED_CAPABILITY, "perm-read-contacts", "Declares android.permission.READ_CONTACTS"),
       networkRef,
      ),
      assessmentType = AssessmentType.COMBINED,
      ruleEngineVersion = COMBINED_ENGINE_VERSION,
     ),
    )
   }

   // 2. SMS + network activity
   val hasSms = "android.permission.READ_SMS" in declaredPermissions ||
    "android.permission.RECEIVE_SMS" in declaredPermissions ||
    "android.permission.SEND_SMS" in declaredPermissions
   if (hasSms) {
    val smsPerm = when {
     "android.permission.READ_SMS" in declaredPermissions -> "READ_SMS"
     "android.permission.RECEIVE_SMS" in declaredPermissions -> "RECEIVE_SMS"
     else -> "SEND_SMS"
    }
    combinedFindings.add(
     RiskFinding(
      id = "${sessionId}_${RuleIds.COMBINED_SMS_NETWORK}",
      ruleId = RuleIds.COMBINED_SMS_NETWORK,
      severity = RiskSeverity.HIGH,
      title = "SMS capability with network activity",
      explanation = "The APK declares SMS access permissions ($smsPerm) and made external network connections during this sandbox session.",
      scoreContribution = 20,
      evidence = listOf(
       EvidenceReference(EvidenceSource.DECLARED_CAPABILITY, "perm-sms", "Declares android.permission.$smsPerm"),
       networkRef,
      ),
      assessmentType = AssessmentType.COMBINED,
      ruleEngineVersion = COMBINED_ENGINE_VERSION,
     ),
    )
   }

   // 3. Location + network activity
   val hasLocation = "android.permission.ACCESS_FINE_LOCATION" in declaredPermissions ||
    "android.permission.ACCESS_COARSE_LOCATION" in declaredPermissions ||
    "android.permission.ACCESS_BACKGROUND_LOCATION" in declaredPermissions
   if (hasLocation) {
    combinedFindings.add(
     RiskFinding(
      id = "${sessionId}_${RuleIds.COMBINED_LOCATION_NETWORK}",
      ruleId = RuleIds.COMBINED_LOCATION_NETWORK,
      severity = RiskSeverity.MEDIUM,
      title = "Location capability with network activity",
      explanation = "The APK declares location access permissions and made external network connections during this sandbox session.",
      scoreContribution = 15,
      evidence = listOf(
       EvidenceReference(EvidenceSource.DECLARED_CAPABILITY, "perm-location", "Declares location access permission"),
       networkRef,
      ),
      assessmentType = AssessmentType.COMBINED,
      ruleEngineVersion = COMBINED_ENGINE_VERSION,
     ),
    )
   }

   // 4 & 5. Accessibility (+ Overlay) + network activity
   val hasAccessibility = "android.permission.BIND_ACCESSIBILITY_SERVICE" in declaredPermissions ||
    RuleIds.ACCESSIBILITY_SERVICE in staticFindingRuleIds
   val hasOverlay = "android.permission.SYSTEM_ALERT_WINDOW" in declaredPermissions ||
    RuleIds.OVERLAY_PERMISSION in staticFindingRuleIds

   if (hasAccessibility && hasOverlay) {
    combinedFindings.add(
     RiskFinding(
      id = "${sessionId}_${RuleIds.COMBINED_ACCESSIBILITY_OVERLAY_NETWORK}",
      ruleId = RuleIds.COMBINED_ACCESSIBILITY_OVERLAY_NETWORK,
      severity = RiskSeverity.HIGH,
      title = "Accessibility service and overlay capabilities with network activity",
      explanation = "The APK declares both an accessibility service and system alert window overlay permissions while exhibiting external network activity during the session.",
      scoreContribution = 35,
      evidence = listOf(
       EvidenceReference(EvidenceSource.DECLARED_CAPABILITY, "perm-accessibility", "Declares BIND_ACCESSIBILITY_SERVICE"),
       EvidenceReference(EvidenceSource.DECLARED_CAPABILITY, "perm-overlay", "Declares SYSTEM_ALERT_WINDOW"),
       networkRef,
      ),
      assessmentType = AssessmentType.COMBINED,
      ruleEngineVersion = COMBINED_ENGINE_VERSION,
     ),
    )
   } else if (hasAccessibility) {
    combinedFindings.add(
     RiskFinding(
      id = "${sessionId}_${RuleIds.COMBINED_ACCESSIBILITY_NETWORK}",
      ruleId = RuleIds.COMBINED_ACCESSIBILITY_NETWORK,
      severity = RiskSeverity.HIGH,
      title = "Accessibility service capability with network activity",
      explanation = "The APK declares an accessibility service capability and made external network connections during this sandbox session.",
      scoreContribution = 25,
      evidence = listOf(
       EvidenceReference(EvidenceSource.DECLARED_CAPABILITY, "perm-accessibility", "Declares BIND_ACCESSIBILITY_SERVICE"),
       networkRef,
      ),
      assessmentType = AssessmentType.COMBINED,
      ruleEngineVersion = COMBINED_ENGINE_VERSION,
     ),
    )
   }

   // 6. Boot persistence + network activity
   val hasBoot = "android.permission.RECEIVE_BOOT_COMPLETED" in declaredPermissions ||
    RuleIds.BOOT_RECEIVER in staticFindingRuleIds
   if (hasBoot) {
    combinedFindings.add(
     RiskFinding(
      id = "${sessionId}_${RuleIds.COMBINED_BOOT_PERSISTENCE_NETWORK}",
      ruleId = RuleIds.COMBINED_BOOT_PERSISTENCE_NETWORK,
      severity = RiskSeverity.MEDIUM,
      title = "Boot persistence capability with network activity",
      explanation = "The APK declares permission to receive device boot completion broadcasts (RECEIVE_BOOT_COMPLETED) and made external network connections during this sandbox session.",
      scoreContribution = 15,
      evidence = listOf(
       EvidenceReference(EvidenceSource.DECLARED_CAPABILITY, "perm-boot", "Declares RECEIVE_BOOT_COMPLETED"),
       networkRef,
      ),
      assessmentType = AssessmentType.COMBINED,
      ruleEngineVersion = COMBINED_ENGINE_VERSION,
     ),
    )
   }

   // 7. Install-package capability + network activity
   val hasInstallPackages = "android.permission.REQUEST_INSTALL_PACKAGES" in declaredPermissions ||
    "android.permission.INSTALL_PACKAGES" in declaredPermissions ||
    RuleIds.REQUEST_INSTALL_PACKAGES in staticFindingRuleIds
   if (hasInstallPackages) {
    combinedFindings.add(
     RiskFinding(
      id = "${sessionId}_${RuleIds.COMBINED_INSTALL_PACKAGES_NETWORK}",
      ruleId = RuleIds.COMBINED_INSTALL_PACKAGES_NETWORK,
      severity = RiskSeverity.HIGH,
      title = "Package installation capability with network activity",
      explanation = "The APK declares permission to request package installation and made external network connections during this sandbox session.",
      scoreContribution = 20,
      evidence = listOf(
       EvidenceReference(EvidenceSource.DECLARED_CAPABILITY, "perm-install-packages", "Declares REQUEST_INSTALL_PACKAGES"),
       networkRef,
      ),
      assessmentType = AssessmentType.COMBINED,
      ruleEngineVersion = COMBINED_ENGINE_VERSION,
     ),
    )
   }
  }

  // 8. Sensitive capabilities + private network access attempt
  if (hasPrivateAccessAttempt) {
   val sensitivePerms = listOf(
    "android.permission.READ_CONTACTS",
    "android.permission.READ_SMS",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.RECORD_AUDIO",
    "android.permission.CAMERA",
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
   ).filter { it in declaredPermissions }

   if (sensitivePerms.isNotEmpty()) {
    val permsStr = sensitivePerms.map { it.substringAfterLast(".") }.joinToString(", ")
    val privateRef = privateAccessFinding.evidence.firstOrNull() ?: EvidenceReference(
     EvidenceSource.OBSERVED_BEHAVIOR,
     "policy-denied",
     "Attempted connection to private network destination blocked by sandbox policy",
    )
    combinedFindings.add(
     RiskFinding(
      id = "${sessionId}_${RuleIds.COMBINED_SENSITIVE_CAPABILITY_PRIVATE_DESTINATION}",
      ruleId = RuleIds.COMBINED_SENSITIVE_CAPABILITY_PRIVATE_DESTINATION,
      severity = RiskSeverity.HIGH,
      title = "Sensitive capabilities with private network access attempt",
      explanation = "The APK declares sensitive capabilities ($permsStr) and attempted to connect to a private/local network destination (blocked by destination policy).",
      scoreContribution = 30,
      evidence = listOf(
       EvidenceReference(EvidenceSource.DECLARED_CAPABILITY, "sensitive-perms", "Declares sensitive capabilities: $permsStr"),
       privateRef,
      ),
      assessmentType = AssessmentType.COMBINED,
      ruleEngineVersion = COMBINED_ENGINE_VERSION,
     ),
    )
   }
  }

  // Deterministic sorting
  val sortedCombinedFindings = combinedFindings.sortedWith(
   compareByDescending<RiskFinding> { it.scoreContribution }
    .thenBy { it.ruleId }
    .thenBy { it.id }
  )

  // Deterministic overall score calculation
  val rawTotal = staticAssessment.score + runtimeAssessment.score + sortedCombinedFindings.sumOf { it.scoreContribution }
  val overallScore = rawTotal.coerceIn(0, 100)

  return CombinedRiskAssessment(
   static = staticAssessment,
   runtime = runtimeAssessment,
   overallScore = overallScore,
   overallLevel = riskLevelFor(overallScore),
   combinedFindings = sortedCombinedFindings,
   engineVersion = COMBINED_ENGINE_VERSION,
   isRuntimeComplete = true,
  )
 }
}
