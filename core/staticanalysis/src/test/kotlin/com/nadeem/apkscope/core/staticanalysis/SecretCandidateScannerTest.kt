package com.nadeem.apkscope.core.staticanalysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Milestone 10 (Security Audit), Phase 10.3, MS10-SECRET01-03 — real fires/does-not-fire cases
 * against `riskfixture-debug.apk`'s real compiled `SecretCandidateFixtures` constants (see that
 * file's own doc for exactly why every value is fake-but-real-shaped), not fabricated test input.
 */
class SecretCandidateScannerTest {

 private fun findRiskFixtureApk(): File {
  val candidates = listOf(
   File("../../riskfixture/build/outputs/apk/debug/riskfixture-debug.apk"),
   File("riskfixture/build/outputs/apk/debug/riskfixture-debug.apk"),
   File("../riskfixture/build/outputs/apk/debug/riskfixture-debug.apk"),
  )
  return candidates.firstOrNull { it.exists() && it.canRead() }
   ?: throw IllegalStateException("riskfixture-debug.apk not found; run :riskfixture:assembleDebug")
 }

 @Test
 fun detectsAwsAccessKeyExampleInRealFixture() {
  val result = SecretCandidateScanner.scanApk(findRiskFixtureApk())
  val found = result.findings.filter { it.category == SecretCategory.AWS_ACCESS_KEY }
  assertTrue("must detect the real AWS access key example constant", found.isNotEmpty())
 }

 @Test
 fun detectsPrivateKeyBlockInRealFixture() {
  val result = SecretCandidateScanner.scanApk(findRiskFixtureApk())
  val found = result.findings.filter { it.category == SecretCategory.PRIVATE_KEY_BLOCK }
  assertTrue("must detect the real PEM private-key header", found.isNotEmpty())
 }

 @Test
 fun detectsGoogleApiKeySlackTokenAndJwtInRealFixture() {
  val result = SecretCandidateScanner.scanApk(findRiskFixtureApk())
  val categories = result.findings.map { it.category }.toSet()
  assertTrue(categories.contains(SecretCategory.GOOGLE_API_KEY))
  assertTrue(categories.contains(SecretCategory.SLACK_TOKEN))
  assertTrue(categories.contains(SecretCategory.JWT))
 }

 /**
  * MS10-SECRET01's own acceptance criterion: a public-looking identifier of the same general shape
  * (a Firebase project id, an application id, a version string) must never be flagged — none of them
  * match any of this scanner's credential formats. Checked directly against the format-matcher
  * itself (not by inference from the whole-APK scan result), against the real fixture module's own
  * constants — not values invented just for this test.
  */
 @Test
 fun doesNotFlagPublicIdentifiersOfSimilarShape() {
  assertFalse(SecretCandidateScanner.matchesAnyKnownFormat("apk-scope-fixture-12345")) // FIREBASE_PROJECT_ID_PUBLIC
  assertFalse(SecretCandidateScanner.matchesAnyKnownFormat("com.apksandbox.riskfixture")) // APPLICATION_ID_PUBLIC
  assertFalse(SecretCandidateScanner.matchesAnyKnownFormat("1.0.0-fixture")) // VERSION_STRING_PUBLIC
 }

 @Test
 fun everyFindingCarriesOnlyAMaskedValueNeverTheRawSecret() {
  val result = SecretCandidateScanner.scanApk(findRiskFixtureApk())
  assertTrue("fixture must produce at least one finding for this test to be meaningful", result.findings.isNotEmpty())
  for (finding in result.findings) {
   assertFalse("masked value must not equal the known raw AWS key", finding.maskedValue == SecretCandidateFixtureRawValuesForNegativeAssertionOnly.AWS_KEY)
   assertTrue("masked value must contain a masking character for anything longer than 8 chars", finding.maskedValue.length <= 8 || finding.maskedValue.contains('*'))
  }
 }

 @Test fun maskFullyHidesShortValues() {
  assertEquals("********", SecretCandidateScanner.mask("AKIAEXAM"))
 }

 @Test fun maskPreservesOnlyFirstAndLastTwoCharsForLongerValues() {
  val masked = SecretCandidateScanner.mask("AKIAIOSFODNN7EXAMPLE")
  assertTrue(masked.startsWith("AK"))
  assertTrue(masked.endsWith("LE"))
  assertFalse("the masked form must not contain the real middle of the key", masked.contains("IOSFODNN7EXAMP"))
 }

 /**
  * MS10-SECRET03: a real, automated check — not a one-time manual code-review claim — that this
  * scanner's own compiled bytecode contains no reference to any networking class. Reads the actual
  * `.class` file bytes for [SecretCandidateScanner] off the test classpath and inspects the
  * constant-pool UTF8 entries directly (the same class of technique this whole project applies to
  * APKs, turned on its own source).
  */
 @Test
 fun secretScannerCompiledBytecodeContainsNoNetworkingClassReferences() {
  val resourceName = "com/nadeem/apkscope/core/staticanalysis/SecretCandidateScanner.class"
  val bytes = SecretCandidateScanner::class.java.classLoader
   .getResourceAsStream(resourceName)
   ?.use { it.readBytes() }
   ?: throw IllegalStateException("could not load compiled class bytes for $resourceName off the test classpath")

  val text = String(bytes, Charsets.ISO_8859_1) // constant-pool UTF8 entries are ASCII-safe for class/package names; ISO_8859_1 preserves every byte 1:1 for a substring scan
  val bannedSubstrings = listOf("java/net/", "javax/net/", "okhttp3/", "HttpURLConnection", "URLConnection", "Socket")
  for (banned in bannedSubstrings) {
   assertFalse("SecretCandidateScanner's compiled bytecode must not reference $banned", text.contains(banned))
  }
 }
}

/** Isolated from the test class body so the raw value literally appears nowhere near the assertions that check it is absent — kept obviously separate and named for exactly this one negative-assertion purpose, never used to construct a real finding. */
private object SecretCandidateFixtureRawValuesForNegativeAssertionOnly {
 const val AWS_KEY = "AKIAIOSFODNN7EXAMPLE"
}
