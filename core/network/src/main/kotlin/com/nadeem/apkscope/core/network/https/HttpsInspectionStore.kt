package com.nadeem.apkscope.core.network.https

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.regex.Pattern

/**
 * In-memory ring buffer storing up to [MAX_TRANSACTIONS] (100) HTTPS transactions for the POC viewer.
 *
 * Implements:
 * - 64 KiB payload truncation limit per direction.
 * - Sensitive header redaction (Authorization, Cookies, Api Keys).
 * - Common JSON secret field redaction (passwords, tokens, api keys, secrets).
 * - Thread-safe operations and StateFlow for real-time Compose updates.
 */
object HttpsInspectionStore {
 const val MAX_TRANSACTIONS = 100
 const val MAX_BODY_BYTES = 64 * 1024 // 64 KiB

 private val sensitiveHeaderNames = setOf(
  "authorization",
  "proxy-authorization",
  "cookie",
  "set-cookie",
  "x-api-key",
  "apikey",
  "token",
  "x-auth-token",
  "bearer"
 )

 private val jsonSecretRegex = Pattern.compile(
  """(?i)"([^"]*?(?:password|secret|token|access_token|refresh_token|auth|api_key|credential|pin|cvv)[^"]*?)"\s*:\s*("(?:\\"|[^"])*"|\[[^]]*]|true|false|null|\d+)"""
 )

 private val urlSecretRegex = Pattern.compile(
  """(?i)([\?&])(password|secret|token|access_token|refresh_token|auth|api_key|apikey|credential|key)=([^&#]*)"""
 )

 private val transactions = Collections.synchronizedList(ArrayList<HttpsTransaction>())
 private val _transactionsFlow = MutableStateFlow<List<HttpsTransaction>>(emptyList())
 val transactionsFlow: StateFlow<List<HttpsTransaction>> = _transactionsFlow.asStateFlow()

