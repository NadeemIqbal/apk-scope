package com.nadeem.apkscope.core.network

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsMessageTest {
 private fun encodeName(vararg labels: String): ByteArray {
  val out = ByteArrayOutputStream()
  for (label in labels) { out.write(label.length); out.write(label.toByteArray(Charsets.US_ASCII)) }
  out.write(0)
  return out.toByteArray()
 }

 private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

 private fun buildQuery(txId: Int, vararg labels: String): ByteArray {
  val header = u16(txId) + u16(0x0100) + u16(1) + u16(0) + u16(0) + u16(0)
  val question = encodeName(*labels) + u16(1) + u16(1) // QTYPE=A, QCLASS=IN
  return header + question
 }

 private fun buildResponse(txId: Int, hostname: List<String>, addresses: List<ByteArray>): ByteArray {
  val header = u16(txId) + u16(0x8180) + u16(1) + u16(addresses.size) + u16(0) + u16(0)
  val question = encodeName(*hostname.toTypedArray()) + u16(1) + u16(1)
  var body = header + question
  for (addr in addresses) {
   // NAME as a compression pointer back to the question's name at offset 12
   body += byteArrayOf(0xC0.toByte(), 0x0C) + u16(1) + u16(1) + byteArrayOf(0, 0, 0, 60) + u16(addr.size) + addr
  }
  return body
 }

 @Test fun parsesAnOrdinaryQuery() {
  val msg = buildQuery(0x1234, "example", "com")
  val parsed = DnsMessage.parseQuery(msg, 0, msg.size)
  assertNotNull(parsed)
  assertEquals(0x1234, parsed!!.transactionId)
  assertEquals("example.com", parsed.hostname)
 }

 @Test fun parsesAnOrdinaryResponseWithCompressedName() {
  val msg = buildResponse(0x1234, listOf("example", "com"), listOf(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)))
  val parsed = DnsMessage.parseResponse(msg, 0, msg.size)
  assertNotNull(parsed)
  assertEquals(0x1234, parsed!!.transactionId)
  assertEquals("example.com", parsed.hostname)
  assertEquals(listOf("93.184.216.34"), parsed.addresses)
 }

 @Test fun responseWithMultipleAnswersCollectsAllAddresses() {
  val msg = buildResponse(1, listOf("multi", "example"), listOf(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8)))
  val parsed = DnsMessage.parseResponse(msg, 0, msg.size)
  assertEquals(listOf("1.2.3.4", "5.6.7.8"), parsed!!.addresses)
 }

 @Test fun tooShortMessageReturnsNullNotAnException() {
  assertNull(DnsMessage.parseQuery(ByteArray(4), 0, 4))
  assertNull(DnsMessage.parseResponse(ByteArray(4), 0, 4))
 }

 @Test fun truncatedNameReturnsNullNotAnException() {
  val msg = buildQuery(1, "example", "com").copyOf(15) // cuts the name off mid-label
  assertNull(DnsMessage.parseQuery(msg, 0, msg.size))
 }

 @Test fun selfReferencingCompressionPointerTerminatesInsteadOfLooping() {
  // Header claims 1 question; the "name" at offset 12 is a pointer to itself — an infinite loop if
  // the decompressor doesn't bound its own work. This test hanging forever *is* the failure mode.
  val header = u16(0x1234) + u16(0x0100) + u16(1) + u16(0) + u16(0) + u16(0)
  val maliciousName = byteArrayOf(0xC0.toByte(), 0x0C) // pointer to offset 12, i.e. itself
  val msg = header + maliciousName + u16(1) + u16(1)
  val parsed = DnsMessage.parseQuery(msg, 0, msg.size)
  assertNull(parsed) // bounded jump budget gives up and reports "couldn't parse", not a hang or a crash
 }

 @Test fun forwardPointingCompressionPointerIsRejectedNotFollowedIntoUnvalidatedRegion() {
  val header = u16(1) + u16(0x0100) + u16(1) + u16(0) + u16(0) + u16(0)
  val forwardPointer = byteArrayOf(0xC0.toByte(), 0xFF.toByte()) // points far past the end of this short message
  val msg = header + forwardPointer + u16(1) + u16(1)
  val parsed = DnsMessage.parseQuery(msg, 0, msg.size)
  assertNull(parsed)
 }

 @Test fun rdLengthLyingAboutAvailableBytesIsRejected() {
  val header = u16(1) + u16(0x8180) + u16(1) + u16(1) + u16(0) + u16(0)
  val question = encodeName("a") + u16(1) + u16(1)
  // Answer claims RDLENGTH=200 but the message ends right after a few bytes of "RDATA".
  val answer = byteArrayOf(0xC0.toByte(), 0x0C) + u16(1) + u16(1) + byteArrayOf(0, 0, 0, 60) + u16(200) + byteArrayOf(1, 2, 3, 4)
  val msg = header + question + answer
  val parsed = DnsMessage.parseResponse(msg, 0, msg.size)
  assertNotNull(parsed) // header/question parsed fine; the bogus answer is just skipped, not a crash
  assertTrue(parsed!!.addresses.isEmpty())
 }
}
