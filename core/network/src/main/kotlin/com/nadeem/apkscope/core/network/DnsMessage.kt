package com.nadeem.apkscope.core.network

import com.nadeem.apkscope.core.model.NetworkObservation

/**
 * Minimal, defensive DNS message reader used only to produce [NetworkObservation.DnsQuery]/
 * [NetworkObservation.DnsResponse] events — it never influences forwarding decisions, and a parse
 * failure here must never affect the UDP datagram it came from (that datagram is still forwarded
 * byte-for-byte regardless of whether this parser understood it).
 *
 * DNS name compression (RFC 1035 §4.1.4) lets a name reference an earlier point in the message via
 * a 2-byte pointer. A hostile/malformed message can make pointers loop or point forward; this
 * parser never trusts message content to bound its own work — every loop below is capped by a
 * fixed iteration/jump budget independent of what the bytes say, so parsing always terminates and
 * never reads outside `buf`.
 */
object DnsMessage {
 private const val MAX_LABELS = 128
 private const val MAX_JUMPS = 32
 private const val HEADER_LEN = 12

 data class Question(val name: String, val nextOffset: Int)
 data class ParsedQuery(val transactionId: Int, val hostname: String)
 data class ParsedResponse(val transactionId: Int, val hostname: String?, val addresses: List<String>)

 /** Parses a DNS query datagram; null if the message is too short or malformed. Never throws. */
 fun parseQuery(buf: ByteArray, off: Int, len: Int): ParsedQuery? = runCatching {
  if (len < HEADER_LEN) return null
  val txId = u16(buf, off)
  val qdCount = u16(buf, off + 4)
  if (qdCount < 1) return null
  val q = readName(buf, off + HEADER_LEN, off, off + len) ?: return null
  ParsedQuery(txId, q.name)
 }.getOrNull()

 /** Parses a DNS response datagram; null if too short/malformed. [ParsedResponse.hostname] is null only if the echoed question could not be parsed — never fabricated from the answers. */
 fun parseResponse(buf: ByteArray, off: Int, len: Int): ParsedResponse? = runCatching {
  if (len < HEADER_LEN) return null
  val end = off + len
  val txId = u16(buf, off)
  val qdCount = u16(buf, off + 4)
  val anCount = u16(buf, off + 6)
  var cursor = off + HEADER_LEN
  var hostname: String? = null
  for (i in 0 until qdCount) {
   val q = readName(buf, cursor, off, end) ?: return ParsedResponse(txId, hostname, emptyList())
   if (i == 0) hostname = q.name
   cursor = q.nextOffset + 4 // QTYPE + QCLASS
   if (cursor > end) return ParsedResponse(txId, hostname, emptyList())
  }
  val addresses = ArrayList<String>()
  for (i in 0 until anCount) {
   if (cursor + 10 > end) break // NAME(>=1) + TYPE(2) + CLASS(2) + TTL(4) + RDLENGTH(2) — bail before reading past the buffer
   val name = readName(buf, cursor, off, end) ?: break
   cursor = name.nextOffset
   if (cursor + 10 > end) break
   val type = u16(buf, cursor)
   val rdLength = u16(buf, cursor + 8)
   val rdataOffset = cursor + 10
   if (rdLength < 0 || rdataOffset + rdLength > end) break // RDLENGTH claims more than the message actually has
   when {
    type == 1 && rdLength == 4 -> addresses.add((0 until 4).joinToString(".") { j -> (buf[rdataOffset + j].toInt() and 0xFF).toString() })
    type == 28 && rdLength == 16 -> addresses.add(formatV6(buf, rdataOffset))
   }
   cursor = rdataOffset + rdLength
  }
  ParsedResponse(txId, hostname, addresses)
 }.getOrNull()

 private fun readName(buf: ByteArray, startOffset: Int, messageStart: Int, messageEnd: Int): Question? {
  val labels = ArrayList<String>()
  var pos = startOffset
  var mainCursorAfterName = -1
  var jumps = 0
  var labelCount = 0
  while (true) {
   if (pos < messageStart || pos >= messageEnd) return null
   val lengthByte = buf[pos].toInt() and 0xFF
   when {
    lengthByte == 0 -> { // root label: end of name
     if (mainCursorAfterName < 0) mainCursorAfterName = pos + 1
     return Question(labels.joinToString("."), mainCursorAfterName)
    }
    lengthByte and 0xC0 == 0xC0 -> { // compression pointer: 2 bytes, 14-bit offset
     if (pos + 1 >= messageEnd) return null
     if (mainCursorAfterName < 0) mainCursorAfterName = pos + 2
     jumps++
     if (jumps > MAX_JUMPS) return null
     val target = ((lengthByte and 0x3F) shl 8) or (buf[pos + 1].toInt() and 0xFF)
     pos = messageStart + target
    }
    lengthByte and 0xC0 != 0 -> return null // reserved label-type bits: not a valid label or pointer
    else -> {
     labelCount++
     if (labelCount > MAX_LABELS) return null
     val labelStart = pos + 1
     val labelEnd = labelStart + lengthByte
     if (labelEnd > messageEnd) return null
     labels.add(String(buf, labelStart, lengthByte, Charsets.US_ASCII))
     pos = labelEnd
    }
   }
  }
 }

 private fun formatV6(buf: ByteArray, off: Int): String =
  (0 until 8).joinToString(":") { i -> String.format("%x", u16(buf, off + i * 2)) }

 private fun u16(buf: ByteArray, off: Int): Int = ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
}
