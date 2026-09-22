package com.nadeem.apkscope.core.staticanalysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BinaryResourceReaderTest {
    private fun pool(data: ByteArray, utf8: Boolean = true, offsets: List<Int> = listOf(0)): ByteBuffer {
        val start = 28 + offsets.size * 4
        return ByteBuffer.allocate(start + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1)
            putShort(28)
            putInt(capacity())
            putInt(offsets.size)
            putInt(0)
            putInt(if (utf8) 0x100 else 0)
            putInt(start)
            putInt(0)
            offsets.forEach { putInt(it) }
            put(data)
            rewind()
        }
    }

    @Test
    fun decodesUtf8AndReusesRepeatedOffsets() {
        val buffer = pool(byteArrayOf(1, 3) + "€".toByteArray() + byteArrayOf(0), offsets = listOf(0, 0))
        val strings = BinaryResourceReader.stringPool(buffer, 0)
        assertEquals(listOf("€", "€"), strings)
        assertSame(strings[0], strings[1])
        assertEquals(0, buffer.position())
    }

    @Test
    fun decodesUtf16() {
        val data = byteArrayOf(2, 0) + "😀".toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
        assertEquals(listOf("😀"), BinaryResourceReader.stringPool(pool(data, utf8 = false), 0))
    }

    @Test
    fun emptyPoolIsSupported() {
        assertEquals(emptyList<String>(), BinaryResourceReader.stringPool(pool(byteArrayOf(), offsets = emptyList()), 0))
        val noDataOffset = pool(byteArrayOf(), offsets = emptyList()).apply { putInt(20, 0) }
        assertEquals(emptyList<String>(), BinaryResourceReader.stringPool(noDataOffset, 0))
    }

    @Test
    fun rejectsAbsurdCountAndLengthWithoutAllocatingThem() {
        val count = pool(byteArrayOf(0, 0, 0)).apply { putInt(8, Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { BinaryResourceReader.stringPool(count, 0) }
        val length = pool(byteArrayOf(-1, -1, -1, -1, 0, 0), utf8 = false)
        assertThrows(IllegalArgumentException::class.java) { BinaryResourceReader.stringPool(length, 0) }
    }

    @Test
    fun offsetsCannotReadOutsideThePoolIntoFollowingChunk() {
        val original = pool(byteArrayOf(0, 0, 0), offsets = listOf(4)).array()
        val withTrailingData = ByteBuffer.wrap(original + ByteArray(100)).order(ByteOrder.LITTLE_ENDIAN)
        assertThrows(IllegalArgumentException::class.java) { BinaryResourceReader.stringPool(withTrailingData, 0) }
    }

    @Test
    fun decompressedEntryReadsAreBounded() {
        assertEquals(8, BinaryResourceReader.read(ByteArray(8).inputStream(), 8).size)
        assertThrows(Exception::class.java) { BinaryResourceReader.read(ByteArray(9).inputStream(), 8) }
    }
}