 /**
  * Milestone 9 attribution fix (2026-09-12, canonical form): [sessionId]/[targetPackage]/[ownership]
  * are the *canonical* source of attribution for the mirrored `TrafficRecord` below — passed
  * explicitly by the caller, which always has them available as its own connection-scoped state
  * (see every call site in `HttpsInspectionEngine`). Deliberately **not** an indiscriminate merge
  * against whatever `TrafficInspectionStore` happens to already hold under this id: an earlier
  * version of this fix looked up the existing record and copied its attribution forward, which only
  * worked for the one call site that reuses an id `TrafficInspectionStore` already saw, and would
  * have silently carried forward *stale* attribution for any id that collided with an unrelated
  * prior record. Explicit, caller-supplied attribution has no such failure mode — it is simply what
  * this specific transaction actually belongs to, independent of the store's prior contents. The
  * same reasoning applies to [ownership]: it defaults to
  * [com.nadeem.apkscope.core.network.traffic.OwnershipVerification.UNVERIFIED] (honest "not computed"),
  * never inherited from whatever the store already holds.
  */
 fun record(
  transaction: HttpsTransaction,
  sessionId: String? = null,
  targetPackage: String? = null,
  ownership: com.nadeem.apkscope.core.network.traffic.OwnershipVerification = com.nadeem.apkscope.core.network.traffic.OwnershipVerification.UNVERIFIED,
 ) {
  val (sanitizedReqBody, reqTruncated) = sanitizeAndTruncateBody(transaction.requestBody)
  val (sanitizedRespBody, respTruncated) = sanitizeAndTruncateBody(transaction.responseBody)
  val isTruncated = transaction.isTruncated || reqTruncated || respTruncated

  val sanitized = transaction.copy(
   url = sanitizeUrl(transaction.url),
   requestHeaders = sanitizeHeaders(transaction.requestHeaders),
   responseHeaders = sanitizeHeaders(transaction.responseHeaders),
   requestBody = sanitizedReqBody,
   responseBody = sanitizedRespBody,
   isTruncated = isTruncated,
   state = if (isTruncated && transaction.state == HttpsCaptureState.DECODED) HttpsCaptureState.TRUNCATED else transaction.state
  )

  synchronized(transactions) {
   val existingIndex = transactions.indexOfFirst { it.id == sanitized.id }
   if (existingIndex >= 0) {
    transactions[existingIndex] = sanitized
   } else {
    transactions.add(0, sanitized)
    while (transactions.size > MAX_TRANSACTIONS) {
     transactions.removeAt(transactions.size - 1)
    }
   }
   _transactionsFlow.value = ArrayList(transactions)
  }

  // Mirror to unified TrafficInspectionStore, carrying the caller-supplied sessionId/targetPackage
  // through explicitly (see this function's own doc comment for why this replaced an earlier
  // lookup-and-merge approach). HttpsTransaction itself carries neither field — a deliberately
  // session-unaware legacy model — so without this parameter the mirror would silently null out
  // attribution on every transaction, which is exactly the defect this fix closes.
  val proto = if (sanitized.port == 80) com.nadeem.apkscope.core.network.traffic.TrafficProtocol.HTTP else com.nadeem.apkscope.core.network.traffic.TrafficProtocol.HTTPS
  val captureState = when (sanitized.state) {
   HttpsCaptureState.DECODED -> com.nadeem.apkscope.core.network.traffic.TrafficCaptureState.DECODED
   HttpsCaptureState.ENCRYPTED -> com.nadeem.apkscope.core.network.traffic.TrafficCaptureState.ENCRYPTED
   HttpsCaptureState.TLS_HANDSHAKE_FAILED -> com.nadeem.apkscope.core.network.traffic.TrafficCaptureState.TLS_HANDSHAKE_FAILED
   HttpsCaptureState.UNSUPPORTED_PROTOCOL -> com.nadeem.apkscope.core.network.traffic.TrafficCaptureState.UNSUPPORTED_PROTOCOL
   HttpsCaptureState.TRUNCATED -> com.nadeem.apkscope.core.network.traffic.TrafficCaptureState.TRUNCATED
  }
  com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore.record(
   com.nadeem.apkscope.core.network.traffic.TrafficRecord(
    id = sanitized.id,
    sessionId = sessionId,
    targetPackage = targetPackage,
    observedOwnerUid = ownership.observedOwnerUid,
    ownershipStatus = ownership.status,
    ownershipFailureReason = ownership.failureReason,
    protocol = proto,
    host = sanitized.host,
    port = sanitized.port,
    url = sanitized.url,
    method = sanitized.method,
    statusCode = sanitized.statusCode,
    statusMessage = sanitized.statusMessage,
    contentType = sanitized.responseHeaders["Content-Type"],
    requestHeaders = sanitized.requestHeaders,
    responseHeaders = sanitized.responseHeaders,
    requestBody = sanitized.requestBody,
    responseBody = sanitized.responseBody,
    requestBodyBytes = sanitized.requestBodyBytes.toLong(),
    responseBodyBytes = sanitized.responseBodyBytes.toLong(),
    durationMs = sanitized.durationMs,
    state = captureState,
    failureDetails = sanitized.failureDetails,
    isTruncated = sanitized.isTruncated
   )
  )
 }

 fun all(): List<HttpsTransaction> = synchronized(transactions) { ArrayList(transactions) }

 fun clear() {
  synchronized(transactions) {
   transactions.clear()
   _transactionsFlow.value = emptyList()
  }
  com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore.clear()
 }

 fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> {
  return headers.mapValues { (name, value) ->
   if (sensitiveHeaderNames.contains(name.lowercase().trim())) {
    "[REDACTED]"
   } else {
    value
   }
  }
 }

 fun sanitizeAndTruncateBody(body: String?): Pair<String?, Boolean> {
  if (body == null) return null to false
  var redacted = redactSecrets(body)
  val bytes = redacted.toByteArray(Charsets.UTF_8)
  return if (bytes.size > MAX_BODY_BYTES) {
   val truncatedText = String(bytes, 0, MAX_BODY_BYTES, Charsets.UTF_8) + "\n\n[TRUNCATED: Exceeded 64 KiB POC limit]"
   truncatedText to true
  } else {
   redacted to false
  }
 }

 fun redactSecrets(text: String): String {
  val matcher = jsonSecretRegex.matcher(text)
  val sb = StringBuffer()
  while (matcher.find()) {
   val key = matcher.group(1)
   matcher.appendReplacement(sb, "\"$key\":\"[REDACTED]\"")
  }
  matcher.appendTail(sb)
  return sb.toString()
 }

 fun sanitizeUrl(url: String): String {
  val matcher = urlSecretRegex.matcher(url)
  val sb = StringBuffer()
  while (matcher.find()) {
   val prefix = matcher.group(1)
   val key = matcher.group(2)
   matcher.appendReplacement(sb, "${prefix}${key}=[REDACTED]")
  }
  matcher.appendTail(sb)
  return sb.toString()
 }
}
