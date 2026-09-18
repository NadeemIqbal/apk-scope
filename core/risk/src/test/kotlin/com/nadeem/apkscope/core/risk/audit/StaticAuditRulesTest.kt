package com.nadeem.apkscope.core.risk.audit

import com.nadeem.apkscope.core.risk.Permissions
import com.nadeem.apkscope.core.risk.testInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every Security Audit v1 rule (docs/SECURITY_AUDIT_RULES.md) always returns a finding —
 * [AuditOutcome.CHECK_PASSED] when its trigger condition is not met, never null — the specific
 * behavior that distinguishes [AuditRule] from core:risk's `RiskRule` (which returns null on
 * no-trigger). A passed check must always report [Severity.INFO] — a rule finding nothing must never
 * carry a non-trivial severity.
 */
class StaticAuditRulesTest {

 @Test fun debuggableBuild_detectsFindingWhenDebuggable() {
  val finding = DebuggableBuildRule.evaluate(testInput(debuggable = true))
  assertEquals(AuditOutcome.FINDING_DETECTED, finding.outcome)
  assertEquals(Severity.HIGH, finding.severity)
  assertEquals(AuditRuleIds.DEBUGGABLE_BUILD, finding.ruleId)
 }
 @Test fun debuggableBuild_passesWhenNotDebuggable() {
  val finding = DebuggableBuildRule.evaluate(testInput(debuggable = false))
  assertEquals(AuditOutcome.CHECK_PASSED, finding.outcome)
  assertEquals(Severity.INFO, finding.severity)
 }

 @Test fun signatureIntegrity_detectsFindingWhenUnverified() {
  val finding = SignatureIntegrityRule.evaluate(testInput(signatureVerified = false))
  assertEquals(AuditOutcome.FINDING_DETECTED, finding.outcome)
  assertEquals(Severity.CRITICAL, finding.severity)
 }
 @Test fun signatureIntegrity_passesWhenVerified() {
  assertEquals(AuditOutcome.CHECK_PASSED, SignatureIntegrityRule.evaluate(testInput(signatureVerified = true)).outcome)
 }

 @Test fun exportedSurfaceRatio_needsReviewWhenMajorityExported() {
  val finding = ExportedSurfaceRatioRule.evaluate(testInput(totalComponentCount = 4, exportedComponentCount = 3))
  assertEquals(AuditOutcome.NEEDS_REVIEW, finding.outcome)
 }
 @Test fun exportedSurfaceRatio_passesWhenMinorityExported() {
  assertEquals(AuditOutcome.CHECK_PASSED, ExportedSurfaceRatioRule.evaluate(testInput(totalComponentCount = 4, exportedComponentCount = 1)).outcome)
 }
 @Test fun exportedSurfaceRatio_passesWhenNoComponents() {
  assertEquals(AuditOutcome.CHECK_PASSED, ExportedSurfaceRatioRule.evaluate(testInput(totalComponentCount = 0, exportedComponentCount = 0)).outcome)
 }

 @Test fun accessibilityOverlayTapjacking_detectsFindingWhenBothDeclared() {
  val finding = AccessibilityOverlayTapjackingRule.evaluate(
   testInput(permissions = listOf(Permissions.BIND_ACCESSIBILITY_SERVICE, Permissions.SYSTEM_ALERT_WINDOW))
  )
  assertEquals(AuditOutcome.FINDING_DETECTED, finding.outcome)
  assertEquals(Severity.HIGH, finding.severity)
 }
 @Test fun accessibilityOverlayTapjacking_passesWithOnlyAccessibility() {
  assertEquals(AuditOutcome.CHECK_PASSED, AccessibilityOverlayTapjackingRule.evaluate(testInput(permissions = listOf(Permissions.BIND_ACCESSIBILITY_SERVICE))).outcome)
 }
 @Test fun accessibilityOverlayTapjacking_passesWithOnlyOverlay() {
  assertEquals(AuditOutcome.CHECK_PASSED, AccessibilityOverlayTapjackingRule.evaluate(testInput(permissions = listOf(Permissions.SYSTEM_ALERT_WINDOW))).outcome)
 }
 @Test fun accessibilityOverlayTapjacking_passesWithNeither() {
  assertEquals(AuditOutcome.CHECK_PASSED, AccessibilityOverlayTapjackingRule.evaluate(testInput()).outcome)
 }

