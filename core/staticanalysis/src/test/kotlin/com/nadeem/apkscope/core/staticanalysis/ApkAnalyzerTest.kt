package com.nadeem.apkscope.core.staticanalysis

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Only [ApkAnalyzer.sha256] is unit-testable in a plain JVM test — [ApkAnalyzer.analyze] needs a
 * real `PackageManager`, which throws "not mocked" outside an instrumented/device test (same
 * constraint documented throughout `core:network`'s test suite). Parsing/signing-verification
 * coverage is deferred to an instrumented test, not implemented in this checkpoint.
 */
class ApkAnalyzerTest {
 @Test fun sha256MatchesKnownVector() {
  val file = File.createTempFile("apk-analyzer-test", ".bin")
  try {
   file.writeBytes("abc".toByteArray(Charsets.US_ASCII))
   assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ApkAnalyzer.sha256(file))
  } finally { file.delete() }
 }

 @Test fun sha256OfEmptyFileMatchesKnownVector() {
  val file = File.createTempFile("apk-analyzer-test-empty", ".bin")
  try {
   assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ApkAnalyzer.sha256(file))
  } finally { file.delete() }
 }
}
