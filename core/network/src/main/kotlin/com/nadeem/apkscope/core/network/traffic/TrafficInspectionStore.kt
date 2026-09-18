package com.nadeem.apkscope.core.network.traffic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.regex.Pattern

/**
 * Query parameters for filtering captured traffic records.
 */
data class TrafficFilterQuery(
    val host: String? = null,
    val protocol: TrafficProtocol? = null,
    val method: String? = null,
    val statusCode: Int? = null,
    val state: TrafficCaptureState? = null,
    val searchText: String? = null,
    val wsDirection: Direction? = null,
    val wsMessageType: MessageType? = null,
    val timeRangeStart: java.time.Instant? = null,
    val timeRangeEnd: java.time.Instant? = null
) {
    fun isEmpty(): Boolean {
        return host.isNullOrBlank() &&
                protocol == null &&
                method.isNullOrBlank() &&
                statusCode == null &&
                state == null &&
                searchText.isNullOrBlank() &&
                wsDirection == null &&
                wsMessageType == null &&
                timeRangeStart == null &&
                timeRangeEnd == null
    }
}

/**
 * Thread-safe bounded in-memory store for HTTP/HTTPS transactions and WebSocket sessions.
 */
object TrafficInspectionStore {
    const val MAX_RECORDS = 200
    const val MAX_BODY_BYTES = 64 * 1024 // 64 KiB per direction

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

    private val records = Collections.synchronizedList(ArrayList<TrafficRecord>())
    private val _recordsFlow = MutableStateFlow<List<TrafficRecord>>(emptyList())
    val recordsFlow: StateFlow<List<TrafficRecord>> = _recordsFlow.asStateFlow()

    fun record(raw: TrafficRecord): TrafficRecord {
        val (requestBody, reqTruncated) = truncateBody(raw.requestBody)
        val (responseBody, respTruncated) = truncateBody(raw.responseBody)
        val isTruncated = raw.isTruncated || reqTruncated || respTruncated

        val stored = raw.copy(
            requestBody = requestBody,
            responseBody = responseBody,
            isTruncated = isTruncated,
            state = if (isTruncated && raw.state == TrafficCaptureState.DECODED) TrafficCaptureState.TRUNCATED else raw.state
        )

        synchronized(records) {
            val existingIndex = records.indexOfFirst { it.id == stored.id }
            if (existingIndex >= 0) {
                records[existingIndex] = stored
            } else {
                records.add(0, stored)
                while (records.size > MAX_RECORDS) {
                    records.removeAt(records.size - 1)
                }
            }
            _recordsFlow.value = ArrayList(records)
        }
        return stored
    }

    fun updateRecord(id: String, transform: (TrafficRecord) -> TrafficRecord): TrafficRecord? {
        synchronized(records) {
            val index = records.indexOfFirst { it.id == id }
            if (index < 0) return null
            val existing = records[index]
            val updated = transform(existing)
            records[index] = updated
            _recordsFlow.value = ArrayList(records)
            return updated
        }
    }

    fun get(id: String): TrafficRecord? = synchronized(records) {
        records.firstOrNull { it.id == id }
    }

    fun all(): List<TrafficRecord> = synchronized(records) {
        ArrayList(records)
    }

    /**
     * Get records for a specific session and target package only.
     * Enforces session/target isolation for evidence export.
     *
     * Milestone 9 (userspace traffic ownership verification, 2026-09-12): also requires
     * [OwnershipVerificationStatus.MATCHED] — the intended session/target attribution alone
     * (`sessionId`/`targetPackage`) is not sufficient evidence that a record genuinely belongs to
     * the analyzed app, since capture itself is unscoped (see Phase 9.1's platform-limitation
     * finding: `addAllowedApplication` cannot be combined with this product's required always-on
     * VPN lockdown). [OwnershipVerificationStatus.UNKNOWN] is deliberately excluded here alongside
     * [OwnershipVerificationStatus.MISMATCHED] — an unverified record must never be treated as
     * confirmed target evidence just because verification could not be completed. This is the sole
     * production caller of this method (`SandboxWorkQueryActivity.exportUrlEvidence`, the URL
     * evidence export for DEX correlation); [all]/[filter] remain unfiltered by ownership for
     * general Live Monitor display, where showing an unverified record (labeled, not hidden) is
     * more useful than silently dropping it.
     */
    fun forSession(sessionId: String?, targetPackage: String?): List<TrafficRecord> = synchronized(records) {
        if (sessionId == null) return emptyList()
        ArrayList(records.filter {
            it.sessionId == sessionId && it.targetPackage == targetPackage &&
                it.ownershipStatus == OwnershipVerificationStatus.MATCHED
        })
    }

