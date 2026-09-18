package com.nadeem.apkscope.core.network.https

import java.time.Instant
import java.util.UUID

/**
 * Represents a single captured or observed HTTPS transaction.
 *
 * Headers and bodies stored in this model MUST have all authorization tokens, cookies,
 * and sensitive credential fields redacted before persistence, and bodies must be bounded
 * to 64 KiB per direction.
 */
data class HttpsTransaction(
 val id: String = UUID.randomUUID().toString(),
 val timestamp: Instant = Instant.now(),
 val method: String,
 val url: String,
 val host: String,
 val port: Int = 443,
 val statusCode: Int? = null,
 val statusMessage: String? = null,
 val requestHeaders: Map<String, String> = emptyMap(),
 val responseHeaders: Map<String, String> = emptyMap(),
 val requestBody: String? = null,
 val responseBody: String? = null,
 val requestBodyBytes: Int = 0,
 val responseBodyBytes: Int = 0,
 val durationMs: Long = 0L,
 val state: HttpsCaptureState = HttpsCaptureState.DECODED,
 val isTruncated: Boolean = false,
 val failureDetails: String? = null
)
