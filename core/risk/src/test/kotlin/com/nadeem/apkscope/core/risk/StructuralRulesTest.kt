package com.nadeem.apkscope.core.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Positive/negative/boundary coverage for the APK-structure rules (item 20). */
class StructuralRulesTest {

 @Test fun nativeLibraries_firesWhenPresent() {
  val finding = NativeLibrariesPresentRule.evaluate(testInput(nativeLibraryAbis = listOf("arm64-v8a")))
  assertEquals(RuleIds.NATIVE_LIBRARIES_PRESENT, finding?.ruleId)
  assertEquals(3, finding?.scoreContribution)
 }
 @Test fun nativeLibraries_doesNotFireWhenAbsent() {
  assertNull(NativeLibrariesPresentRule.evaluate(testInput(nativeLibraryAbis = emptyList())))
 }

 @Test fun debuggable_firesWhenTrue() {
  val finding = DebuggableApkRule.evaluate(testInput(debuggable = true))
  assertEquals(RuleIds.DEBUGGABLE_APK, finding?.ruleId)
  assertEquals(10, finding?.scoreContribution)
 }
 @Test fun debuggable_doesNotFireWhenFalse() {
  assertNull(DebuggableApkRule.evaluate(testInput(debuggable = false)))
 }

 @Test fun oldTargetSdk_firesBelowThreshold() {
  val finding = OldTargetSdkRule.evaluate(testInput(targetSdkVersion = OLD_TARGET_SDK_THRESHOLD - 1))
  assertEquals(RuleIds.OLD_TARGET_SDK, finding?.ruleId)
  assertEquals(5, finding?.scoreContribution)
 }
 @Test fun oldTargetSdk_doesNotFireAtThreshold() {
  assertNull(OldTargetSdkRule.evaluate(testInput(targetSdkVersion = OLD_TARGET_SDK_THRESHOLD)))
 }
 @Test fun oldTargetSdk_doesNotFireAboveThreshold() {
  assertNull(OldTargetSdkRule.evaluate(testInput(targetSdkVersion = OLD_TARGET_SDK_THRESHOLD + 5)))
 }
 @Test fun oldTargetSdk_doesNotFireWhenUnknown() {
  assertNull(OldTargetSdkRule.evaluate(testInput(targetSdkVersion = -1)))
 }

 @Test fun signatureVerificationFailed_firesWhenNotVerified() {
  val finding = SignatureVerificationFailedRule.evaluate(testInput(signatureVerified = false))
  assertEquals(RuleIds.SIGNATURE_VERIFICATION_FAILED, finding?.ruleId)
  assertEquals(20, finding?.scoreContribution)
 }
 @Test fun signatureVerificationFailed_doesNotFireWhenVerified() {
  assertNull(SignatureVerificationFailedRule.evaluate(testInput(signatureVerified = true)))
 }

 @Test fun manyExportedComponents_firesAtThreshold() {
  val finding = ManyExportedComponentsRule.evaluate(testInput(totalComponentCount = 10, exportedComponentCount = MANY_EXPORTED_COMPONENTS_THRESHOLD))
  assertEquals(RuleIds.MANY_EXPORTED_COMPONENTS, finding?.ruleId)
  assertEquals(5, finding?.scoreContribution)
 }
 @Test fun manyExportedComponents_doesNotFireJustBelowThreshold() {
  assertNull(ManyExportedComponentsRule.evaluate(testInput(totalComponentCount = 10, exportedComponentCount = MANY_EXPORTED_COMPONENTS_THRESHOLD - 1)))
 }
 @Test fun manyExportedComponents_doesNotFireForOrdinaryApp() {
  assertNull(ManyExportedComponentsRule.evaluate(testInput(totalComponentCount = 3, exportedComponentCount = 1)))
 }
}
