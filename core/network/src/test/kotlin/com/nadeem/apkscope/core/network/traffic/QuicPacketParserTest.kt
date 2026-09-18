package com.nadeem.apkscope.core.network.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class QuicPacketParserTest {

    @Test
    fun testLongHeaderInitialQuicV1() {
        // Construct QUIC Initial packet:
        // Byte 0: 11000000 (0xC0) -> Long Header (0x80), Fixed Bit (0x40), Initial (0x00)
        // Bytes 1-4: 0x00000001 (QUIC v1)
        // Byte 5: DCIL = 8
        // Bytes 6-13: DCID = 01 02 03 04 05 06 07 08
        // Byte 14: SCIL = 4
        // Bytes 15-18: SCID = 0A 0B 0C 0D
        val buffer = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        buffer.put(0xC0.toByte())
        buffer.putInt(0x00000001)
        buffer.put(8.toByte())
        buffer.put(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.put(4.toByte())
        buffer.put(byteArrayOf(10, 11, 12, 13))

        val result = QuicPacketParser.parseUdpDatagram(buffer.array(), 0, buffer.position())

        assertNotNull("Should parse valid Initial packet", result)
        assertEquals("QUIC v1 (RFC 9000)", result!!.version)
        assertEquals("INITIAL", result.packetType)
        assertEquals("0102030405060708", result.destinationConnectionIdHex)
        assertEquals("0a0b0c0d", result.sourceConnectionIdHex)
        assertFalse("Unencrypted transport cannot confirm HTTP/3", result.isConfirmedHttp3)
    }

    @Test
    fun testLongHeaderHandshakeQuicV2() {
        // Handshake: bits 5-4 = 0x02 -> 0x80 | 0x40 | 0x20 = 0xE0
        val buffer = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
        buffer.put(0xE0.toByte())
        buffer.putInt(0x6b333433) // QUIC v2
        buffer.put(4.toByte()) // DCIL
        buffer.put(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()))
        buffer.put(0.toByte()) // SCIL = 0

        val result = QuicPacketParser.parseUdpDatagram(buffer.array(), 0, buffer.position())

        assertNotNull(result)
        assertEquals("QUIC v2 (RFC 9369)", result!!.version)
        assertEquals("HANDSHAKE", result.packetType)
        assertEquals("aabbccdd", result.destinationConnectionIdHex)
    }

    @Test
    fun testShortHeader1RttPacket() {
        // Short header: Bit 7 = 0, Fixed Bit 6 = 1 -> 0x40
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buffer.put(0x40.toByte())
        buffer.put(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) // DCID prefix

        val result = QuicPacketParser.parseUdpDatagram(buffer.array(), 0, buffer.position())

        assertNotNull(result)
        assertEquals("1-RTT Connected", result!!.version)
        assertEquals("1-RTT (Short Header)", result.packetType)
        assertEquals("0102030405060708", result.destinationConnectionIdHex)
    }

    @Test
    fun testNonQuicDatagramRejected() {
        // Fixed bit = 0 (0x00) -> invalid QUIC
        val invalid = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05)
        val result = QuicPacketParser.parseUdpDatagram(invalid)
        assertNull("Should reject datagram with fixed bit unset", result)

        // Too short (<5 bytes)
        val shortBytes = byteArrayOf(0xC0.toByte(), 0x00)
        assertNull("Should reject too short datagram", QuicPacketParser.parseUdpDatagram(shortBytes))
    }
}
