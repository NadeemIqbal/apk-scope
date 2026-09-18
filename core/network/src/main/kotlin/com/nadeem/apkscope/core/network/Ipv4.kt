package com.nadeem.apkscope.core.network

/**
 * Minimal IPv4 header reader/writer for the forwarding engine. Read side tolerates options
 * (uses [ihl] as the real header length); write side always emits a bare 20-byte header
 * (IHL=5, no options) since every packet the engine originates is one it built itself.
 */
object Ipv4 {
 const val PROTO_ICMP = 1
 const val PROTO_TCP = 6
 const val PROTO_UDP = 17

 fun version(buf: ByteArray, off: Int = 0): Int = (buf[off].toInt() shr 4) and 0xF
 fun ihl(buf: ByteArray, off: Int = 0): Int = (buf[off].toInt() and 0xF) * 4
 fun totalLength(buf: ByteArray, off: Int = 0): Int = ((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF)
 fun protocol(buf: ByteArray, off: Int = 0): Int = buf[off + 9].toInt() and 0xFF
 fun srcIp(buf: ByteArray, off: Int = 0): ByteArray = buf.copyOfRange(off + 12, off + 16)
 fun dstIp(buf: ByteArray, off: Int = 0): ByteArray = buf.copyOfRange(off + 16, off + 20)

 /** True iff the header's own checksum field is internally consistent with [ihl] bytes at [off]. Caller must already have bounds-checked `off + ihl <= buf.size`. */
 fun headerChecksumValid(buf: ByteArray, off: Int, ihl: Int): Boolean = Checksums.checksum(buf, off, ihl) == 0

 /** Writes a bare 20-byte IPv4 header at [out][offset] framing [payloadLength] bytes of [protocol]. */
 fun writeHeader(out: ByteArray, offset: Int, protocol: Int, srcIp: ByteArray, dstIp: ByteArray, payloadLength: Int, identification: Int) {
  val totalLen = 20 + payloadLength
  out[offset] = 0x45 // version 4, IHL 5 (no options)
  out[offset + 1] = 0
  out[offset + 2] = (totalLen shr 8).toByte(); out[offset + 3] = totalLen.toByte()
  out[offset + 4] = (identification shr 8).toByte(); out[offset + 5] = identification.toByte()
  out[offset + 6] = 0x40; out[offset + 7] = 0 // Don't Fragment, no fragment offset
  out[offset + 8] = 64 // TTL
  out[offset + 9] = protocol.toByte()
  out[offset + 10] = 0; out[offset + 11] = 0 // checksum placeholder, filled below
  System.arraycopy(srcIp, 0, out, offset + 12, 4)
  System.arraycopy(dstIp, 0, out, offset + 16, 4)
  val csum = Checksums.checksum(out, offset, 20)
  out[offset + 10] = (csum shr 8).toByte(); out[offset + 11] = csum.toByte()
 }
}
