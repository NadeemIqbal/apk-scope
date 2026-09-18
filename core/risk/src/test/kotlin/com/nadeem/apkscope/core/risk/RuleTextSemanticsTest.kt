package com.nadeem.apkscope.core.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 22: a focused content test, not an NLP system — static findings must never read like a
 * runtime-behavior claim. Evaluates every rule against one input engineered to trigger all of them
 * (every relevant permission + debuggable + an old target SDK + a failed signature + many exported
 * components), then inspects every produced finding's title/explanation.
 */
class RuleTextSemanticsTest {
 private val everythingTriggersInput = testInput(
  permissions = listOf(
   Permissions.CAMERA, Permissions.BIND_ACCESSIBILITY_SERVICE, Permissions.SYSTEM_ALERT_WINDOW,
   Permissions.READ_SMS, Permissions.RECEIVE_SMS, Permissions.SEND_SMS,
   Permissions.REQUEST_INSTALL_PACKAGES, Permissions.RECEIVE_BOOT_COMPLETED,
   Permissions.ACCESS_BACKGROUND_LOCATION, Permissions.QUERY_ALL_PACKAGES,
   Permissions.INTERNET, Permissions.READ_CONTACTS,
  ),
  debuggable = true,
  targetSdkVersion = OLD_TARGET_SDK_THRESHOLD - 1,
  nativeLibraryAbis = listOf("arm64-v8a"),
  totalComponentCount = 10,
  exportedComponentCount = MANY_EXPORTED_COMPONENTS_THRESHOLD,
  signatureVerified = false,
 )

 private val bannedWords = listOf("accessed", "uploaded", "recorded", "tracked", "stole", "stolen")

 @Test fun allRulesFireAgainstTheEverythingInput() {
  val findings = DefaultRiskRules.all.mapNotNull { it.evaluate(everythingTriggersInput) }
  assertEquals("every rule should have fired against a fixture engineered to trigger all of them", DefaultRiskRules.all.size, findings.size)
 }

 @Test fun noFindingTextMakesAnUnsupportedRuntimeClaim() {
  val findings = DefaultRiskRules.all.mapNotNull { it.evaluate(everythingTriggersInput) }
  for (finding in findings) {
   val text = (finding.title + " " + finding.explanation).lowercase()
   for (banned in bannedWords) {
    assertFalse("finding ${finding.ruleId} title/explanation must not contain '$banned': $text", text.contains(banned))
   }
  }
 }

 @Test fun capabilityBasedFindingsUseDeclaredOrRequestsLanguage() {
  // Every single-capability and combination rule is about a manifest declaration — its text
  // should say so explicitly. The two structural rules that are NOT about a declared capability
  // (signature verification, debuggable) are exempted: they describe a fact about the APK file
  // itself, not a declared capability, so item 8's vocabulary list doesn't apply to them the
  // same way.
  val exempt = setOf(RuleIds.SIGNATURE_VERIFICATION_FAILED, RuleIds.DEBUGGABLE_APK)
  val findings = DefaultRiskRules.all.filter { it.id !in exempt }.mapNotNull { it.evaluate(everythingTriggersInput) }
  for (finding in findings) {
   val text = (finding.title + " " + finding.explanation).lowercase()
   assertTrue("finding ${finding.ruleId} should describe a declared/requested/exposed capability: $text", text.contains("declar") || text.contains("request") || text.contains("expose") || text.contains("target") || text.contains("contain"))
  }
 }
}