    fun clear() {
        synchronized(records) {
            records.clear()
            _recordsFlow.value = emptyList()
        }
    }

    fun filter(query: TrafficFilterQuery, sourceList: List<TrafficRecord> = all()): List<TrafficRecord> {
        if (query.isEmpty()) return sourceList

        val searchLower = query.searchText?.trim()?.lowercase()?.ifEmpty { null }
        val hostQuery = query.host?.trim()?.lowercase()?.ifEmpty { null }
        val methodQuery = query.method?.trim()?.uppercase()?.ifEmpty { null }

        return sourceList.filter { record ->
            // Host filter
            if (hostQuery != null && !record.host.lowercase().contains(hostQuery)) {
                return@filter false
            }

            // Protocol filter
            if (query.protocol != null && record.protocol != query.protocol) {
                return@filter false
            }

            // Method filter
            if (methodQuery != null && record.method.uppercase() != methodQuery) {
                return@filter false
            }

            // Status code filter
            if (query.statusCode != null && record.statusCode != query.statusCode) {
                return@filter false
            }

            // State filter
            if (query.state != null && record.state != query.state) {
                return@filter false
            }

            // WebSocket Direction filter
            if (query.wsDirection != null) {
                val ws = record.webSocketSession ?: return@filter false
                if (ws.messages.none { it.direction == query.wsDirection }) return@filter false
            }

            // WebSocket Message Type filter
            if (query.wsMessageType != null) {
                val ws = record.webSocketSession ?: return@filter false
                if (ws.messages.none { it.type == query.wsMessageType }) return@filter false
            }

            // Time range filter
            if (query.timeRangeStart != null && record.timestamp.isBefore(query.timeRangeStart)) {
                return@filter false
            }
            if (query.timeRangeEnd != null && record.timestamp.isAfter(query.timeRangeEnd)) {
                return@filter false
            }

            // Free text search
            if (searchLower != null) {
                val inUrl = record.url.lowercase().contains(searchLower)
                val inHost = record.host.lowercase().contains(searchLower)
                val inReqHeaders = record.requestHeaders.any { (k, v) ->
                    k.lowercase().contains(searchLower) || v.lowercase().contains(searchLower)
                }
                val inRespHeaders = record.responseHeaders.any { (k, v) ->
                    k.lowercase().contains(searchLower) || v.lowercase().contains(searchLower)
                }
                val inReqBody = record.requestBody?.lowercase()?.contains(searchLower) == true
                val inRespBody = record.responseBody?.lowercase()?.contains(searchLower) == true
                val inWs = record.webSocketSession?.messages?.any { msg ->
                    msg.payloadPreview?.lowercase()?.contains(searchLower) == true
                } == true

                if (!inUrl && !inHost && !inReqHeaders && !inRespHeaders && !inReqBody && !inRespBody && !inWs) {
                    return@filter false
                }
            }

            true
        }
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

    /** Returns a redacted presentation copy. Captured values remain intact for local inspection. */
    fun redacted(record: TrafficRecord): TrafficRecord = record.copy(
        url = sanitizeUrl(record.url),
        requestHeaders = sanitizeHeaders(record.requestHeaders),
        responseHeaders = sanitizeHeaders(record.responseHeaders),
        requestBody = record.requestBody?.let(::redactSecrets),
        responseBody = record.responseBody?.let(::redactSecrets)
    )

    fun truncateBody(body: String?): Pair<String?, Boolean> {
        if (body == null) return null to false
        val bytes = body.toByteArray(Charsets.UTF_8)
        return if (bytes.size > MAX_BODY_BYTES) {
            val truncatedText = String(bytes, 0, MAX_BODY_BYTES, Charsets.UTF_8) + "\n\n[TRUNCATED: Exceeded 64 KiB preview limit]"
            truncatedText to true
        } else {
            body to false
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