 @Test fun smsInternetExfilSurface_needsReviewWhenBothDeclared() {
  assertEquals(AuditOutcome.NEEDS_REVIEW, SmsInternetExfilSurfaceRule.evaluate(testInput(permissions = listOf(Permissions.READ_SMS, Permissions.INTERNET))).outcome)
 }
 @Test fun smsInternetExfilSurface_firesWithAnySmsPermission() {
  assertEquals(AuditOutcome.NEEDS_REVIEW, SmsInternetExfilSurfaceRule.evaluate(testInput(permissions = listOf(Permissions.SEND_SMS, Permissions.INTERNET))).outcome)
  assertEquals(AuditOutcome.NEEDS_REVIEW, SmsInternetExfilSurfaceRule.evaluate(testInput(permissions = listOf(Permissions.RECEIVE_SMS, Permissions.INTERNET))).outcome)
 }
 @Test fun smsInternetExfilSurface_passesWithOnlySms() {
  assertEquals(AuditOutcome.CHECK_PASSED, SmsInternetExfilSurfaceRule.evaluate(testInput(permissions = listOf(Permissions.READ_SMS))).outcome)
 }
 @Test fun smsInternetExfilSurface_passesWithOnlyInternet() {
  assertEquals(AuditOutcome.CHECK_PASSED, SmsInternetExfilSurfaceRule.evaluate(testInput(permissions = listOf(Permissions.INTERNET))).outcome)
 }

 @Test fun bootPersistenceNetwork_needsReviewWhenBothDeclared() {
  assertEquals(AuditOutcome.NEEDS_REVIEW, BootPersistenceNetworkRule.evaluate(testInput(permissions = listOf(Permissions.RECEIVE_BOOT_COMPLETED, Permissions.INTERNET))).outcome)
 }
 @Test fun bootPersistenceNetwork_passesWithOnlyBoot() {
  assertEquals(AuditOutcome.CHECK_PASSED, BootPersistenceNetworkRule.evaluate(testInput(permissions = listOf(Permissions.RECEIVE_BOOT_COMPLETED))).outcome)
 }
 @Test fun bootPersistenceNetwork_passesWithOnlyInternet() {
  assertEquals(AuditOutcome.CHECK_PASSED, BootPersistenceNetworkRule.evaluate(testInput(permissions = listOf(Permissions.INTERNET))).outcome)
 }

 @Test fun outdatedTargetSdk_needsReviewBelowThreshold() {
  assertEquals(AuditOutcome.NEEDS_REVIEW, OutdatedTargetSdkRule.evaluate(testInput(targetSdkVersion = 28)).outcome)
 }
 @Test fun outdatedTargetSdk_passesAtThreshold() {
  assertEquals(AuditOutcome.CHECK_PASSED, OutdatedTargetSdkRule.evaluate(testInput(targetSdkVersion = 29)).outcome)
 }
 @Test fun outdatedTargetSdk_passesAboveThreshold() {
  assertEquals(AuditOutcome.CHECK_PASSED, OutdatedTargetSdkRule.evaluate(testInput(targetSdkVersion = 34)).outcome)
 }

 @Test fun nativeCodeUnverified_notTestedWhenPresent() {
  val finding = NativeCodeUnverifiedRule.evaluate(testInput(nativeLibraryAbis = listOf("arm64-v8a")))
  assertEquals(AuditOutcome.NOT_TESTED, finding.outcome)
  assertEquals(Severity.INFO, finding.severity) // NOT_TESTED must never imply a finding's severity
 }
 @Test fun nativeCodeUnverified_passesWhenAbsent() {
  assertEquals(AuditOutcome.CHECK_PASSED, NativeCodeUnverifiedRule.evaluate(testInput(nativeLibraryAbis = emptyList())).outcome)
 }

 @Test fun cleartextTrafficEnabled_detectsFindingWhenTrue() {
  val finding = CleartextTrafficEnabledRule.evaluate(testInput(usesCleartextTraffic = true))
  assertEquals(AuditOutcome.FINDING_DETECTED, finding.outcome)
  assertEquals(Confidence.MEDIUM, finding.confidence) // declaration, not an observed transaction
 }
 @Test fun cleartextTrafficEnabled_passesWhenFalse() {
  assertEquals(AuditOutcome.CHECK_PASSED, CleartextTrafficEnabledRule.evaluate(testInput(usesCleartextTraffic = false)).outcome)
 }
 /** Phase 10.3 correction: a defaulted `usesCleartextTraffic=false` from a pre-Security-Audit row must never read as a confirmed CHECK_PASSED. */
 @Test fun cleartextTrafficEnabled_notTestedWhenStaticSecurityFieldsUnknown() {
  val finding = CleartextTrafficEnabledRule.evaluate(testInput(usesCleartextTraffic = false, staticSecurityFieldsKnown = false))
  assertEquals(AuditOutcome.NOT_TESTED, finding.outcome)
  assertEquals(Severity.INFO, finding.severity)
 }
 /** Same correction, the other direction: even when the defaulted value happens to be `true` (which would otherwise fire FINDING_DETECTED), an unknown field must still report NOT_TESTED, not a finding built on data that was never actually read. */
 @Test fun cleartextTrafficEnabled_notTestedEvenWhenDefaultedValueIsTrue() {
  val finding = CleartextTrafficEnabledRule.evaluate(testInput(usesCleartextTraffic = true, staticSecurityFieldsKnown = false))
  assertEquals(AuditOutcome.NOT_TESTED, finding.outcome)
 }

