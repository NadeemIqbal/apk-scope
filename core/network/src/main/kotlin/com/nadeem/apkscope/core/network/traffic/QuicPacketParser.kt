package com.nadeem.apkscope.core.network.traffic

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Observational, non-intrusive packet parser for QUIC transport telemetry (RFC 9000 / RFC 9369).
 *
 * Implements:
 * - Long Header vs Short Header (1-RTT) discrimination.
 * - Long Header packet type decoding: Initial, 0-RTT, Handshake, Retry, Version Negotiation.
 * - Version recognition: QUIC v1 (0x00000001), QUIC v2 (0x6b333433), draft versions, and legacy gQUIC.
 * - Variable-length Destination and Source Connection ID extraction.
 * - Grounded classification: honestly distinguishes QUIC Transport from Confirmed HTTP/3.
 */
object QuicPacketParser {

    /**
     * Attempts to parse a UDP datagram as a QUIC packet.
     * Returns null if datagram does not match QUIC packet structure.
     */
    fun parseUdpDatagram(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): QuicObservationData? {
        if (length < 5) return null

        val firstByte = bytes[offset].toInt() and 0xFF
        val isLongHeader = (firstByte and 0x80) != 0
        val isFixedBitSet = (firstByte and 0x40) != 0

        // In RFC 9000, fixed bit must be 1 for valid QUIC packets
        if (!isFixedBitSet) {
            return null
        }

        if (isLongHeader) {
            return parseLongHeader(bytes, offset, length, firstByte)
        } else {
            return parseShortHeader(bytes, offset, length, firstByte)
        }
    }

    private fun parseLongHeader(bytes: ByteArray, offset: Int, length: Int, firstByte: Int): QuicObservationData? {
        if (length < 6) return null

        val versionInt = ByteBuffer.wrap(bytes, offset + 1, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int

        val versionString = when (versionInt) {
            0x00000000 -> "Version Negotiation"
            0x00000001 -> "QUIC v1 (RFC 9000)"
            0x6b333433 -> "QUIC v2 (RFC 9369)"
            0x51303433 -> "gQUIC Q043"
            0x51303436 -> "gQUIC Q046"
            0x51303530 -> "gQUIC Q050"
            else -> {
                if ((versionInt.toLong() and 0xFF000000L) == 0xFF000000L) {
                    val draftNum = versionInt and 0xFF
                    "QUIC Draft-$draftNum"
                } else {
                    String.format("QUIC 0x%08X", versionInt)
                }
            }
        }

        val packetType = if (versionInt == 0) {
            "VERSION_NEGOTIATION"
        } else {
            when ((firstByte shr 4) and 0x03) {
                0x00 -> "INITIAL"
                0x01 -> "0-RTT"
                0x02 -> "HANDSHAKE"
                0x03 -> "RETRY"
                else -> "UNKNOWN_LONG_HEADER"
            }
        }

        var cursor = offset + 5
        if (cursor >= offset + length) return null

        // Destination Connection ID length (1 byte)
        val dcil = bytes[cursor].toInt() and 0xFF
        cursor++

        if (dcil > 20 || cursor + dcil > offset + length) {
            return null
        }

        val dcidHex = bytesToHex(bytes, cursor, dcil)
        cursor += dcil

        // Source Connection ID length (1 byte)
        if (cursor >= offset + length) {
            return QuicObservationData(
                version = versionString,
                packetType = packetType,
                destinationConnectionIdHex = dcidHex,
                sourceConnectionIdHex = null,
                isConfirmedHttp3 = false
            )
        }

        val scil = bytes[cursor].toInt() and 0xFF
        cursor++

        val scidHex = if (scil in 0..20 && cursor + scil <= offset + length) {
            bytesToHex(bytes, cursor, scil)
        } else {
            null
        }

        return QuicObservationData(
            version = versionString,
            packetType = packetType,
            destinationConnectionIdHex = dcidHex,
            sourceConnectionIdHex = scidHex,
            isConfirmedHttp3 = false // Unencrypted transport cannot confirm HTTP/3 semantics without decryption
        )
    }

    private fun parseShortHeader(bytes: ByteArray, offset: Int, length: Int, firstByte: Int): QuicObservationData? {
        // Short header has 1-RTT packet type
        // DCID length is not explicitly encoded in the short header (it was negotiated in the handshake).
        // For telemetry preview, extract up to 8 bytes as representative destination connection ID prefix
        val sampleDcidLen = minOf(8, length - 1)
        val dcidHex = if (sampleDcidLen > 0) {
            bytesToHex(bytes, offset + 1, sampleDcidLen)
        } else {
            "UNKNOWN"
        }

        return QuicObservationData(
            version = "1-RTT Connected",
            packetType = "1-RTT (Short Header)",
            destinationConnectionIdHex = dcidHex,
            sourceConnectionIdHex = null,
            isConfirmedHttp3 = false
        )
    }

    private fun bytesToHex(bytes: ByteArray, offset: Int, length: Int): String {
        if (length == 0) return "[Zero-Length CID]"
        val sb = StringBuilder()
        for (i in offset until (offset + length)) {
            sb.append(String.format("%02x", bytes[i]))
        }
        return sb.toString()
    }
}
