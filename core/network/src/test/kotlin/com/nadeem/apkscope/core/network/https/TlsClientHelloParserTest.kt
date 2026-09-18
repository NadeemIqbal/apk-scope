package com.nadeem.apkscope.core.network.https

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class TlsClientHelloParserTest {

 private fun buildClientHello(hostname: String?): ByteArray {
  val extBytes = ByteArrayOutputStream()
  if (hostname != null) {
   val hostBytes = hostname.toByteArray(Charsets.US_ASCII)
   val sniEntry = ByteArrayOutputStream().apply {
    write(0x00) // host_name
    write((hostBytes.size shr 8) and 0xFF)
    write(hostBytes.size and 0xFF)
    write(hostBytes)
   }.toByteArray()

   extBytes.write(0x00) // ext type 0x0000 (server_name)
   extBytes.write(0x00)
   val extDataLen = sniEntry.size + 2
   extBytes.write((extDataLen shr 8) and 0xFF)
   extBytes.write(extDataLen and 0xFF)
   extBytes.write((sniEntry.size shr 8) and 0xFF)
   extBytes.write(sniEntry.size and 0xFF)
   extBytes.write(sniEntry)
  }

  val handshakeBody = ByteArrayOutputStream().apply {
   write(0x03); write(0x03) // TLS 1.2 client version
   write(ByteArray(32)) // random
   write(0x00) // session id len = 0
   write(0x00); write(0x02); write(0x13); write(0x01) // cipher suites
   write(0x01); write(0x00) // compression methods
   val exts = extBytes.toByteArray()
   write((exts.size shr 8) and 0xFF)
   write(exts.size and 0xFF)
   write(exts)
  }.toByteArray()

  val handshakeMsg = ByteArrayOutputStream().apply {
   write(0x01) // ClientHello
   write((handshakeBody.size shr 16) and 0xFF)
   write((handshakeBody.size shr 8) and 0xFF)
   write(handshakeBody.size and 0xFF)
   write(handshakeBody)
  }.toByteArray()

  return ByteArrayOutputStream().apply {
   write(0x16) // Handshake record
   write(0x03); write(0x01) // TLS 1.0 record version
   write((handshakeMsg.size shr 8) and 0xFF)
   write(handshakeMsg.size and 0xFF)
   write(handshakeMsg)
  }.toByteArray()
 }

 @Test
 fun testExtractSniFromValidClientHello() {
  val packet = buildClientHello("httpbin.org")
  val host = TlsClientHelloParser.extractSni(packet)
  assertEquals("httpbin.org", host)
 }

 @Test
 fun testExtractSniSubdomain() {
  val packet = buildClientHello("jsonplaceholder.typicode.com")
  val host = TlsClientHelloParser.extractSni(packet)
  assertEquals("jsonplaceholder.typicode.com", host)
 }

 @Test
 fun testNullWhenNoSni() {
  val packet = buildClientHello(null)
  val host = TlsClientHelloParser.extractSni(packet)
  assertNull(host)
 }

 @Test
 fun testNullOnNonTlsOrMalformed() {
  assertNull(TlsClientHelloParser.extractSni(ByteArray(0)))
  assertNull(TlsClientHelloParser.extractSni(byteArrayOf(0x47, 0x45, 0x54, 0x20))) // "GET "
  assertNull(TlsClientHelloParser.extractSni(ByteArray(10) { 0xFF.toByte() }))
 }

 @Test
 fun testRecordCompletenessAndFragmentation() {
  val packet = buildClientHello("example.com")
  org.junit.Assert.assertTrue("Full packet should be a complete record", TlsClientHelloParser.isRecordComplete(packet))

  val firstHalf = packet.copyOfRange(0, packet.size / 2)
  org.junit.Assert.assertFalse("Truncated packet should not be complete", TlsClientHelloParser.isRecordComplete(firstHalf))

  val nonTls = "GET /index.html HTTP/1.1\r\n".toByteArray()
  org.junit.Assert.assertTrue("Non-TLS payload should return complete to unblock forwarding", TlsClientHelloParser.isRecordComplete(nonTls))
 }

 @Test
 fun testLargeClientHelloWithPostQuantumExtension() {
  // Build a ClientHello with a 2500-byte dummy extension simulating Kyber/ML-KEM key shares
  val extBytes = ByteArrayOutputStream()

  // 1. SNI extension
  val hostBytes = "secure.service.example.org".toByteArray(Charsets.US_ASCII)
  val sniEntry = ByteArrayOutputStream().apply {
   write(0x00) // host_name
   write((hostBytes.size shr 8) and 0xFF)
   write(hostBytes.size and 0xFF)
   write(hostBytes)
  }.toByteArray()
  extBytes.write(0x00); extBytes.write(0x00) // server_name
  val extDataLen = sniEntry.size + 2
  extBytes.write((extDataLen shr 8) and 0xFF); extBytes.write(extDataLen and 0xFF)
  extBytes.write((sniEntry.size shr 8) and 0xFF); extBytes.write(sniEntry.size and 0xFF)
  extBytes.write(sniEntry)

  // 2. Large dummy extension (e.g. key_share with post-quantum Kyber768/ML-KEM)
  val pqData = ByteArray(2500) { (it % 256).toByte() }
  extBytes.write(0x00); extBytes.write(0x33) // key_share ext type 0x0033
  extBytes.write((pqData.size shr 8) and 0xFF); extBytes.write(pqData.size and 0xFF)
  extBytes.write(pqData)

  val handshakeBody = ByteArrayOutputStream().apply {
   write(0x03); write(0x03)
   write(ByteArray(32))
   write(0x00)
   write(0x00); write(0x02); write(0x13); write(0x01)
   write(0x01); write(0x00)
   val exts = extBytes.toByteArray()
   write((exts.size shr 8) and 0xFF)
   write(exts.size and 0xFF)
   write(exts)
  }.toByteArray()

  val handshakeMsg = ByteArrayOutputStream().apply {
   write(0x01)
   write((handshakeBody.size shr 16) and 0xFF)
   write((handshakeBody.size shr 8) and 0xFF)
   write(handshakeBody.size and 0xFF)
   write(handshakeBody)
  }.toByteArray()

  val fullRecord = ByteArrayOutputStream().apply {
   write(0x16)
   write(0x03); write(0x01)
   write((handshakeMsg.size shr 8) and 0xFF)
   write(handshakeMsg.size and 0xFF)
   write(handshakeMsg)
  }.toByteArray()

  org.junit.Assert.assertTrue("Record must exceed 2500 bytes", fullRecord.size > 2500)
  org.junit.Assert.assertTrue("Record must be complete", TlsClientHelloParser.isRecordComplete(fullRecord))
  val extracted = TlsClientHelloParser.extractSni(fullRecord)
  assertEquals("secure.service.example.org", extracted)
 }
}
