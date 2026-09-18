package com.nadeem.apkscope.core.network.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Deflater

class HttpFramingTest {

    @Test
    fun testChunkedTransferUnchunking() {
        val rawChunked = "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n"
        val inStream = ByteArrayInputStream(rawChunked.toByteArray(Charsets.ISO_8859_1))

        val out = ByteArrayOutputStream()
        val reader = inStream.bufferedReader()
        while (true) {
            val line = reader.readLine() ?: break
            val size = line.split(";")[0].trim().toIntOrNull(16) ?: 0
            if (size <= 0) break
            val buf = CharArray(size)
            var read = 0
            while (read < size) {
                val n = reader.read(buf, read, size - read)
                if (n < 0) break
                read += n
            }
            out.write(String(buf, 0, read).toByteArray(Charsets.ISO_8859_1))
            reader.readLine() // CRLF
        }

        val unchunked = String(out.toByteArray(), Charsets.ISO_8859_1)
        assertEquals("Wikipedia", unchunked)
    }

    @Test
    fun testGzipDecompression() {
        val original = "Sensitive payload or normal API JSON response to decompress"
        val gzOut = ByteArrayOutputStream()
        GZIPOutputStream(gzOut).use { it.write(original.toByteArray(Charsets.UTF_8)) }
        val compressed = gzOut.toByteArray()

        val decompressed = java.util.zip.GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader().readText()
        assertEquals(original, decompressed)
    }

    @Test
    fun testDeflateDecompression() {
        val original = "Deflated HTTP payload text preview test"
        val textBytes = original.toByteArray(Charsets.UTF_8)
        val deflater = Deflater()
        deflater.setInput(textBytes)
        deflater.finish()
        val compBuf = ByteArray(256)
        val compLen = deflater.deflate(compBuf)
        deflater.end()

        val inflater = java.util.zip.Inflater()
        inflater.setInput(compBuf, 0, compLen)
        val outBuf = ByteArray(256)
        val outLen = inflater.inflate(outBuf)
        inflater.end()

        val decompressed = String(outBuf, 0, outLen, Charsets.UTF_8)
        assertEquals(original, decompressed)
    }

    @Test
    fun testResponsesWithoutBodies() {
        // RFC 7230 §3.3: 204, 304, and HEAD responses must not have bodies regardless of headers
        fun isNoBody(method: String, statusCode: Int): Boolean {
            return method.equals("HEAD", ignoreCase = true) ||
                    statusCode == 204 || statusCode == 304 || (statusCode in 100..199)
        }

        assertTrue(isNoBody("HEAD", 200))
        assertTrue(isNoBody("GET", 204))
        assertTrue(isNoBody("GET", 304))
        assertTrue(isNoBody("POST", 100))
        assertTrue(isNoBody("GET", 101))
        org.junit.Assert.assertFalse(isNoBody("GET", 200))
        org.junit.Assert.assertFalse(isNoBody("POST", 201))
        org.junit.Assert.assertFalse(isNoBody("GET", 404))
    }
}
