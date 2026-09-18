package com.nadeem.apkscope.core.network.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

class GrpcMessageDecoderTest {

    @Test
    fun testLengthPrefixedUncompressedMessage() {
        val messages = mutableListOf<GrpcMessage>()
        val decoder = GrpcMessageDecoder(direction = Direction.OUTBOUND) { messages.add(it) }

        val payload = "hello grpc".toByteArray(StandardCharsets.UTF_8)
        val header = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
            .put(0.toByte()) // uncompressed
            .putInt(payload.size)
            .array()

        decoder.feed(header + payload)

        assertEquals(1, messages.size)
        val msg = messages[0]
        assertEquals(1, msg.sequence)
        assertEquals(Direction.OUTBOUND, msg.direction)
        assertFalse(msg.isCompressed)
        assertEquals(payload.size, msg.length)
        assertTrue(msg.payloadPreview.contains("hello grpc"))
    }

    @Test
    fun testMultipleMessagesInOneDataChunk() {
        val messages = mutableListOf<GrpcMessage>()
        val decoder = GrpcMessageDecoder(direction = Direction.INBOUND) { messages.add(it) }

        val p1 = "msg 1".toByteArray()
        val p2 = "msg 2".toByteArray()

        val h1 = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN).put(0.toByte()).putInt(p1.size).array()
        val h2 = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN).put(0.toByte()).putInt(p2.size).array()

        decoder.feed(h1 + p1 + h2 + p2)

        assertEquals(2, messages.size)
        assertEquals(1, messages[0].sequence)
        assertEquals(2, messages[1].sequence)
        assertTrue(messages[0].payloadPreview.contains("msg 1"))
        assertTrue(messages[1].payloadPreview.contains("msg 2"))
    }

    @Test
    fun testMessageSplitAcrossChunks() {
        val messages = mutableListOf<GrpcMessage>()
        val decoder = GrpcMessageDecoder(direction = Direction.OUTBOUND) { messages.add(it) }

        val payload = "split test across boundaries".toByteArray()
        val header = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN).put(0.toByte()).putInt(payload.size).array()
        val full = header + payload

        // Feed byte by byte
        for (b in full) {
            decoder.feed(byteArrayOf(b))
        }

        assertEquals(1, messages.size)
        assertEquals(payload.size, messages[0].length)
        assertTrue(messages[0].payloadPreview.contains("split test"))
    }

    @Test
    fun testCompressedGzipMessage() {
        val messages = mutableListOf<GrpcMessage>()
        val decoder = GrpcMessageDecoder(direction = Direction.INBOUND, isGzipEncoding = true) { messages.add(it) }

        val originalText = "compressed grpc payload content"
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzip ->
            gzip.write(originalText.toByteArray(StandardCharsets.UTF_8))
        }
        val compressed = baos.toByteArray()

        val header = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
            .put(1.toByte()) // compressed flag
            .putInt(compressed.size)
            .array()

        decoder.feed(header + compressed)

        assertEquals(1, messages.size)
        val msg = messages[0]
        assertTrue("Must be marked as compressed", msg.isCompressed)
        assertTrue("Preview should contain decompressed text", msg.payloadPreview.contains(originalText))
    }

    @Test
    fun testCanonicalStatusMapping() {
        assertEquals("OK", GrpcMessageDecoder.getStatusName(0))
        assertEquals("CANCELLED", GrpcMessageDecoder.getStatusName(1))
        assertEquals("UNKNOWN", GrpcMessageDecoder.getStatusName(2))
        assertEquals("INVALID_ARGUMENT", GrpcMessageDecoder.getStatusName(3))
        assertEquals("DEADLINE_EXCEEDED", GrpcMessageDecoder.getStatusName(4))
        assertEquals("NOT_FOUND", GrpcMessageDecoder.getStatusName(5))
        assertEquals("PERMISSION_DENIED", GrpcMessageDecoder.getStatusName(7))
        assertEquals("UNAUTHENTICATED", GrpcMessageDecoder.getStatusName(16))
        assertEquals("UNKNOWN_STATUS_99", GrpcMessageDecoder.getStatusName(99))
    }

    @Test
    fun testMalformedLengthProtection() {
        val messages = mutableListOf<GrpcMessage>()
        val decoder = GrpcMessageDecoder(direction = Direction.OUTBOUND) { messages.add(it) }

        // Send a negative length
        val badHeader = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN).put(0.toByte()).putInt(-50).array()
        decoder.feed(badHeader)

        assertTrue("Should not parse message with negative length", messages.isEmpty())
    }
}
