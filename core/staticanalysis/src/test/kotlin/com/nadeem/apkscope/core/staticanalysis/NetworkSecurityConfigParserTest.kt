package com.nadeem.apkscope.core.staticanalysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Milestone 10 (Security Audit), Phase 10.3, MS10-NET01 correction — parses `fixture-debug.apk`'s
 * real, pre-existing `network_security_config.xml` (added earlier for unrelated HTTPS-inspection
 * work, not fabricated for this test): a `<base-config cleartextTrafficPermitted="true">` with a
 * `system` trust anchor, and a `<debug-overrides>` trusting both `system` and `user` CAs. See
 * `fixture/src/main/res/xml/network_security_config.xml` for the source this test's expectations are
 * derived from.
 */
class NetworkSecurityConfigParserTest {

 private fun findApk(relativePath: String): File {
  val candidates = listOf(File("../../$relativePath"), File(relativePath), File("../$relativePath"))
  return candidates.firstOrNull { it.exists() && it.canRead() }
   ?: throw IllegalStateException("$relativePath not found; run the matching :assembleDebug task")
 }

 @Test
 fun parsesRealFixtureNetworkSecurityConfigContent() {
  val apkFile = findApk("fixture/build/outputs/apk/debug/fixture-debug.apk")
  val xmlBytes = ZipFile(apkFile).use { zip ->
   zip.getInputStream(zip.getEntry("res/xml/network_security_config.xml")).readBytes()
  }

  val result = NetworkSecurityConfigParser.parseXmlBytes(xmlBytes)
  assertTrue("must parse, got $result", result is NetworkSecurityConfigParser.ParseResult.Parsed)
  val analysis = (result as NetworkSecurityConfigParser.ParseResult.Parsed).analysis

  val base = analysis.baseConfig
  assertTrue("base-config must be present", base != null)
  assertEquals("fixture's base-config declares cleartextTrafficPermitted=true", true, base!!.cleartextTrafficPermitted)
  assertEquals(listOf("system"), base.trustAnchorSources)
  assertNull("base-config has no pin-set", base.pinSet)
  assertTrue("base-config has no <domain> children (it's the base, not a domain-config)", base.domains.isEmpty())

  assertTrue("domain-config list must be empty — fixture declares none", analysis.domainConfigs.isEmpty())

  val debug = analysis.debugOverrides
  assertTrue("debug-overrides must be present", debug != null)
  assertEquals("debug-overrides does not itself declare cleartextTrafficPermitted", null, debug!!.cleartextTrafficPermitted)
  assertEquals(listOf("system", "user"), debug.trustAnchorSources)

  assertTrue("no unsupported constructs in this real fixture's XML", analysis.unsupportedNotes.isEmpty())
 }

 @Test
 fun parsesFullPipelineFromManifestAttributeThroughResourceResolutionToXmlContent() {
  val apkFile = findApk("fixture/build/outputs/apk/debug/fixture-debug.apk")
  val result = ZipFile(apkFile).use { zip ->
   // The exact raw attribute-value form BinaryXmlParser emits for a TYPE_REFERENCE — verified
   // against fixture's real compiled resource id via ResourceTableParserTest.
   NetworkSecurityConfigParser.parseFromManifestAttribute(zip, "@0x7f010000")
  }
  assertTrue("full pipeline must resolve and parse, got $result", result is NetworkSecurityConfigParser.ParseResult.Parsed)
  val analysis = (result as NetworkSecurityConfigParser.ParseResult.Parsed).analysis
  assertEquals(true, analysis.baseConfig?.cleartextTrafficPermitted)
 }

 @Test
 fun reportsUnavailableForAnUnresolvableAttributeValue() {
  val apkFile = findApk("fixture/build/outputs/apk/debug/fixture-debug.apk")
  val result = ZipFile(apkFile).use { zip ->
   NetworkSecurityConfigParser.parseFromManifestAttribute(zip, "not-a-resource-reference")
  }
  assertTrue("must be Unavailable, got $result", result is NetworkSecurityConfigParser.ParseResult.Unavailable)
 }

 /**
  * `fixture`'s existing `network_security_config.xml` only exercises `<base-config>`/
  * `<debug-overrides>` — added a second real, compiled fixture resource,
  * `network_security_config_domain_pins.xml`, purely to give `<domain-config>`/`<domain
  * includeSubdomains>`/`<pin-set expiration>`/`<pin digest>` real, AAPT2-compiled binary XML bytes
  * to parse (not referenced by `AndroidManifest.xml` — read directly by zip-entry path here, same as
  * the other fixture resource above). Pin digests are obviously fake placeholders, documented in the
  * resource file's own comment, never wired into this fixture's real `android:networkSecurityConfig`.
  */
 @Test
 fun parsesDomainConfigWithDomainsAndPinSet() {
  val apkFile = findApk("fixture/build/outputs/apk/debug/fixture-debug.apk")
  val xmlBytes = ZipFile(apkFile).use { zip ->
   zip.getInputStream(zip.getEntry("res/xml/network_security_config_domain_pins.xml")).readBytes()
  }
  val result = NetworkSecurityConfigParser.parseXmlBytes(xmlBytes)
  assertTrue("must parse, got $result", result is NetworkSecurityConfigParser.ParseResult.Parsed)
  val analysis = (result as NetworkSecurityConfigParser.ParseResult.Parsed).analysis

  assertEquals(1, analysis.domainConfigs.size)
  val domainConfig = analysis.domainConfigs[0]
  assertEquals(false, domainConfig.cleartextTrafficPermitted)
  assertEquals(
   listOf(NetworkSecurityConfigParser.DomainEntry("example.com", true)),
   domainConfig.domains,
  )
  val pinSet = domainConfig.pinSet
  assertTrue("pin-set must be present", pinSet != null)
  assertEquals("2027-01-01", pinSet!!.expirationDate)
  assertEquals(2, pinSet.pins.size)
  assertEquals("sha256", pinSet.pins[0].digestAlgorithm)
 }
}
