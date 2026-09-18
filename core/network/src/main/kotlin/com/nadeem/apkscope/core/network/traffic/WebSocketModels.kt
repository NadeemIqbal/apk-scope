package com.nadeem.apkscope.core.network.traffic

import java.time.Instant

/**
 * Message flow direction in a WebSocket connection.
 */
enum class Direction {
    /** Client to Server */
    OUTBOUND,
    /** Server to Client */
    INBOUND
}

/**
 * WebSocket opcode message types (RFC 6455).
 */
enum class MessageType {
    TEXT,
    BINARY,
    PING,
    PONG,
    CLOSE
}

/**
 * Individual recorded WebSocket frame or defragmented message.
 */
data class WebSocketMessage(
    val sequence: Int,
    val direction: Direction,
    val type: MessageType,
    val timestamp: Instant = Instant.now(),
    val payloadLength: Long = 0L,
    val payloadPreview: String? = null,
    val isMasked: Boolean = false,
    val isDeflated: Boolean = false,
    val closeCode: Int? = null,
    val closeReason: String? = null,
    val isFragmented: Boolean = false,
    val isReconstructed: Boolean = false,
    val fragmentCount: Int = 1
)

/**
 * Metadata and recorded messages for a WebSocket session.
 */
data class WebSocketSessionData(
    val openedAt: Instant = Instant.now(),
    val closedAt: Instant? = null,
    val closeCode: Int? = null,
    val closeReason: String? = null,
    val messages: List<WebSocketMessage> = emptyList(),
    val bytesIn: Long = 0L,
    val bytesOut: Long = 0L
) {
    fun withMessage(message: WebSocketMessage): WebSocketSessionData {
        val newBytesIn = if (message.direction == Direction.INBOUND) bytesIn + message.payloadLength else bytesIn
        val newBytesOut = if (message.direction == Direction.OUTBOUND) bytesOut + message.payloadLength else bytesOut
        val newClosedAt = if (message.type == MessageType.CLOSE) message.timestamp else closedAt
        val newCloseCode = if (message.type == MessageType.CLOSE) message.closeCode ?: closeCode else closeCode
        val newCloseReason = if (message.type == MessageType.CLOSE) message.closeReason ?: closeReason else closeReason

        return copy(
            closedAt = newClosedAt,
            closeCode = newCloseCode,
            closeReason = newCloseReason,
            messages = messages + message,
            bytesIn = newBytesIn,
            bytesOut = newBytesOut
        )
    }
}
