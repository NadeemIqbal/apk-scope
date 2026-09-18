package com.nadeem.apkscope.core.network

object Udp {
 const val HEADER_LEN = 8

 fun srcPort(buf: ByteArray, off: Int): Int = ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
 fun dstPort(buf: ByteArray, off: Int): Int = ((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF)
 fun length(buf: ByteArray, off: Int): Int = ((buf[off + 4].toInt() and 0xFF) shl 8) or (buf[off + 5].toInt() and 0xFF)
 fun checksumField(buf: ByteArray, off: Int): Int = ((buf[off + 6].toInt() and 0xFF) shl 8) or (buf[off + 7].toInt() and 0xFF)

 /** True iff the checksum is present (0 on the wire means "not computed", legal per RFC 768) and internally consistent. Caller must already have bounds-checked `off + segmentLen <= buf.size`. */
 fun checksumValid(buf: ByteArray, off: Int, segmentLen: Int, srcIp: ByteArray, dstIp: ByteArray): Boolean {
  if (checksumField(buf, off) == 0) return true // no checksum transmitted; nothing to verify
  val pseudo = Checksums.pseudoHeaderSum(srcIp, dstIp, Ipv4.PROTO_UDP, segmentLen)
  return Checksums.checksum(buf, off, segmentLen, pseudo) == 0
 }

 /** Writes an 8-byte UDP header plus payload at [out][offset]. */
 fun writeDatagram(out: ByteArray, offset: Int, srcPort: Int, dstPort: Int, srcIp: ByteArray, dstIp: ByteArray, payload: ByteArray, payloadOff: Int, payloadLen: Int) {
  val len = HEADER_LEN + payloadLen
  out[offset] = (srcPort shr 8).toByte(); out[offset + 1] = srcPort.toByte()
  out[offset + 2] = (dstPort shr 8).toByte(); out[offset + 3] = dstPort.toByte()
  out[offset + 4] = (len shr 8).toByte(); out[offset + 5] = len.toByte()
  out[offset + 6] = 0; out[offset + 7] = 0 // checksum placeholder
  if (payloadLen > 0) System.arraycopy(payload, payloadOff, out, offset + HEADER_LEN, payloadLen)
  val pseudo = Checksums.pseudoHeaderSum(srcIp, dstIp, Ipv4.PROTO_UDP, len)
  val csum = Checksums.checksum(out, offset, len, pseudo)
  // RFC 768: a computed checksum of 0 is transmitted as all-ones; 0 on the wire means "no checksum".
  val onWire = if (csum == 0) 0xFFFF else csum
  out[offset + 6] = (onWire shr 8).toByte(); out[offset + 7] = onWire.toByte()
 }
}
