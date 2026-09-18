package com.nadeem.apkscope.core.network

/**
 * TCP header reader/writer. Sequence numbers are carried as [Long] (masked to 32 bits) so
 * wraparound comparisons stay simple integer subtraction without sign trouble.
 */
object Tcp {
 const val FIN = 0x01; const val SYN = 0x02; const val RST = 0x04
 const val PSH = 0x08; const val ACK = 0x10; const val URG = 0x20
 const val HEADER_LEN = 20
 private const val MASK32 = 0xFFFFFFFFL

 fun srcPort(buf: ByteArray, off: Int): Int = ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
 fun dstPort(buf: ByteArray, off: Int): Int = ((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF)
 fun seq(buf: ByteArray, off: Int): Long = u32(buf, off + 4)
 fun ack(buf: ByteArray, off: Int): Long = u32(buf, off + 8)
 fun dataOffset(buf: ByteArray, off: Int): Int = ((buf[off + 12].toInt() shr 4) and 0xF) * 4
 fun flags(buf: ByteArray, off: Int): Int = buf[off + 13].toInt() and 0xFF
 fun window(buf: ByteArray, off: Int): Int = ((buf[off + 14].toInt() and 0xFF) shl 8) or (buf[off + 15].toInt() and 0xFF)

 /** True iff the segment's checksum is internally consistent. Caller must already have bounds-checked `off + segmentLen <= buf.size`. */
 fun checksumValid(buf: ByteArray, off: Int, segmentLen: Int, srcIp: ByteArray, dstIp: ByteArray): Boolean {
  val pseudo = Checksums.pseudoHeaderSum(srcIp, dstIp, Ipv4.PROTO_TCP, segmentLen)
  return Checksums.checksum(buf, off, segmentLen, pseudo) == 0
 }

 private fun u32(buf: ByteArray, off: Int): Long =
  (((buf[off].toLong() and 0xFF) shl 24) or ((buf[off + 1].toLong() and 0xFF) shl 16) or
   ((buf[off + 2].toLong() and 0xFF) shl 8) or (buf[off + 3].toLong() and 0xFF)) and MASK32

 /** Best-effort extraction of an advertised MSS option from a SYN's option bytes; null if absent/unparseable. */
 fun readMss(buf: ByteArray, off: Int): Int? {
  val hdrLen = dataOffset(buf, off)
  var i = off + HEADER_LEN
  val end = off + hdrLen
  while (i < end) {
   val kind = buf[i].toInt() and 0xFF
   if (kind == 0) break // end of options
   if (kind == 1) { i += 1; continue } // NOP
   if (i + 1 >= end) break
   val len = buf[i + 1].toInt() and 0xFF
   if (len < 2 || i + len > end) break
   if (kind == 2 && len == 4) return ((buf[i + 2].toInt() and 0xFF) shl 8) or (buf[i + 3].toInt() and 0xFF)
   i += len
  }
  return null
 }

 /**
  * Writes a TCP segment at [out][offset]: header (20 bytes, plus a 4-byte MSS option when [mss] is
  * given — used only on our SYN-ACK) followed by [payloadLen] bytes copied from [payload].
  * Returns the total segment length written (header + options + payload).
  */
 fun writeSegment(
  out: ByteArray, offset: Int, srcPort: Int, dstPort: Int, seq: Long, ack: Long, flags: Int, window: Int,
  srcIp: ByteArray, dstIp: ByteArray, mss: Int? = null, payload: ByteArray = EMPTY, payloadOff: Int = 0, payloadLen: Int = 0
 ): Int {
  val optionsLen = if (mss != null) 4 else 0
  val hdrLen = HEADER_LEN + optionsLen
  out[offset] = (srcPort shr 8).toByte(); out[offset + 1] = srcPort.toByte()
  out[offset + 2] = (dstPort shr 8).toByte(); out[offset + 3] = dstPort.toByte()
  writeU32(out, offset + 4, seq)
  writeU32(out, offset + 8, ack)
  out[offset + 12] = ((hdrLen / 4) shl 4).toByte()
  out[offset + 13] = flags.toByte()
  out[offset + 14] = (window shr 8).toByte(); out[offset + 15] = window.toByte()
  out[offset + 16] = 0; out[offset + 17] = 0 // checksum placeholder
  out[offset + 18] = 0; out[offset + 19] = 0 // urgent pointer, unused
  if (mss != null) {
   out[offset + 20] = 2; out[offset + 21] = 4
   out[offset + 22] = (mss shr 8).toByte(); out[offset + 23] = mss.toByte()
  }
  if (payloadLen > 0) System.arraycopy(payload, payloadOff, out, offset + hdrLen, payloadLen)
  val total = hdrLen + payloadLen
  val pseudo = Checksums.pseudoHeaderSum(srcIp, dstIp, Ipv4.PROTO_TCP, total)
  val csum = Checksums.checksum(out, offset, total, pseudo)
  out[offset + 16] = (csum shr 8).toByte(); out[offset + 17] = csum.toByte()
  return total
 }

 private fun writeU32(out: ByteArray, off: Int, v: Long) {
  out[off] = (v shr 24).toByte(); out[off + 1] = (v shr 16).toByte(); out[off + 2] = (v shr 8).toByte(); out[off + 3] = v.toByte()
 }

 private val EMPTY = ByteArray(0)
}
