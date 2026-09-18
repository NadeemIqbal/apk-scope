package com.nadeem.apkscope.core.staticanalysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Milestone 10 (Security Audit), Phase 10.3, MS10-CODE01 — real fires/does-not-fire cases against
 * `fixture-debug.apk`'s real compiled `CodePatternFixtures`/`UnsafeSslErrorWebViewClient`/
 * `SafeSslErrorWebViewClient`/`NotAWebViewClientButSameMethodName` classes (see that file's own doc).
 */
class CodePatternAnalyzerTest {

 private fun findFixtureApk(): File {
  val candidates = listOf(File("../../fixture/build/outputs/apk/debug/fixture-debug.apk"), File("fixture/build/outputs/apk/debug/fixture-debug.apk"), File("../fixture/build/outputs/apk/debug/fixture-debug.apk"))
  return candidates.firstOrNull { it.exists() && it.canRead() } ?: throw IllegalStateException("fixture-debug.apk not found; run :fixture:assembleDebug")
 }

 @Test
 fun detectsWeakCipherAlgorithmHeuristicOnRealPositiveFixture() {
  val result = CodePatternAnalyzer.analyzeApk(findFixtureApk())
  val found = result.findings.filter {
   it.category == CodePatternAnalyzer.CodePatternCategory.WEAK_CRYPTOGRAPHIC_ALGORITHM_HEURISTIC && it.methodName == "weakCipherReference"
  }
  assertTrue("must detect the real DES co-occurrence in weakCipherReference", found.isNotEmpty())
  assertEquals(CodePatternAnalyzer.EvidenceConfidence.HEURISTIC, found.first().confidence)
  assertEquals(CodePatternAnalyzer.EvidenceTier.REFERENCE, found.first().tier)
 }

 @Test
 fun detectsWeakDigestAlgorithmHeuristicOnRealPositiveFixture() {
  val result = CodePatternAnalyzer.analyzeApk(findFixtureApk())
  val found = result.findings.filter {
   it.category == CodePatternAnalyzer.CodePatternCategory.WEAK_CRYPTOGRAPHIC_ALGORITHM_HEURISTIC && it.methodName == "weakDigestReference"
  }
  assertTrue("must detect the real MD5 co-occurrence in weakDigestReference", found.isNotEmpty())
 }

 @Test
 fun doesNotFlagModernAlgorithmReferencesOnRealNegativeFixture() {
  val result = CodePatternAnalyzer.analyzeApk(findFixtureApk())
  val flaggedMethods = result.findings
   .filter { it.category == CodePatternAnalyzer.CodePatternCategory.WEAK_CRYPTOGRAPHIC_ALGORITHM_HEURISTIC }
   .map { it.methodName }
  assertFalse("modernCipherReference (AES/GCM) must not be flagged", flaggedMethods.contains("modernCipherReference"))
  assertFalse("modernDigestReference (SHA-256) must not be flagged", flaggedMethods.contains("modernDigestReference"))
 }

 @Test
 fun detectsWebViewSslErrorBypassOnRealPositiveFixture() {
  val result = CodePatternAnalyzer.analyzeApk(findFixtureApk())
  val found = result.findings.filter {
   it.category == CodePatternAnalyzer.CodePatternCategory.WEBVIEW_SSL_ERROR_BYPASS &&
    it.className == "com.apksandbox.fixture.UnsafeSslErrorWebViewClient"
  }
  assertTrue("must detect the real unsafe onReceivedSslError in UnsafeSslErrorWebViewClient", found.isNotEmpty())
  assertEquals(CodePatternAnalyzer.EvidenceConfidence.CONFIRMED, found.first().confidence)
  assertEquals(CodePatternAnalyzer.EvidenceTier.REFERENCE, found.first().tier)
 }

 @Test
 fun doesNotFlagSafeWebViewClientThatCancelsOnSslError() {
  val result = CodePatternAnalyzer.analyzeApk(findFixtureApk())
  val found = result.findings.filter {
   it.category == CodePatternAnalyzer.CodePatternCategory.WEBVIEW_SSL_ERROR_BYPASS &&
    it.className == "com.apksandbox.fixture.SafeSslErrorWebViewClient"
  }
  assertTrue("SafeSslErrorWebViewClient calls handler.cancel(), not proceed() — must not be flagged", found.isEmpty())
 }

 @Test
 fun doesNotFlagAClassWithTheSameMethodNameThatDoesNotExtendWebViewClient() {
  // Proves the check is a real class-hierarchy conjunction, not a method-name-only match.
  val result = CodePatternAnalyzer.analyzeApk(findFixtureApk())
  val found = result.findings.filter {
   it.category == CodePatternAnalyzer.CodePatternCategory.WEBVIEW_SSL_ERROR_BYPASS &&
    it.className == "com.apksandbox.fixture.NotAWebViewClientButSameMethodName"
  }
  assertTrue("a class that merely has a method named onReceivedSslError, without extending WebViewClient, must not be flagged", found.isEmpty())
 }

 @Test
 fun everyFindingIsStructurallyTaggedAsReferenceTier() {
  val result = CodePatternAnalyzer.analyzeApk(findFixtureApk())
  assertTrue("fixture must produce at least one finding for this test to be meaningful", result.findings.isNotEmpty())
  for (finding in result.findings) {
   assertEquals("finding for ${finding.category} must be tagged REFERENCE, not REACHABLE/EXECUTED", CodePatternAnalyzer.EvidenceTier.REFERENCE, finding.tier)
  }
 }

 // --- Accuracy hardening pass (2026-09-14): confirmedFacts must state only the directly-observed
 // structural facts; interpretation must carry the security-impact reading, never the reverse. ---

 @Test
 fun webViewSslBypassConfirmedFactsUseTheExactRequiredWordingAndNoBehavioralClaim() {
  val result = CodePatternAnalyzer.analyzeApk(findFixtureApk())
  val finding = result.findings.first {
   it.category == CodePatternAnalyzer.CodePatternCategory.WEBVIEW_SSL_ERROR_BYPASS &&
    it.className == "com.apksandbox.fixture.UnsafeSslErrorWebViewClient"
  }
  assertTrue(
   "confirmedFacts must use the exact required phrasing",
   finding.confirmedFacts.contains("Confirmed call to SslErrorHandler.proceed() from onReceivedSslError"),
  )
  assertTrue("confirmedFacts must state the override fact", finding.confirmedFacts.contains("overrides onReceivedSslError"))
  // The two proven facts only — no claim about what proceed() causes at runtime, no "bypassed"/
  // "certificate validation is" language, which belongs only in interpretation.
  assertFalse("confirmedFacts must not itself claim validation is bypassed", finding.confirmedFacts.contains("bypass", ignoreCase = true))
  assertFalse("confirmedFacts must not claim certificate validation was skipped as an established fact", finding.confirmedFacts.contains("certificate validation is", ignoreCase = true))
 }

 @Test
 fun webViewSslBypassInterpretationCarriesTheSecurityReadAndIsLabeledAsInterpretation() {
  val result = CodePatternAnalyzer.analyzeApk(findFixtureApk())
  val finding = result.findings.first {
   it.category == CodePatternAnalyzer.CodePatternCategory.WEBVIEW_SSL_ERROR_BYPASS &&
    it.className == "com.apksandbox.fixture.UnsafeSslErrorWebViewClient"
  }
  assertTrue("interpretation must be explicitly labeled as interpretation, not proven fact", finding.interpretation.contains("Interpretation"))
  assertTrue("interpretation must not claim to be proven by this scanner", finding.interpretation.contains("not proven by this scanner"))
  assertTrue("interpretation is where the bypass-indicator read belongs", finding.interpretation.contains("strong indicator", ignoreCase = true))
  assertTrue(
   "interpretation must explicitly disclaim confirming a runtime bypass",
   finding.interpretation.contains("Do not read this finding as confirming certificate validation is bypassed"),
  )
 }

 @Test
 fun weakAlgorithmConfirmedFactsDoNotClaimTheStringWasThePassedArgument() {
  val result = CodePatternAnalyzer.analyzeApk(findFixtureApk())
  val finding = result.findings.first {
   it.category == CodePatternAnalyzer.CodePatternCategory.WEAK_CRYPTOGRAPHIC_ALGORITHM_HEURISTIC && it.methodName == "weakCipherReference"
  }
  assertFalse("confirmedFacts must not claim the string was passed as the argument", finding.confirmedFacts.contains("passed"))
  assertTrue("confirmedFacts should state the co-occurrence facts only", finding.confirmedFacts.contains("contains an invocation"))
 }

 @Test
 fun weakAlgorithmInterpretationExplicitlyLabelsItselfHeuristicAndUnproven() {
  val result = CodePatternAnalyzer.analyzeApk(findFixtureApk())
  val finding = result.findings.first {
   it.category == CodePatternAnalyzer.CodePatternCategory.WEAK_CRYPTOGRAPHIC_ALGORITHM_HEURISTIC && it.methodName == "weakCipherReference"
  }
  assertTrue(finding.interpretation.contains("HEURISTIC"))
  assertTrue(finding.interpretation.contains("not proven"))
  assertTrue(
   "must explicitly disclaim confirming the argument binding",
   finding.interpretation.contains("cannot establish that this string constant was actually passed"),
  )
 }

 // --- MS10-COV01: Coverage reporting with limit detection ---

 @Test
 fun coverageReportsScannedDexFiles() {
  val result = CodePatternAnalyzer.analyzeApk(findFixtureApk())
  assertTrue("coverage must report which DEX files were inspected", result.coverage.dexFilesInspected.isNotEmpty())
  assertTrue("must include classes.dex", result.coverage.dexFilesInspected.any { it.contains("classes.dex") })
 }

 @Test
 fun coverageReportsScanDurationMilliseconds() {
  val result = CodePatternAnalyzer.analyzeApk(findFixtureApk())
  assertTrue("coverage must record scan duration", result.coverage.scanDurationMs > 0)
 }

 @Test
 fun coverageReportsLimitReachedFalseWhenFindingsAreBelowThreshold() {
  // The real fixture has far fewer than 500 findings, so this should not trigger the limit.
  val result = CodePatternAnalyzer.analyzeApk(findFixtureApk())
  assertFalse("real fixture findings are well below MAX_FINDINGS (500), so limitsReached must be false", result.coverage.limitsReached)
 }

 @Test
 fun coverageCanReportParsingErrors() {
  // This is a structural test: the coverage model includes a parsingErrors list for documenting
  // any errors encountered during scanning (malformed DEX, partial read, etc.). Real fixture has
  // none, but the field must exist and be reported honestly.
  val result = CodePatternAnalyzer.analyzeApk(findFixtureApk())
  assertEquals("real fixture has no parsing errors", emptyList<String>(), result.coverage.parsingErrors)
 }
}
