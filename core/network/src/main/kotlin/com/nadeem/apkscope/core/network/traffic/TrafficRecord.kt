package com.nadeem.apkscope.core.network.traffic

import java.time.Instant

/**
 * Protocol family for an intercepted or observed network stream.
 */
enum class TrafficProtocol {
    HTTP,
    HTTPS,
    WS,
    WSS,
    HTTP_2,
    HTTPS_2,
    GRPC,
    SSE,
    QUIC_OBSERVED
}

/**
 * Inspection and capture state of a traffic transaction or session.
 */
enum class TrafficCaptureState {
    /** Request and response / frames successfully intercepted and decoded. */
    DECODED,
    /** Passthrough encrypted connection without interception or decryption. */
    ENCRYPTED,
    /** Downstream or upstream TLS handshake failed (certificate untrusted, pinning, mismatch). */
    TLS_HANDSHAKE_FAILED,
    /** Protocol unsupported for decoding (e.g. non-HTTP/1.1 or unknown framing). */
    UNSUPPORTED_PROTOCOL,
    /** Payload exceeded preview cap (64 KiB) and was truncated for preview. */
    TRUNCATED,
    /** Unexpected processing or I/O error during interception. */
    ERROR
}

/**
 * Milestone 9 (Phase: userspace traffic ownership verification, 2026-09-12) — the result of
 * comparing a connection's real, OS-verified owner UID ([TrafficRecord.observedOwnerUid]) against
 * the session's resolved target UID. Deliberately a *separate* concept from
 * [TrafficRecord.sessionId]/[TrafficRecord.targetPackage] (the session's own *intended* identity,
 * set unconditionally at session-attribution time — see the Milestone 9 attribution-wiring fix) —
 * this is whether that intended attribution was actually *verified* for this specific connection,
 * which can independently be true, false, or undeterminable.
 */
enum class OwnershipVerificationStatus {
    /** The connection's real owner UID (from `ConnectivityManager.getConnectionOwnerUid`) equals the session's resolved target UID. Positive evidence the traffic genuinely belongs to the analyzed app. */
    MATCHED,
    /** The connection's real owner UID was resolved, and it is a different, real UID than the session's target — e.g. a second Work Profile app or APK Scope's own Work-side background traffic sharing the unscoped tunnel (see Phase 9.1's platform-limitation finding). */
    MISMATCHED,
    /** Ownership could not be determined — the target UID isn't resolvable yet (not installed), the connection's owner UID lookup failed or raced, or the API is unavailable. Must never be treated as matched, and must never silently degrade into MATCHED or MISMATCHED without a real comparison. See [TrafficRecord.ownershipFailureReason] for why. */
    UNKNOWN
}

/**
 * Milestone 9 (userspace traffic ownership verification) — the per-connection result computed once
 * in `HttpsInspectionEngine.handleClient` (from the owner UID `TcpProxy` resolved and carried
 * across the internal preamble, compared against the session's resolved target UID) and threaded
 * through to every `TrafficRecord` this connection produces. A small holder, not three separate
 * parameters, purely to keep the many downstream function signatures from growing unreadable —
 * the three fields it carries remain the same three distinct concepts described on
 * [TrafficRecord.observedOwnerUid]/[TrafficRecord.ownershipStatus]/[TrafficRecord.ownershipFailureReason].
 */
data class OwnershipVerification(
    val observedOwnerUid: Int?,
    val status: OwnershipVerificationStatus,
    val failureReason: String?
) {
    companion object {
        /** The pre-Milestone-9 default for any code path that does not yet compute real ownership verification — honest "unknown", never fabricated as matched. */
        val UNVERIFIED = OwnershipVerification(null, OwnershipVerificationStatus.UNKNOWN, "ownership verification not performed for this connection")
    }
}

/**
 * Unified record modeling an HTTP/HTTPS transaction, WebSocket session, gRPC call, or SSE stream.
 */
data class TrafficRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sessionId: String? = null,
    val targetPackage: String? = null,
    /** Milestone 9: the connection's real owner UID as resolved via `ConnectivityManager.getConnectionOwnerUid`, or null if never resolved/attempted — distinct from [ownershipStatus], which is the *comparison result*, not the raw value. */
    val observedOwnerUid: Int? = null,
    /** Milestone 9: whether [observedOwnerUid] was verified to match the session's resolved target UID. Defaults to [OwnershipVerificationStatus.UNKNOWN] — ownership is never assumed true. */
    val ownershipStatus: OwnershipVerificationStatus = OwnershipVerificationStatus.UNKNOWN,
    /** Milestone 9: populated only when [ownershipStatus] is [OwnershipVerificationStatus.UNKNOWN] — a short, specific reason (e.g. "target package not yet resolvable", "INVALID_UID after bounded retry", "SecurityException") distinct from the status itself, so an unknown result is diagnosable rather than a bare null. */
    val ownershipFailureReason: String? = null,
    val timestamp: Instant = Instant.now(),
    val protocol: TrafficProtocol = TrafficProtocol.HTTP,
    val host: String = "",
    val port: Int = 80,
    val url: String = "",
    val method: String = "GET",
    val statusCode: Int? = null,
    val statusMessage: String? = null,
    val contentType: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val requestBody: String? = null,
    val responseBody: String? = null,
    val requestBodyBytes: Long = 0L,
    val responseBodyBytes: Long = 0L,
    val durationMs: Long = 0L,
    val state: TrafficCaptureState = TrafficCaptureState.DECODED,
    val failureDetails: String? = null,
    val isTruncated: Boolean = false,
    val webSocketSession: WebSocketSessionData? = null,
    val streamId: Int? = null,
    val grpcSession: GrpcSessionData? = null,
    val sseSession: SseSessionData? = null,
    val quicDetails: QuicObservationData? = null
)