 @Test fun backupEnabled_needsReviewWhenTrue() {
  assertEquals(AuditOutcome.NEEDS_REVIEW, BackupEnabledRule.evaluate(testInput(allowBackup = true)).outcome)
 }
 @Test fun backupEnabled_passesWhenFalse() {
  assertEquals(AuditOutcome.CHECK_PASSED, BackupEnabledRule.evaluate(testInput(allowBackup = false)).outcome)
 }
 /** Phase 10.3 correction: same staticSecurityFieldsKnown gate as CleartextTrafficEnabledRule. */
 @Test fun backupEnabled_notTestedWhenStaticSecurityFieldsUnknown() {
  val finding = BackupEnabledRule.evaluate(testInput(allowBackup = false, staticSecurityFieldsKnown = false))
  assertEquals(AuditOutcome.NOT_TESTED, finding.outcome)
  assertEquals(Severity.INFO, finding.severity)
 }

 @Test fun everyRule_alwaysProvidesNonBlankRemediation() {
  // MS10-UI03: opening a finding must always show remediation text — never blank, even for a
  // clean CHECK_PASSED result (which states "no action needed" rather than omitting the field).
  StaticAuditRules.all.forEach { rule ->
   assertTrue("rule ${rule.id}'s PASS-case remediation was blank", rule.evaluate(testInput(allowBackup = false)).remediation.isNotBlank())
  }
 }

 @Test fun everyRule_stampsItsOwnIdOnItsFinding() {
  StaticAuditRules.all.forEach { rule ->
   val finding = rule.evaluate(testInput())
   assertTrue("rule ${rule.id} did not stamp its own id on its finding", finding.ruleId == rule.id)
  }
 }

 @Test fun networkSecurityConfig_passesWhenNotDeclared() {
  val finding = NetworkSecurityConfigReviewRule.evaluate(testInput(networkSecurityConfigPresent = null))
  assertEquals(AuditOutcome.CHECK_PASSED, finding.outcome)
  assertEquals(Severity.INFO, finding.severity)
 }
 @Test fun networkSecurityConfig_notTestedWhenPresentButUnavailable() {
  val finding = NetworkSecurityConfigReviewRule.evaluate(
   testInput(networkSecurityConfigPresent = true, networkSecurityConfigUnavailableReason = "unsupported resources.arsc variant")
  )
  assertEquals(AuditOutcome.NOT_TESTED, finding.outcome)
  assertTrue("detail must name the actual reason, not a generic message", finding.detail.contains("unsupported resources.arsc variant"))
 }
 @Test fun networkSecurityConfig_needsReviewWhenBaseConfigPermitsCleartext() {
  val finding = NetworkSecurityConfigReviewRule.evaluate(
   testInput(networkSecurityConfigPresent = true, networkSecurityConfigCleartextPermitted = true)
  )
  assertEquals(AuditOutcome.NEEDS_REVIEW, finding.outcome)
  assertEquals(Severity.LOW, finding.severity)
 }
 @Test fun networkSecurityConfig_needsReviewWhenDebugOverridesTrustUserCerts() {
  val finding = NetworkSecurityConfigReviewRule.evaluate(
   testInput(networkSecurityConfigPresent = true, networkSecurityConfigCleartextPermitted = false, networkSecurityConfigDebugOverridesTrustsUserCerts = true)
  )
  assertEquals(AuditOutcome.NEEDS_REVIEW, finding.outcome)
 }
 @Test fun networkSecurityConfig_passesWhenParsedAndCleanEvenWithPinSet() {
  // A pin-set is a positive signal, never itself a reason for NEEDS_REVIEW/FAIL — MS10-NET01's own
  // "absence of pinning is never reported as an automatic FAIL" applies with equal force here: its
  // *presence*, combined with no cleartext override and no debug-CA trust, must still pass cleanly.
  val finding = NetworkSecurityConfigReviewRule.evaluate(
   testInput(networkSecurityConfigPresent = true, networkSecurityConfigCleartextPermitted = false, networkSecurityConfigHasPinSet = true)
  )
  assertEquals(AuditOutcome.CHECK_PASSED, finding.outcome)
  assertTrue("a real positive signal (pin-set) should still be visible in the detail text", finding.detail.contains("pin-set"))
 }

 @Test fun everyPassedCheck_carriesInfoSeverity() {
  // A rule that found nothing must never report a non-trivial severity — severity is meaningful
  // only for FINDING_DETECTED/NEEDS_REVIEW, per AuditModels.kt's own contract.
  val cleanInput = testInput(allowBackup = false, usesCleartextTraffic = false, signatureVerified = true, targetSdkVersion = 34)
  StaticAuditRules.all.forEach { rule ->
   val finding = rule.evaluate(cleanInput)
   if (finding.outcome == AuditOutcome.CHECK_PASSED) {
    assertEquals("rule ${rule.id} passed but did not report INFO severity", Severity.INFO, finding.severity)
   }
  }
 }
}
