package com.nadeem.apkscope.core.staticanalysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Milestone 10 (Security Audit), Phase 10.3, MS10-BUILD01 — real fires/does-not-fire cases:
 * `obfuscatedfixture-debug.apk` (10 real classes, all deliberately 1-letter names — see that module's
 * own doc for why hand-authored short names are an honest fixture) vs `riskfixture-debug.apk` (real,
 * ordinary descriptive class names, unrelated to this requirement, never modified for it).
 */
class BuildObfuscationAnalyzerTest {

 private fun findApk(relativePath: String): File {
  val candidates = listOf(File("../../$relativePath"), File(relativePath), File("../$relativePath"))
  return candidates.firstOrNull { it.exists() && it.canRead() } ?: throw IllegalStateException("$relativePath not found; run the matching :assembleDebug task")
 }

 @Test
 fun flagsNamingPatternConsistentWithObfuscationOnRealShortNamedFixture() {
  val result = BuildObfuscationAnalyzer.analyze(findApk("obfuscatedfixture/build/outputs/apk/debug/obfuscatedfixture-debug.apk"))
  assertTrue("all 10 real fixture classes are 1-letter names — ratio must be very high", result.shortNameRatio > 0.9)
  assertTrue(result.namingPatternConsistentWithObfuscation)
  assertEquals(10, result.shortGeneratedLookingNameCount)
 }

 @Test
 fun doesNotFlagRealDescriptivelyNamedFixture() {
  val result = BuildObfuscationAnalyzer.analyze(findApk("riskfixture/build/outputs/apk/debug/riskfixture-debug.apk"))
  assertFalse("riskfixture's real classes (MainActivity, NoOpActivityOne, ...) are all descriptively named", result.namingPatternConsistentWithObfuscation)
  assertEquals(0, result.shortGeneratedLookingNameCount)
 }

 @Test
 fun everyAssessmentCarriesTheNonProofDisclaimerRegardlessOfOutcome() {
  val obfuscated = BuildObfuscationAnalyzer.analyze(findApk("obfuscatedfixture/build/outputs/apk/debug/obfuscatedfixture-debug.apk"))
  val notObfuscated = BuildObfuscationAnalyzer.analyze(findApk("riskfixture/build/outputs/apk/debug/riskfixture-debug.apk"))
  assertTrue(obfuscated.disclaimer.contains("does not prove"))
  assertTrue(notObfuscated.disclaimer.contains("does not prove"))
 }

 @Test
 fun generatedRAndBuildConfigClassesAreExcludedFromTheRatio() {
  // obfuscatedfixture's real compiled output includes a generated `R` class (confirmed via direct
  // zip inspection before writing this test) — it must not be counted as evidence of obfuscation.
  val result = BuildObfuscationAnalyzer.analyze(findApk("obfuscatedfixture/build/outputs/apk/debug/obfuscatedfixture-debug.apk"))
  assertEquals("R/BuildConfig excluded — only the 10 real short-named fixture classes should count", 10, result.totalClassesInspected)
 }

 @Test
 fun mappingFileSuppliedButMatchingNoRealClassInTheApkYieldsZeroConfirmedRenames() {
  val fakeMapping = "com.example.Original -> zzz:\n    void method() -> a\n"
  val result = BuildObfuscationAnalyzer.analyzeWithOptionalMappingFile(
   findApk("riskfixture/build/outputs/apk/debug/riskfixture-debug.apk"), fakeMapping,
  )
  assertEquals(0, result.mappingConfirmedRenamedClassCount)
 }

 @Test
 fun mappingFileNamingARealPresentClassYieldsAConfirmedCount() {
  // "com.apksandbox.obfuscatedfixture.a" really is one of this fixture's 10 real compiled classes —
  // a genuine positive case for the mapping-file cross-check, not a fabricated one.
  val mapping = "com.example.SomeOriginalName -> com.apksandbox.obfuscatedfixture.a:\n"
  val result = BuildObfuscationAnalyzer.analyzeWithOptionalMappingFile(
   findApk("obfuscatedfixture/build/outputs/apk/debug/obfuscatedfixture-debug.apk"), mapping,
  )
  assertEquals(1, result.mappingConfirmedRenamedClassCount)
 }

 @Test fun noMappingFileSuppliedLeavesConfirmedCountNull() {
  val result = BuildObfuscationAnalyzer.analyze(findApk("riskfixture/build/outputs/apk/debug/riskfixture-debug.apk"))
  assertEquals(null, result.mappingConfirmedRenamedClassCount)
 }
}
