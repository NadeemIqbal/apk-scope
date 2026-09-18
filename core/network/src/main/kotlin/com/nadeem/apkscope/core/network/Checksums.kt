package com.nadeem.apkscope.core.network

/** RFC 1071 Internet checksum, plus the IPv4 pseudo-header sum used by UDP/TCP. */
object Checksums {
 /** Folds [buf][offset,offset+length) into a ones-complement checksum, seeded with [initial] (e.g. a pseudo-header sum). */
 fun checksum(buf: ByteArray, offset: Int, length: Int, initial: Long = 0L): Int {
  var sum = initial
  var i = offset
  val end = offset + length
  while (i + 1 < end) {
   sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
   i += 2
  }
  if (i < end) sum += (buf[i].toInt() and 0xFF) shl 8 // odd trailing byte, high-order padded with zero
  while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
  return (sum.inv() and 0xFFFF).toInt()
 }

 /** Unfolded sum of the pseudo-header fields; pass as [checksum]'s initial value so it chains into one fold. */
 fun pseudoHeaderSum(srcIp: ByteArray, dstIp: ByteArray, protocol: Int, length: Int): Long {
  var sum = 0L
  sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
  sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
  sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
  sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
  sum += protocol.toLong()
  sum += length.toLong()
  return sum
 }
}
