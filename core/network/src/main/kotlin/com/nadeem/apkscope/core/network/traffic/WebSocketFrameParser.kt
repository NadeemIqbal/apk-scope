package com.nadeem.apkscope.core.network.traffic

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.time.Instant
import java.util.zip.Inflater

/**
 * Parsed raw WebSocket frame according to RFC 6455.
 */
data class RawWebSocketFrame(
    val fin: Boolean,
    val rsv1: Boolean,
    val rsv2: Boolean,
    val rsv3: Boolean,
    val opcode: Int,
    val isMasked: Boolean,
    val maskingKey: ByteArray?,
    val unmaskedPayload: ByteArray
) {
    val isControl: Boolean get() = opcode >= 0x08

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RawWebSocketFrame
        if (fin != other.fin) return false
        if (rsv1 != other.rsv1) return false
        if (opcode != other.opcode) return false
        if (isMasked != other.isMasked) return false
        if (!unmaskedPayload.contentEquals(other.unmaskedPayload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = fin.hashCode()
        result = 31 * result + rsv1.hashCode()
        result = 31 * result + opcode
        result = 31 * result + isMasked.hashCode()
        result = 31 * result + unmaskedPayload.contentHashCode()
        return result
    }
}

/**
 * Utility for parsing and serializing RFC 6455 WebSocket frames and defragmenting messages.
 */
object WebSocketFrameParser {

    const val OPCODE_CONTINUATION = 0x00
    const val OPCODE_TEXT = 0x01
    const val OPCODE_BINARY = 0x02
    const val OPCODE_CLOSE = 0x08
    const val OPCODE_PING = 0x09
    const val OPCODE_PONG = 0x0A

    /**
     * Reads one complete WebSocket frame from an InputStream.
     * Returns null if stream ended before a complete frame header could be read.
     */
    fun readFrame(inStream: InputStream): RawWebSocketFrame? {
        val b0 = inStream.read()
        if (b0 < 0) return null
        val b1 = inStream.read()
        if (b1 < 0) return null

        val fin = (b0 and 0x80) != 0
        val rsv1 = (b0 and 0x40) != 0
        val rsv2 = (b0 and 0x20) != 0
        val rsv3 = (b0 and 0x10) != 0
        val opcode = b0 and 0x0F

        val isMasked = (b1 and 0x80) != 0
        val lenField = b1 and 0x7F

        val payloadLength: Long = when (lenField) {
            126 -> {
                val b2 = inStream.read()
                val b3 = inStream.read()
                if (b2 < 0 || b3 < 0) return null
                ((b2 shl 8) or b3).toLong()
            }
            127 -> {
                var len = 0L
                for (i in 0 until 8) {
                    val b = inStream.read()
                    if (b < 0) return null
                    len = (len shl 8) or (b and 0xFF).toLong()
                }
                len
            }
            else -> lenField.toLong()
        }

        val maskingKey = if (isMasked) {
            val key = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = inStream.read(key, read, 4 - read)
                if (n < 0) return null
                read += n
            }
            key
        } else null

        // Cap individual read payload preview to max 1 MiB to prevent memory exhaustion, while forwarding faithfully
        val safeLen = minOf(payloadLength, 1024L * 1024L).toInt()
        val payload = ByteArray(safeLen)
        var read = 0
        while (read < safeLen) {
            val n = inStream.read(payload, read, safeLen - read)
            if (n < 0) return null
            read += n
        }

        // Skip any remaining bytes if payload > 1 MiB
        if (payloadLength > safeLen) {
            var remaining = payloadLength - safeLen
            while (remaining > 0) {
                val skipped = inStream.skip(remaining)
                if (skipped <= 0) break
                remaining -= skipped
            }
        }

        // Unmask if masked
        if (isMasked && maskingKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskingKey[i % 4].toInt()).toByte()
            }
        }

        return RawWebSocketFrame(
            fin = fin,
            rsv1 = rsv1,
            rsv2 = rsv2,
            rsv3 = rsv3,
            opcode = opcode,
            isMasked = isMasked,
            maskingKey = maskingKey,
            unmaskedPayload = payload
        )
    }

    /**
     * Serializes a RawWebSocketFrame back into wire format for transparent relay.
     */
    fun serializeFrame(frame: RawWebSocketFrame): ByteArray {
        val out = ByteArrayOutputStream()
        var b0 = frame.opcode and 0x0F
        if (frame.fin) b0 = b0 or 0x80
        if (frame.rsv1) b0 = b0 or 0x40
        if (frame.rsv2) b0 = b0 or 0x20
        if (frame.rsv3) b0 = b0 or 0x10
        out.write(b0)

        val len = frame.unmaskedPayload.size
        var b1 = if (frame.isMasked) 0x80 else 0x00
        when {
            len <= 125 -> {
                b1 = b1 or len
                out.write(b1)
            }
            len <= 65535 -> {
                b1 = b1 or 126
                out.write(b1)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                b1 = b1 or 127
                out.write(b1)
                for (i in 7 downTo 0) {
                    out.write(((len.toLong() shr (i * 8)) and 0xFF).toInt())
                }
            }
        }

        if (frame.isMasked && frame.maskingKey != null) {
            out.write(frame.maskingKey)
            // Re-mask payload before writing
            val masked = ByteArray(frame.unmaskedPayload.size)
            for (i in frame.unmaskedPayload.indices) {
                masked[i] = (frame.unmaskedPayload[i].toInt() xor frame.maskingKey[i % 4].toInt()).toByte()
            }
            out.write(masked)
        } else {
            out.write(frame.unmaskedPayload)
        }

        return out.toByteArray()
    }

    /**
     * Decompresses permessage-deflate payload (RFC 7692).
     */
    fun inflatePermessageDeflate(compressed: ByteArray, maxOutputBytes: Int = 64 * 1024): ByteArray {
        val inflater = Inflater(true)
        // RFC 7692 specifies trailing 0x00, 0x00, 0xFF, 0xFF was stripped
        val input = if (compressed.size >= 4 &&
            compressed[compressed.size - 4] == 0x00.toByte() &&
            compressed[compressed.size - 3] == 0x00.toByte() &&
            compressed[compressed.size - 2] == 0xFF.toByte() &&
            compressed[compressed.size - 1] == 0xFF.toByte()
        ) {
            compressed
        } else {
            compressed + byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte())
        }

        inflater.setInput(input)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var total = 0
        try {
            while (!inflater.finished() && total < maxOutputBytes) {
                val count = inflater.inflate(buf)
                if (count <= 0) {
                    if (inflater.needsInput()) break
                    break
                }
                out.write(buf, 0, count)
                total += count
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    /**
     * Extracts close code and reason phrase from Close frame payload.
     */
    fun parseClosePayload(payload: ByteArray): Pair<Int?, String?> {
        if (payload.size < 2) return null to null
        val code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val reason = if (payload.size > 2) {
            try {
                String(payload, 2, payload.size - 2, Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        } else null
        return code to reason
    }

    /**
     * Reconstructed logical WebSocket message defragmented across continuation frames.
     */
    data class ReconstructedMessage(
        val type: MessageType,
        val payload: ByteArray,
        val isDeflated: Boolean,
        val isFragmented: Boolean,
        val fragmentCount: Int,
        val isTruncated: Boolean = false
    )

    /**
     * Per-direction bounded message reconstructor according to RFC 6455.
     * Interleaved control frames (Ping, Pong, Close) do NOT break fragmentation sequence.
     */
    class BoundedMessageReconstructor(
        val maxBufferSize: Int = 1024 * 1024 // 1 MiB preview limit
    ) {
        private var currentOpcode: Int? = null
        private var isDeflated: Boolean = false
        private val buffer = ByteArrayOutputStream()
        private var fragmentCount: Int = 0
        private var isTruncated: Boolean = false

        /**
         * Feeds a parsed frame into the reconstructor.
         * Returns a [ReconstructedMessage] if this frame completes a message (or is an unfragmented message or control frame).
         * Returns null if more continuation frames are required.
         */
        @Synchronized
        fun processFrame(frame: RawWebSocketFrame): ReconstructedMessage? {
            // Control frames are never fragmented and may be interleaved between fragments
            if (frame.isControl) {
                val type = when (frame.opcode) {
                    OPCODE_CLOSE -> MessageType.CLOSE
                    OPCODE_PING -> MessageType.PING
                    OPCODE_PONG -> MessageType.PONG
                    else -> MessageType.TEXT
                }
                return ReconstructedMessage(
                    type = type,
                    payload = frame.unmaskedPayload,
                    isDeflated = false,
                    isFragmented = false,
                    fragmentCount = 1
                )
            }

            if (frame.opcode != OPCODE_CONTINUATION) {
                // Initial data frame
                currentOpcode = frame.opcode
                isDeflated = frame.rsv1
                buffer.reset()
                fragmentCount = 1
                isTruncated = false

                appendPayload(frame.unmaskedPayload)

                if (frame.fin) {
                    // Unfragmented data frame
                    val type = if (currentOpcode == OPCODE_BINARY) MessageType.BINARY else MessageType.TEXT
                    val payload = buffer.toByteArray()
                    resetState()
                    return ReconstructedMessage(
                        type = type,
                        payload = payload,
                        isDeflated = isDeflated,
                        isFragmented = false,
                        fragmentCount = 1,
                        isTruncated = isTruncated
                    )
                }
                return null
            } else {
                // Continuation frame
                if (currentOpcode == null) {
                    // Orphan continuation frame: treat gracefully as binary
                    return ReconstructedMessage(
                        type = MessageType.BINARY,
                        payload = frame.unmaskedPayload,
                        isDeflated = false,
                        isFragmented = true,
                        fragmentCount = 1
                    )
                }
                fragmentCount++
                appendPayload(frame.unmaskedPayload)

                if (frame.fin) {
                    // Final continuation frame
                    val type = if (currentOpcode == OPCODE_BINARY) MessageType.BINARY else MessageType.TEXT
                    val payload = buffer.toByteArray()
                    val totalFragments = fragmentCount
                    val wasTruncated = isTruncated
                    val deflated = isDeflated
                    resetState()
                    return ReconstructedMessage(
                        type = type,
                        payload = payload,
                        isDeflated = deflated,
                        isFragmented = true,
                        fragmentCount = totalFragments,
                        isTruncated = wasTruncated
                    )
                }
                return null
            }
        }

        private fun appendPayload(bytes: ByteArray) {
            val remaining = maxBufferSize - buffer.size()
            if (remaining > 0) {
                val toWrite = minOf(remaining, bytes.size)
                buffer.write(bytes, 0, toWrite)
                if (toWrite < bytes.size) {
                    isTruncated = true
                }
            } else {
                isTruncated = true
            }
        }

        private fun resetState() {
            currentOpcode = null
            isDeflated = false
            buffer.reset()
            fragmentCount = 0
            isTruncated = false
        }
    }
}
