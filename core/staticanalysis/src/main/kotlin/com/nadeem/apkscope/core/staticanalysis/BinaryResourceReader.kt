package com.nadeem.apkscope.core.staticanalysis

import com.nadeem.apkscope.core.common.BoundedCopy
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Shared allocation limits for untrusted, decompressed Android XML/resource entries. */
internal object BinaryResourceReader {
    const val MAX_XML_BYTES = 8 * 1024 * 1024
    const val MAX_TABLE_BYTES = 32 * 1024 * 1024
    private const val MAX_STRINGS = 250_000
    private const val MAX_DECODED_CHARS = 8 * 1024 * 1024

    fun read(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        BoundedCopy.copy(input, output, limit.toLong())
        return output.toByteArray()
    }

    /** AXML and resources.arsc use the same ResStringPool layout. */
    fun stringPool(source: ByteBuffer, start: Int): List<String> {
        val buffer = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        require(start >= 0 && start <= buffer.limit() - 28) { "Truncated string pool header" }
        val headerSize = buffer.getShort(start + 2).toInt() and 0xffff
        val size = buffer.getInt(start + 4)
        require(headerSize >= 28 && size >= headerSize && size <= buffer.limit() - start) {
            "Invalid string pool size"
        }
        val count = buffer.getInt(start + 8)
        val styles = buffer.getInt(start + 12)
        val utf8 = buffer.getInt(start + 16) and 0x100 != 0
        val stringsStart = buffer.getInt(start + 20)
        val stylesStart = buffer.getInt(start + 24)
        require(count in 0..MAX_STRINGS && styles >= 0) { "String pool entry limit exceeded" }
        if (count == 0 && styles == 0 && stringsStart == 0 && stylesStart == 0) return emptyList()
        require(stringsStart in headerSize..size &&
            (count.toLong() + styles) * 4 <= stringsStart - headerSize) { "Invalid string offset table" }
        val dataEnd = if (stylesStart == 0) size else stylesStart
        require(dataEnd in stringsStart..size) { "Invalid string pool data bounds" }
        buffer.limit(start + dataEnd)

        val strings = ArrayList<String>(count)
        val decodedOffsets = HashMap<Int, String>()
        var remainingChars = MAX_DECODED_CHARS
        repeat(count) { index ->
            val offset = buffer.getInt(start + headerSize + index * 4)
            require(offset >= 0 && offset < dataEnd - stringsStart) { "String offset outside pool" }
            val value = decodedOffsets.getOrPut(offset) {
                buffer.position(start + stringsStart + offset)
                if (utf8) {
                    length8(buffer) // UTF-16 length; bytes below determine decoding.
                    val length = length8(buffer)
                    require(length <= remainingChars && length < buffer.remaining()) { "String length exceeds budget" }
                    val bytes = ByteArray(length)
                    buffer.get(bytes)
                    require(buffer.get() == 0.toByte()) { "Unterminated UTF-8 string" }
                    String(bytes, Charsets.UTF_8)
                } else {
                    val length = length16(buffer)
                    require(length <= remainingChars && length <= (buffer.remaining() - 2) / 2) {
                        "String length exceeds budget"
                    }
                    val chars = CharArray(length) { buffer.short.toInt().toChar() }
                    require(buffer.short == 0.toShort()) { "Unterminated UTF-16 string" }
                    String(chars)
                }.also { remainingChars -= it.length }
            }
            strings.add(value)
        }
        return strings
    }

    private fun length8(buffer: ByteBuffer): Int {
        val first = buffer.get().toInt() and 0xff
        return if (first and 0x80 == 0) first
        else ((first and 0x7f) shl 8) or (buffer.get().toInt() and 0xff)
    }

    private fun length16(buffer: ByteBuffer): Int {
        val first = buffer.short.toInt() and 0xffff
        return if (first and 0x8000 == 0) first
        else ((first and 0x7fff) shl 16) or (buffer.short.toInt() and 0xffff)
    }
}
