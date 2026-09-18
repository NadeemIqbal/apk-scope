package com.nadeem.apkscope.core.staticanalysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Milestone 10 (Security Audit), Phase 10.3: closes the `ApkMetadata.networkSecurityConfigPresent`
 * hardcoded-`null` gap named as a prerequisite blocker for MS10-NET01 in the prior checkpoint
 * (the maintainer's private verification notes). [ApkAnalyzer.analyze] needs a real
 * `PackageManager`, so this is an instrumented test, not a JVM one (`ApkAnalyzerTest`'s own doc
 * comment already establishes this constraint for this class).
 *
 * Deliberately uses two *pre-existing* real fixture APKs rather than fabricating a new one or
 * modifying an existing fixture's manifest — `core:sandbox`'s `fixture` module already declares
 * `android:networkSecurityConfig="@xml/network_security_config"` on its `<application>` tag (added
 * for unrelated HTTPS-inspection work), and `riskfixture` never has, giving a real positive and a
 * real negative case with zero risk of perturbing either fixture's already-verified rule-trigger
 * counts (adding this attribute does not correspond to any of the 10 v1 audit rules today).
 *
 * Requires both fixture APKs pushed to `/data/local/tmp/` before running — matching this project's
 * established convention (see `HttpsInspectionIntegrationTest.testStaticAnalysisOnDevice`'s
 * identical `/data/local/tmp/fixture-debug.apk` precedent):
 *   ./gradlew :fixture:assembleDebug :riskfixture:assembleDebug
 *   adb push fixture/build/outputs/apk/debug/fixture-debug.apk /data/local/tmp/fixture-debug.apk
 *   adb push riskfixture/build/outputs/apk/debug/riskfixture-debug.apk /data/local/tmp/riskfixture-debug.apk
 */
@RunWith(AndroidJUnit4::class)
class ApkAnalyzerNetworkSecurityConfigTest {
 private val context = InstrumentationRegistry.getInstrumentation().targetContext

 @Test
 fun analyze_detectsNetworkSecurityConfigPresent_onFixtureThatDeclaresIt() {
  val apkFile = File("/data/local/tmp/fixture-debug.apk")
  assertTrue("fixture-debug.apk must be pushed to /data/local/tmp first", apkFile.exists() && apkFile.canRead())

  val result = ApkAnalyzer.analyze(context, apkFile)

  assertEquals(
   "fixture's manifest declares android:networkSecurityConfig — must be detected as present, not left null/false",
   true,
   result.metadata.networkSecurityConfigPresent,
  )
 }

 @Test
 fun analyze_detectsNetworkSecurityConfigAbsent_onFixtureThatDoesNotDeclareIt() {
  val apkFile = File("/data/local/tmp/riskfixture-debug.apk")
  assertTrue("riskfixture-debug.apk must be pushed to /data/local/tmp first", apkFile.exists() && apkFile.canRead())

  val result = ApkAnalyzer.analyze(context, apkFile)

  assertEquals(
   "riskfixture's manifest never declares android:networkSecurityConfig — must be false (confirmed absent), not null (unknown) or true",
   false,
   result.metadata.networkSecurityConfigPresent,
  )
 }

 /**
  * Phase 10.3 correction: real resource-*content* parsing through the full [ApkAnalyzer.analyze]
  * pipeline — resources.arsc resolution, then binary-XML content parsing — not just attribute
  * presence. `fixture`'s real `network_security_config.xml` declares `cleartextTrafficPermitted="true"`
  * on `<base-config>` and no `<pin-set>` anywhere.
  */
 @Test
 fun analyze_parsesRealNetworkSecurityConfigContentOnFixture() {
  val apkFile = File("/data/local/tmp/fixture-debug.apk")
  assertTrue("fixture-debug.apk must be pushed to /data/local/tmp first", apkFile.exists() && apkFile.canRead())

  val result = ApkAnalyzer.analyze(context, apkFile)

  val summary = result.metadata.networkSecurityConfig
  assertTrue("fixture's networkSecurityConfig must be parsed, not null", summary != null)
  assertEquals("resolution/parsing must succeed for this real, supported-subset resource", null, summary!!.unavailableReason)
  assertEquals(true, summary.baseConfigCleartextTrafficPermitted)
  assertEquals("fixture's real config declares no <pin-set> anywhere", false, summary.hasAnyPinSet)
  assertEquals("fixture's real debug-overrides trusts both system and user CAs", true, summary.debugOverridesTrustsUserCerts)
  assertEquals(0, summary.domainConfigCount)
 }

 @Test
 fun analyze_leavesNetworkSecurityConfigNullWhenAttributeAbsent() {
  val apkFile = File("/data/local/tmp/riskfixture-debug.apk")
  assertTrue("riskfixture-debug.apk must be pushed to /data/local/tmp first", apkFile.exists() && apkFile.canRead())

  val result = ApkAnalyzer.analyze(context, apkFile)

  assertEquals(
   "no networkSecurityConfig attribute at all — the content field must be null, not a default/guessed summary",
   null,
   result.metadata.networkSecurityConfig,
  )
 }
}
