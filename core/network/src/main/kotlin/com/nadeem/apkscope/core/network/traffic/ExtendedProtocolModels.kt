package com.nadeem.apkscope.core.network.traffic

import java.time.Instant

/**
 * Directional flow for a message in a multiplexed stream or session.
 */
enum class StreamDirection {
    CLIENT_TO_SERVER,
    SERVER_TO_CLIENT
}

/**
 * A discrete gRPC message within an HTTP/2 stream.
 */
data class GrpcMessage(
    val sequence: Int,
    val direction: Direction,
    val timestamp: Instant = Instant.now(),
    val isCompressed: Boolean = false,
    val length: Int = 0,
    val payloadPreview: String = "",
    val isBinary: Boolean = true
)

/**
 * Session data for a decoded gRPC unary or streaming call over HTTP/2.
 */
data class GrpcSessionData(
    val serviceName: String,
    val methodName: String,
    val messages: List<GrpcMessage> = emptyList(),
    val grpcStatus: Int? = null,
    val grpcStatusName: String? = null,
    val grpcMessage: String? = null,
    val trailers: Map<String, String> = emptyMap()
) {
    val isStreaming: Boolean get() = messages.size > 2
}

/**
 * A discrete event received from a Server-Sent Events (text/event-stream) response.
 */
data class SseEvent(
    val id: String? = null,
    val eventType: String = "message",
    val data: String = "",
    val retryMs: Long? = null,
    val isComment: Boolean = false,
    val timestamp: Instant = Instant.now()
)

/**
 * Stream state and accumulated event history for a Server-Sent Events stream.
 */
data class SseSessionData(
    val events: List<SseEvent> = emptyList(),
    val isLive: Boolean = true,
    val lastEventId: String? = null
)

/**
 * Observational transport telemetry for QUIC packets on UDP port 443.
 */
data class QuicObservationData(
    val version: String,
    val packetType: String,
    val destinationConnectionIdHex: String,
    val sourceConnectionIdHex: String? = null,
    val isConfirmedHttp3: Boolean = false
)
