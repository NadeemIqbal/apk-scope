package com.nadeem.apkscope.core.network.https

import java.nio.ByteBuffer

/**
 * Extracts the Server Name Indication (SNI) hostname from the initial TLS ClientHello record.
 *
 * Implements standard TLS 1.0 - 1.3 ClientHello record and handshake parsing (RFC 5246, RFC 8446).
 * All buffer access is strictly bounds-checked to safely handle malformed or truncated packets.
 */
object TlsClientHelloParser {

 private const val CONTENT_TYPE_HANDSHAKE = 0x16
 private const val HANDSHAKE_TYPE_CLIENT_HELLO = 0x01
 private const val EXTENSION_SERVER_NAME = 0x0000
 private const val NAME_TYPE_HOST_NAME = 0x00

 /**
  * Returns true if the buffer contains a complete TLS record according to its record header length,
  * or if the buffer does not look like TLS handshake data.
  */
 fun isRecordComplete(buf: ByteArray, offset: Int = 0, length: Int = buf.size): Boolean {
  if (length < 5) return false
  val bb = ByteBuffer.wrap(buf, offset, length)
  val contentType = bb.get().toInt() and 0xFF
  if (contentType != CONTENT_TYPE_HANDSHAKE) return true // Not a TLS handshake record
  val major = bb.get().toInt() and 0xFF
  if (major != 0x03) return true // Not SSL 3.0 / TLS 1.x
  bb.get() // minor
  val recordLength = bb.short.toInt() and 0xFFFF
  return length >= 5 + recordLength
 }

 /**
  * Returns the SNI hostname if present, or null if not present, not TLS, or malformed.
  */
 fun extractSni(buf: ByteArray, offset: Int = 0, length: Int = buf.size): String? {
  if (length < 5) return null
  val bb = ByteBuffer.wrap(buf, offset, length)

  val contentType = bb.get().toInt() and 0xFF
  if (contentType != CONTENT_TYPE_HANDSHAKE) return null

  val major = bb.get().toInt() and 0xFF
  val minor = bb.get().toInt() and 0xFF
  if (major != 0x03) return null // Must be SSL 3.0 / TLS 1.x
  val recordLength = bb.short.toInt() and 0xFFFF
  if (bb.remaining() < recordLength && bb.remaining() < 4) return null

  val handshakeType = bb.get().toInt() and 0xFF
  if (handshakeType != HANDSHAKE_TYPE_CLIENT_HELLO) return null

  // 3 bytes handshake length
  if (bb.remaining() < 3) return null
  val hLen1 = bb.get().toInt() and 0xFF
  val hLen2 = bb.get().toInt() and 0xFF
  val hLen3 = bb.get().toInt() and 0xFF
  val handshakeLength = (hLen1 shl 16) or (hLen2 shl 8) or hLen3

  // Client version (2 bytes) + Random (32 bytes)
  if (bb.remaining() < 34) return null
  bb.position(bb.position() + 34)

  // Session ID
  if (!bb.hasRemaining()) return null
  val sessionIdLen = bb.get().toInt() and 0xFF
  if (bb.remaining() < sessionIdLen) return null
  bb.position(bb.position() + sessionIdLen)

  // Cipher Suites
  if (bb.remaining() < 2) return null
  val cipherSuitesLen = bb.short.toInt() and 0xFFFF
  if (bb.remaining() < cipherSuitesLen) return null
  bb.position(bb.position() + cipherSuitesLen)

  // Compression Methods
  if (!bb.hasRemaining()) return null
  val compressionMethodsLen = bb.get().toInt() and 0xFF
  if (bb.remaining() < compressionMethodsLen) return null
  bb.position(bb.position() + compressionMethodsLen)

  // Extensions
  if (bb.remaining() < 2) return null
  val extensionsLen = bb.short.toInt() and 0xFFFF
  if (bb.remaining() < extensionsLen) return null

  val extensionsEnd = bb.position() + extensionsLen
  while (bb.position() + 4 <= extensionsEnd) {
   val extType = bb.short.toInt() and 0xFFFF
   val extLen = bb.short.toInt() and 0xFFFF
   if (bb.position() + extLen > extensionsEnd) return null

   if (extType == EXTENSION_SERVER_NAME) {
    if (extLen < 2) return null
    val serverNameListLen = bb.short.toInt() and 0xFFFF
    val listEnd = bb.position() + serverNameListLen
    while (bb.position() + 3 <= listEnd) {
     val nameType = bb.get().toInt() and 0xFF
     val nameLen = bb.short.toInt() and 0xFFFF
     if (bb.position() + nameLen > listEnd) return null
     if (nameType == NAME_TYPE_HOST_NAME) {
      val nameBytes = ByteArray(nameLen)
      bb.get(nameBytes)
      return String(nameBytes, Charsets.US_ASCII)
     } else {
      bb.position(bb.position() + nameLen)
     }
    }
    return null
   } else {
    bb.position(bb.position() + extLen)
   }
  }

  return null
 }
}
