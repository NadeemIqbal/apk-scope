package com.nadeem.apkscope.poc.apkrepack

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

class FridaBodyCaptureTest {
    @Test
    fun retainsOnlyPrefixAcrossChunksButCountsAllObservedBytes() {
        val capture = FridaBodyCapture(limit = 5)
        capture.write("abc".toByteArray())
        capture.write("defgh".toByteArray())
        assertArrayEquals("abcde".toByteArray(), capture.decode(null).bytes)
        assertEquals(8L, capture.observedBytes)
        assertTrue(capture.decode(null).truncated)
    }

    @Test
    fun byteCountDoesNotOverflowAfterTwoGiB() {
        val capture = FridaBodyCapture()
        val chunk = ByteArray(64 * 1024)
        repeat(32_769) { capture.write(chunk) }
        assertEquals(32_769L * chunk.size, capture.observedBytes)
        assertEquals(64 * 1024, capture.decode(null).bytes.size)
        assertTrue(capture.isTruncated)
    }

    @Test
    fun gzipExpansionIsBoundedEvenWhenBinarySummaryWouldBeShort() {
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(ByteArray(2 * 1024 * 1024)) }
        }.toByteArray()
        val capture = FridaBodyCapture().apply { write(compressed) }
        assertFalse(capture.isTruncated) // all compressed bytes fit; decoded bytes do not
        val decoded = capture.decode("gzip")
        assertEquals(64 * 1024, decoded.bytes.size)
        assertTrue(decoded.truncated)
        assertEquals(compressed.size.toLong(), capture.observedBytes)
    }

    @Test
    fun wrappedAndRawDeflateBothRespectDecodedLimit() {
        for (raw in listOf(false, true)) {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, raw)
            val output = ByteArrayOutputStream()
            try {
                DeflaterOutputStream(output, deflater).use { it.write(ByteArray(1024 * 1024) { 65 }) }
            } finally {
                deflater.end()
            }
            val decoded = FridaBodyCapture().apply { write(output.toByteArray()) }.decode("deflate")
            assertArrayEquals(ByteArray(64 * 1024) { 65 }, decoded.bytes)
            assertTrue(decoded.truncated)
        }
    }

    @Test
    fun exactlyFullGzipPreviewIsNotMarkedTruncated() {
        val bytes = ByteArray(64 * 1024) { 65 }
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        val decoded = FridaBodyCapture().apply { write(output.toByteArray()) }.decode("gzip")
        assertArrayEquals(bytes, decoded.bytes)
        assertFalse(decoded.truncated)
    }

    @Test
    fun invalidCompressedDataFallsBackToBoundedOriginal() {
        val decoded = FridaBodyCapture(limit = 4).apply { write("invalid".toByteArray()) }.decode("gzip")
        assertArrayEquals("inva".toByteArray(), decoded.bytes)
        assertTrue(decoded.truncated)
    }
}
