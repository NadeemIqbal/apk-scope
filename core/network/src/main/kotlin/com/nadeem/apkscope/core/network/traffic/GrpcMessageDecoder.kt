package com.nadeem.apkscope.core.network.traffic

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.zip.GZIPInputStream

/**
 * Decoder for gRPC length-prefixed framing, status codes, and message payload previews.
 *
 * Implements:
 * - 5-byte length prefix: 1-byte compression flag + 4-byte big-endian length.
 * - Incremental frame reassembly across HTTP/2 DATA frames.
 * - Direction-aware message sequence tracking.
 * - Bounded gzip decompression when requested.
 * - Grounded binary hex/ASCII preview without fabricating ungrounded protobuf schemas.
 * - Canonical gRPC status code mapping (0..16).
 */
class GrpcMessageDecoder(
    private val direction: Direction,
    private val isGzipEncoding: Boolean = false,
    private val maxPreviewBytes: Int = 1024,
    private val maxDecompressedBytes: Int = 64 * 1024,
    private val onMessageDecoded: (GrpcMessage) -> Unit
) {
    companion object {
        val STATUS_NAMES = mapOf(
            0 to "OK",
            1 to "CANCELLED",
            2 to "UNKNOWN",
            3 to "INVALID_ARGUMENT",
            4 to "DEADLINE_EXCEEDED",
            5 to "NOT_FOUND",
            6 to "ALREADY_EXISTS",
            7 to "PERMISSION_DENIED",
            8 to "RESOURCE_EXHAUSTED",
            9 to "FAILED_PRECONDITION",
            10 to "ABORTED",
            11 to "OUT_OF_RANGE",
            12 to "UNIMPLEMENTED",
            13 to "INTERNAL",
            14 to "UNAVAILABLE",
            15 to "DATA_LOSS",
            16 to "UNAUTHENTICATED"
        )

        fun getStatusName(statusCode: Int): String {
            return STATUS_NAMES[statusCode] ?: "UNKNOWN_STATUS_$statusCode"
        }

        fun isGrpcContentType(contentType: String?): Boolean {
            if (contentType == null) return false
            val lower = contentType.lowercase().trim()
            return lower.startsWith("application/grpc")
        }
    }

    private val accumulator = ByteArrayOutputStream()
    private var sequenceNumber = 0

    @Synchronized
    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        accumulator.write(data, offset, length)
        processAccumulator()
    }

    private fun processAccumulator() {
        var currentBytes = accumulator.toByteArray()

        while (currentBytes.size >= 5) {
            val isCompressed = currentBytes[0] != 0.toByte()
            val messageLength = ByteBuffer.wrap(currentBytes, 1, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .int

            if (messageLength < 0 || messageLength > 16 * 1024 * 1024) {
                // Malformed or unreasonably large message length; stop parsing to prevent OOM
                break
            }

            val totalNeeded = 5 + messageLength
            if (currentBytes.size < totalNeeded) {
                // Incomplete message, wait for more data frames
                break
            }

            // Complete message payload available
            val payloadBytes = ByteArray(messageLength)
            System.arraycopy(currentBytes, 5, payloadBytes, 0, messageLength)

            val (finalPayload, decompressedSuccessfully) = if (isCompressed || isGzipEncoding) {
                decompressGzip(payloadBytes)
            } else {
                payloadBytes to false
            }

            val preview = formatPayloadPreview(finalPayload, isCompressed = isCompressed || decompressedSuccessfully)

            sequenceNumber++
            val grpcMessage = GrpcMessage(
                sequence = sequenceNumber,
                direction = direction,
                timestamp = Instant.now(),
                isCompressed = isCompressed || decompressedSuccessfully,
                length = messageLength,
                payloadPreview = preview,
                isBinary = true
            )

            onMessageDecoded(grpcMessage)

            // Shift remaining bytes
            val remaining = currentBytes.size - totalNeeded
            val nextBytes = ByteArray(remaining)
            if (remaining > 0) {
                System.arraycopy(currentBytes, totalNeeded, nextBytes, 0, remaining)
            }
            currentBytes = nextBytes
            accumulator.reset()
            accumulator.write(currentBytes)
        }
    }

    private fun decompressGzip(compressed: ByteArray): Pair<ByteArray, Boolean> {
        return try {
            GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(2048)
                var total = 0
                var read: Int
                while (gzip.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    total += read
                    if (total >= maxDecompressedBytes) break
                }
                out.toByteArray() to true
            }
        } catch (_: Exception) {
            compressed to false
        }
    }

    private fun formatPayloadPreview(bytes: ByteArray, isCompressed: Boolean): String {
        if (bytes.isEmpty()) return "[Empty Message]"

        // Try to see if it's printable ASCII or JSON
        var printableCount = 0
        val sampleSize = minOf(bytes.size, 128)
        for (i in 0 until sampleSize) {
            val c = bytes[i].toInt() and 0xFF
            if (c in 32..126 || c == 9 || c == 10 || c == 13) {
                printableCount++
            }
        }

        val isMostlyText = printableCount.toDouble() / sampleSize > 0.85
        if (isMostlyText) {
            val text = String(bytes, 0, minOf(bytes.size, maxPreviewBytes), StandardCharsets.UTF_8)
            val suffix = if (bytes.size > maxPreviewBytes) "... [Truncated]" else ""
            return text + suffix
        }

        // Binary Protobuf preview: render length badge and hex bytes
        val hexLimit = minOf(bytes.size, maxPreviewBytes)
        val sb = StringBuilder()
        sb.append("[Protobuf Binary: ").append(bytes.size).append(" bytes")
        if (isCompressed) sb.append(", decompressed")
        sb.append("] ")

        for (i in 0 until hexLimit) {
            if (i > 0 && i % 16 == 0) sb.append("\n  ")
            sb.append(String.format("%02X ", bytes[i]))
        }
        if (bytes.size > maxPreviewBytes) {
            sb.append("... [").append(bytes.size - maxPreviewBytes).append(" more bytes]")
        }
        return sb.toString()
    }
}
