package com.nadeem.apkscope.core.network.https

/**
 * Explicit capture states for inspected HTTPS connections in the POC.
 *
 * Per requirements, every transaction is assigned an honest, unambiguous state:
 * - [DECODED]: Fully decrypted and parsed HTTP/1.1 transaction.
 * - [ENCRYPTED]: Connection was not intercepted (e.g., untargeted destination or pass-through).
 * - [TLS_HANDSHAKE_FAILED]: TLS handshake failed on either client or upstream side.
 * - [UNSUPPORTED_PROTOCOL]: Protocol was not HTTP/1.1 over TLS (e.g. non-TLS, HTTP/2, WebSocket).
 * - [TRUNCATED]: Payload exceeded the 64 KiB POC boundary and was safely truncated.
 */
enum class HttpsCaptureState(val displayName: String) {
 DECODED("Decoded"),
 ENCRYPTED("Encrypted"),
 TLS_HANDSHAKE_FAILED("TLS handshake failed"),
 UNSUPPORTED_PROTOCOL("Unsupported protocol"),
 TRUNCATED("Truncated")
}
