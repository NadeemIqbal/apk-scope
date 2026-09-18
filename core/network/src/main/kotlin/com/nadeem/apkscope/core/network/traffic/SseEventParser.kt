package com.nadeem.apkscope.core.network.traffic

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Incremental, memory-bounded parser for Server-Sent Events (RFC / WHATWG EventSource specification).
 *
 * Handles:
 * - Events split across transport buffers / read calls.
 * - Multi-line `data:` fields concatenated with newline characters.
 * - `event:` custom event types.
 * - `id:` event identifiers.
 * - `retry:` reconnection intervals in milliseconds.
 * - `:comment` and heartbeat keep-alive lines.
 * - Memory bounds on line length (max 64 KiB) and max retained events.
 */
class SseEventParser(
    private val maxEventHistory: Int = 500,
    private val maxLineLengthBytes: Int = 64 * 1024,
    private val onEventParsed: (SseEvent) -> Unit
) {
    private val lineBuffer = ByteArrayOutputStream()
    private var isPrevCr = false

    private var currentId: String? = null
    private var lastDispatchedId: String? = null
    private var currentEventType = "message"
    private val currentData = StringBuilder()
    private var currentRetryMs: Long? = null
    private var accumulatedEventCount = 0

    /**
     * Ingest a raw chunk of received bytes from the transport stream.
     */
    @Synchronized
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        for (i in offset until (offset + length)) {
            val b = bytes[i]
            if (b == '\r'.code.toByte()) {
                isPrevCr = true
                processLine(lineBuffer.toByteArray())
                lineBuffer.reset()
            } else if (b == '\n'.code.toByte()) {
                if (isPrevCr) {
                    // Part of CRLF already processed on CR
                    isPrevCr = false
                } else {
                    processLine(lineBuffer.toByteArray())
                    lineBuffer.reset()
                }
            } else {
                isPrevCr = false
                if (lineBuffer.size() < maxLineLengthBytes) {
                    lineBuffer.write(b.toInt())
                }
            }
        }
    }

    /**
     * Ingest a string chunk directly.
     */
    fun feedText(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        feed(bytes, 0, bytes.size)
    }

    /**
     * Complete stream and dispatch any final pending event.
     */
    @Synchronized
    fun finish() {
        if (lineBuffer.size() > 0) {
            processLine(lineBuffer.toByteArray())
            lineBuffer.reset()
        }
        dispatchCurrentEvent()
    }

    private fun processLine(rawBytes: ByteArray) {
        if (rawBytes.isEmpty()) {
            // Empty line triggers event dispatch
            dispatchCurrentEvent()
            return
        }

        val line = String(rawBytes, StandardCharsets.UTF_8)

        // Comment line (starts with colon)
        if (line.startsWith(":")) {
            val commentText = line.removePrefix(":").trimStart()
            if (accumulatedEventCount < maxEventHistory) {
                val commentEvent = SseEvent(
                    id = lastDispatchedId,
                    eventType = "comment",
                    data = commentText,
                    isComment = true,
                    timestamp = Instant.now()
                )
                accumulatedEventCount++
                onEventParsed(commentEvent)
            }
            return
        }

        val colonIndex = line.indexOf(':')
        val field: String
        val value: String
        if (colonIndex != -1) {
            field = line.substring(0, colonIndex).trim()
            val rawValue = line.substring(colonIndex + 1)
            // If value starts with single space, strip it per spec
            value = if (rawValue.startsWith(" ")) rawValue.substring(1) else rawValue
        } else {
            field = line.trim()
            value = ""
        }

        when (field) {
            "data" -> {
                if (currentData.isNotEmpty()) {
                    currentData.append("\n")
                }
                currentData.append(value)
            }
            "event" -> {
                currentEventType = value.ifBlank { "message" }
            }
            "id" -> {
                if (!value.contains('\u0000')) {
                    currentId = value
                    lastDispatchedId = value
                }
            }
            "retry" -> {
                val parsed = value.trim().toLongOrNull()
                if (parsed != null && parsed >= 0) {
                    currentRetryMs = parsed
                }
            }
        }
    }

    private fun dispatchCurrentEvent() {
        if (currentData.isEmpty() && currentEventType == "message" && currentId == null && currentRetryMs == null) {
            // Nothing accumulated
            return
        }

        if (accumulatedEventCount < maxEventHistory) {
            val event = SseEvent(
                id = currentId ?: lastDispatchedId,
                eventType = currentEventType,
                data = currentData.toString(),
                retryMs = currentRetryMs,
                isComment = false,
                timestamp = Instant.now()
            )
            accumulatedEventCount++
            onEventParsed(event)
        }

        // Reset buffer for next event
        currentEventType = "message"
        currentData.setLength(0)
        currentRetryMs = null
        currentId = null
    }
}
