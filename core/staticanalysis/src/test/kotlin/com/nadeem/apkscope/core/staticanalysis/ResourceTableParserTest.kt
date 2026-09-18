package com.nadeem.apkscope.core.staticanalysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Milestone 10 (Security Audit), Phase 10.3, MS10-NET01 correction — [ResourceTableParser] resolves a
 * real, compiled resource id from `fixture-debug.apk`'s actual `resources.arsc`, not a fabricated one.
 * `fixture`'s `AndroidManifest.xml` declares `android:networkSecurityConfig="@xml/network_security_config"`
 * (confirmed present via `ApkAnalyzerNetworkSecurityConfigTest`); this test resolves that reference all
 * the way to its real zip-entry path.
 */
class ResourceTableParserTest {

 private fun findApk(relativePath: String): File {
  val candidates = listOf(File("../../$relativePath"), File(relativePath), File("../$relativePath"))
  return candidates.firstOrNull { it.exists() && it.canRead() }
   ?: throw IllegalStateException("$relativePath not found; run the matching :assembleDebug task")
 }

 private fun loadArsc(apkFile: File): ByteArray =
  ZipFile(apkFile).use { zip -> zip.getInputStream(zip.getEntry("resources.arsc")).readBytes() }

 @Test
 fun resolvesRealNetworkSecurityConfigResourceFromFixtureApk() {
  val apkFile = findApk("fixture/build/outputs/apk/debug/fixture-debug.apk")
  val arsc = loadArsc(apkFile)

  // fixture's resources.arsc has exactly one package (0x7f), one type ("xml", 1-based type id 1),
  // one entry (id 0, "network_security_config") — verified by direct inspection of the real compiled
  // table (not assumed), so this exact resource id is real, not guessed.
  val resourceId = 0x7f010000

  val result = ResourceTableParser.resolveFileResource(arsc, resourceId)
  assertTrue("must resolve, got $result", result is ResourceTableParser.ResolveResult.Resolved)
  assertEquals("res/xml/network_security_config.xml", (result as ResourceTableParser.ResolveResult.Resolved).path)
 }

 @Test
 fun reportsNotFoundForAResourceIdWithNoEntry() {
  val apkFile = findApk("fixture/build/outputs/apk/debug/fixture-debug.apk")
  val arsc = loadArsc(apkFile)

  val result = ResourceTableParser.resolveFileResource(arsc, 0x7f01FFFF) // same type, an entry id that does not exist
  assertEquals(ResourceTableParser.ResolveResult.NotFound, result)
 }

 @Test
 fun reportsNotFoundForAnUnknownPackageId() {
  val apkFile = findApk("fixture/build/outputs/apk/debug/fixture-debug.apk")
  val arsc = loadArsc(apkFile)

  val result = ResourceTableParser.resolveFileResource(arsc, 0x01010000) // package 0x01 does not exist in this table
  assertEquals(ResourceTableParser.ResolveResult.NotFound, result)
 }

 @Test
 fun reportsParseFailedForGarbageBytesRatherThanCrashingOrGuessing() {
  val result = ResourceTableParser.resolveFileResource(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 0x7f010000)
  assertTrue("must be ParseFailed, got $result", result is ResourceTableParser.ResolveResult.ParseFailed)
 }
}
