package com.nadeem.apkscope.core.common

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class BoundedCopyTest {
    @Test fun exactLimitPreservesContentAcrossBuffers() {
        val bytes=ByteArray(80000) { (it % 251).toByte() }
        val output=ByteArrayOutputStream()
        assertEquals(80000L,BoundedCopy.copy(bytes.inputStream(),output,80000))
        assertArrayEquals(bytes,output.toByteArray())
    }
    @Test fun refusesOversizedProviderInputWithoutWritingPastLimit() {
        val output=ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            BoundedCopy.copy(ByteArray(80001).inputStream(),output,80000)
        }
        assertTrue(output.size() <= 80000)
    }
    @Test fun acceptsEmptyInputAtZeroLimit() {
        assertEquals(0L,BoundedCopy.copy(byteArrayOf().inputStream(),ByteArrayOutputStream(),0))
    }
    @Test fun refusesNegativeLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            BoundedCopy.copy(byteArrayOf().inputStream(),ByteArrayOutputStream(),-1)
        }
    }
    @Test fun propagatesProviderReadFailure() {
        val broken=object:InputStream() { override fun read():Int=throw IOException("Provider disconnected") }
        assertThrows(IOException::class.java) { BoundedCopy.copy(broken,ByteArrayOutputStream(),1024) }
    }
}
