package com.nadeem.apkscope.core.network.traffic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class WebSocketFrameParserTest {

    @Test
    fun testUnmaskedTextFrame() {
        // FIN=1, Opcode=0x1 (Text), MASK=0, Len=5, Payload="Hello"
        val raw = byteArrayOf(
            0x81.toByte(), // FIN + Text
            0x05.toByte(), // Unmasked, len 5
            0x48, 0x65, 0x6C, 0x6C, 0x6F // "Hello"
        )

        val frame = WebSocketFrameParser.readFrame(ByteArrayInputStream(raw))
        assertNotNull(frame)
        assertTrue(frame!!.fin)
        assertEquals(WebSocketFrameParser.OPCODE_TEXT, frame.opcode)
        assertFalse(frame.isMasked)
        assertEquals("Hello", String(frame.unmaskedPayload, Charsets.UTF_8))

        // Check serialization matches
        val wire = WebSocketFrameParser.serializeFrame(frame)
        assertArrayEquals(raw, wire)
    }

    @Test
    fun testMaskedClientTextFrame() {
        // Masked "Hello" with key 0x37, 0xfa, 0x21, 0x3d
        // 'H' (0x48) xor 0x37 = 0x7f
        // 'e' (0x65) xor 0xfa = 0x9f
        // 'l' (0x6c) xor 0x21 = 0x4d
        // 'l' (0x6c) xor 0x3d = 0x51
        // 'o' (0x6f) xor 0x37 = 0x58
        val raw = byteArrayOf(
            0x81.toByte(), // FIN + Text
            0x85.toByte(), // Masked, len 5
            0x37, 0xfa.toByte(), 0x21, 0x3d, // Key
            0x7f, 0x9f.toByte(), 0x4d, 0x51, 0x58 // Masked payload
        )

        val frame = WebSocketFrameParser.readFrame(ByteArrayInputStream(raw))
        assertNotNull(frame)
        assertTrue(frame!!.fin)
        assertTrue(frame.isMasked)
        assertEquals("Hello", String(frame.unmaskedPayload, Charsets.UTF_8))

        // Check round-trip serialization
        val wire = WebSocketFrameParser.serializeFrame(frame)
        val reparsed = WebSocketFrameParser.readFrame(ByteArrayInputStream(wire))
        assertEquals("Hello", String(reparsed!!.unmaskedPayload, Charsets.UTF_8))
    }

    @Test
    fun testPingPongAndCloseFrames() {
        // Ping frame with payload "ping"
        val pingRaw = byteArrayOf(
            0x89.toByte(), // FIN + Ping
            0x04.toByte(),
            'p'.code.toByte(), 'i'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte()
        )
        val pingFrame = WebSocketFrameParser.readFrame(ByteArrayInputStream(pingRaw))
        assertNotNull(pingFrame)
        assertTrue(pingFrame!!.isControl)
        assertEquals(WebSocketFrameParser.OPCODE_PING, pingFrame.opcode)
        assertEquals("ping", String(pingFrame.unmaskedPayload, Charsets.UTF_8))

        // Close frame with code 1000 (Normal Closure) and reason "bye"
        val closeRaw = byteArrayOf(
            0x88.toByte(), // FIN + Close
            0x05.toByte(),
            0x03.toByte(), 0xE8.toByte(), // 1000
            'b'.code.toByte(), 'y'.code.toByte(), 'e'.code.toByte()
        )
        val closeFrame = WebSocketFrameParser.readFrame(ByteArrayInputStream(closeRaw))
        assertNotNull(closeFrame)
        assertTrue(closeFrame!!.isControl)
        assertEquals(WebSocketFrameParser.OPCODE_CLOSE, closeFrame.opcode)

        val (code, reason) = WebSocketFrameParser.parseClosePayload(closeFrame.unmaskedPayload)
        assertEquals(1000, code)
        assertEquals("bye", reason)
    }

    @Test
    fun testMediumPayloadLength16Bit() {
        val payload = ByteArray(300) { (it % 256).toByte() }
        val out = ByteArrayOutputStream()
        out.write(0x82) // FIN + Binary
        out.write(126) // 16-bit length indicator
        out.write((300 shr 8) and 0xFF)
        out.write(300 and 0xFF)
        out.write(payload)

        val frame = WebSocketFrameParser.readFrame(ByteArrayInputStream(out.toByteArray()))
        assertNotNull(frame)
        assertEquals(WebSocketFrameParser.OPCODE_BINARY, frame!!.opcode)
        assertEquals(300, frame.unmaskedPayload.size)
        assertArrayEquals(payload, frame.unmaskedPayload)
    }

    @Test
    fun testPermessageDeflateDecompression() {
        val text = "The quick brown fox jumps over the lazy dog. The quick brown fox jumps over the lazy dog."
        val textBytes = text.toByteArray(Charsets.UTF_8)

        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true) // nowrap=true
        deflater.setInput(textBytes)
        deflater.finish()
        val compBuf = ByteArray(256)
        val compLen = deflater.deflate(compBuf)
        deflater.end()

        val compressed = compBuf.copyOfRange(0, compLen)
        val decompressed = WebSocketFrameParser.inflatePermessageDeflate(compressed)
        assertEquals(text, String(decompressed, Charsets.UTF_8))
    }

    @Test
    fun testFragmentedTextDefragmentationWithInterleavedPing() {
        val reconstructor = WebSocketFrameParser.BoundedMessageReconstructor()

        // Fragment 1: FIN=0, Opcode=TEXT, "Part 1: "
        val f1 = RawWebSocketFrame(
            fin = false,
            rsv1 = false,
            rsv2 = false,
            rsv3 = false,
            opcode = WebSocketFrameParser.OPCODE_TEXT,
            isMasked = false,
            maskingKey = null,
            unmaskedPayload = "Part 1: ".toByteArray(Charsets.UTF_8)
        )
        val res1 = reconstructor.processFrame(f1)
        org.junit.Assert.assertNull("Fragment 1 should not complete message", res1)

        // Interleaved Control Frame: Ping (FIN=1, Opcode=PING)
        val ping = RawWebSocketFrame(
            fin = true,
            rsv1 = false,
            rsv2 = false,
            rsv3 = false,
            opcode = WebSocketFrameParser.OPCODE_PING,
            isMasked = false,
            maskingKey = null,
            unmaskedPayload = "heartbeat".toByteArray(Charsets.UTF_8)
        )
        val pingRes = reconstructor.processFrame(ping)
        org.junit.Assert.assertNotNull("Ping should be processed immediately", pingRes)
        assertEquals(MessageType.PING, pingRes!!.type)
        assertEquals("heartbeat", String(pingRes.payload, Charsets.UTF_8))

        // Fragment 2: FIN=1, Opcode=CONTINUATION, "Part 2"
        val f2 = RawWebSocketFrame(
            fin = true,
            rsv1 = false,
            rsv2 = false,
            rsv3 = false,
            opcode = WebSocketFrameParser.OPCODE_CONTINUATION,
            isMasked = false,
            maskingKey = null,
            unmaskedPayload = "Part 2".toByteArray(Charsets.UTF_8)
        )
        val res2 = reconstructor.processFrame(f2)
        org.junit.Assert.assertNotNull("Fragment 2 should complete message", res2)
        assertEquals(MessageType.TEXT, res2!!.type)
        assertTrue(res2.isFragmented)
        assertEquals(2, res2.fragmentCount)
        assertEquals("Part 1: Part 2", String(res2.payload, Charsets.UTF_8))
    }

    @Test
    fun testFragmentedBinaryDefragmentation() {
        val reconstructor = WebSocketFrameParser.BoundedMessageReconstructor()

        val part1 = byteArrayOf(1, 2, 3)
        val part2 = byteArrayOf(4, 5, 6, 7)

        val f1 = RawWebSocketFrame(
            fin = false,
            rsv1 = false,
            rsv2 = false,
            rsv3 = false,
            opcode = WebSocketFrameParser.OPCODE_BINARY,
            isMasked = false,
            maskingKey = null,
            unmaskedPayload = part1
        )
        assertNull(reconstructor.processFrame(f1))

        val f2 = RawWebSocketFrame(
            fin = true,
            rsv1 = false,
            rsv2 = false,
            rsv3 = false,
            opcode = WebSocketFrameParser.OPCODE_CONTINUATION,
            isMasked = false,
            maskingKey = null,
            unmaskedPayload = part2
        )
        val res = reconstructor.processFrame(f2)
        assertNotNull(res)
        assertEquals(MessageType.BINARY, res!!.type)
        assertTrue(res.isFragmented)
        assertEquals(2, res.fragmentCount)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7), res.payload)
    }

    private fun assertNull(actual: Any?) {
        org.junit.Assert.assertNull(actual)
    }
}
