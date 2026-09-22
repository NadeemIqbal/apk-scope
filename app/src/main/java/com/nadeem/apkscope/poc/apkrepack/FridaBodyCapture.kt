package com.nadeem.apkscope.poc.apkrepack

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** Retain a preview prefix while counting all observed bytes, including discarded content. */
internal class FridaBodyCapture(private val limit: Int = 64 * 1024) {
    init { require(limit > 0) }

    private val retained = ByteArrayOutputStream()
    var observedBytes: Long = 0
        private set
    val isTruncated: Boolean get() = observedBytes > retained.size()

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        observedBytes += length
        val keep = minOf(length, limit - retained.size())
        if (keep > 0) retained.write(bytes, offset, keep)
    }

    fun decode(encoding: String?): DecodedBody {
        val bytes = retained.toByteArray()
        val decoded = try {
            when {
                encoding.equals("gzip", true) || bytes.startsWith(0x1f, 0x8b) ->
                    GZIPInputStream(ByteArrayInputStream(bytes)).use { readPrefix(it) }
                encoding.equals("deflate", true) ||
                    (bytes.size >= 2 && bytes[0] == 0x78.toByte() && (bytes[1].toInt() and 0xff) in setOf(0x01, 0x9c, 0xda)) ->
                    try { inflate(bytes, raw = false) } catch (_: Exception) { inflate(bytes, raw = true) }
                else -> DecodedBody(bytes, false)
            }
        } catch (_: Exception) {
            // An incomplete compressed prefix may not yet be decodable. Retain the bounded
            // original bytes for presentation instead of allocating an unbounded fallback.
            DecodedBody(bytes, false)
        }
        return decoded.copy(truncated = decoded.truncated || isTruncated)
    }

    private fun inflate(bytes: ByteArray, raw: Boolean): DecodedBody {
        val inflater = Inflater(raw)
        return try {
            InflaterInputStream(ByteArrayInputStream(bytes), inflater).use { readPrefix(it) }
        } finally {
            inflater.end()
        }
    }

    private fun readPrefix(input: InputStream): DecodedBody {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(minOf(4096, limit))
        while (output.size() < limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) return DecodedBody(output.toByteArray(), false)
            output.write(buffer, 0, count)
        }
        return DecodedBody(output.toByteArray(), input.read() != -1)
    }

    private fun ByteArray.startsWith(first: Int, second: Int): Boolean =
        size >= 2 && this[0] == first.toByte() && this[1] == second.toByte()

    data class DecodedBody(val bytes: ByteArray, val truncated: Boolean)
}
