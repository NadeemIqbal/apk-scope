package com.nadeem.apkscope.core.network.traffic

import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.Header
import okhttp3.internal.http2.Hpack
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Incrementally decodes plaintext HTTP/2 bytes captured after TLS. A decoder belongs to one
 * connection: HPACK dynamic tables are directional and must never be shared between connections.
 */
class LiveHttp2ConnectionDecoder(
    private val defaultHost: String,
    private val sessionId: String?,
    private val targetPackage: String?,
    private val onRecord: (TrafficRecord) -> Unit,
) {
    private data class DirectionState(
        val pending: ByteArrayOutputStream = ByteArrayOutputStream(),
        val hpackInput: Buffer = Buffer(),
        val hpackReader: Hpack.Reader = Hpack.Reader(hpackInput, 4096),
        val headerBlocks: MutableMap<Int, ByteArrayOutputStream> = mutableMapOf(),
    )

    private val outbound = DirectionState()
    private val inbound = DirectionState()
    private val streams = ConcurrentHashMap<Int, Http2StreamTracker>()

    @Synchronized
    fun feed(clientToServer: Boolean, bytes: ByteArray) {
        val state = if (clientToServer) outbound else inbound
        state.pending.write(bytes)
        var available = state.pending.toByteArray()
        var offset = 0
        if (clientToServer && available.startsWith(PREFACE)) offset = PREFACE.size

        while (available.size - offset >= 9) {
            val length = ((available[offset].toInt() and 0xff) shl 16) or
                ((available[offset + 1].toInt() and 0xff) shl 8) or (available[offset + 2].toInt() and 0xff)
            if (length > MAX_FRAME_SIZE || available.size - offset < 9 + length) break
            val type = available[offset + 3].toInt() and 0xff
            val flags = available[offset + 4].toInt() and 0xff
            val streamId = ((available[offset + 5].toInt() and 0x7f) shl 24) or
                ((available[offset + 6].toInt() and 0xff) shl 16) or
                ((available[offset + 7].toInt() and 0xff) shl 8) or (available[offset + 8].toInt() and 0xff)
            val payload = available.copyOfRange(offset + 9, offset + 9 + length)
            handleFrame(state, clientToServer, type, flags, streamId, payload)
            offset += 9 + length
        }

        state.pending.reset()
        if (offset < available.size) state.pending.write(available, offset, available.size - offset)
    }

    private fun handleFrame(state: DirectionState, clientToServer: Boolean, type: Int, flags: Int, streamId: Int, payload: ByteArray) {
        when (type) {
            TYPE_HEADERS -> appendHeaders(state, clientToServer, flags, streamId, headerFragment(flags, payload))
            TYPE_CONTINUATION -> appendHeaders(state, clientToServer, flags, streamId, payload)
            TYPE_DATA -> {
                val body = dataFragment(flags, payload)
                val tracker = streams[streamId] ?: return
                if (clientToServer) {
                    tracker.appendRequestBody(body, 0, body.size)
                    if (flags and FLAG_END_STREAM != 0) tracker.isRequestFinished = true
                } else {
                    tracker.appendResponseBody(body, 0, body.size)
                    if (flags and FLAG_END_STREAM != 0) tracker.isResponseFinished = true
                }
                onRecord(tracker.toTrafficRecord(defaultHost, 443, true, sessionId, targetPackage))
            }
            TYPE_RST_STREAM -> streams[streamId]?.let { tracker ->
                tracker.isReset = true
                tracker.resetErrorCode = if (payload.size >= 4) ErrorCode.fromHttp2(readInt(payload, 0))?.name ?: "UNKNOWN" else "UNKNOWN"
                onRecord(tracker.toTrafficRecord(defaultHost, 443, true, sessionId, targetPackage))
            }
        }
    }

    private fun appendHeaders(state: DirectionState, clientToServer: Boolean, flags: Int, streamId: Int, fragment: ByteArray) {
        val block = state.headerBlocks.getOrPut(streamId) { ByteArrayOutputStream() }
        block.write(fragment)
        if (flags and FLAG_END_HEADERS == 0) return
        state.headerBlocks.remove(streamId)
        val headers = decodeHeaders(state, block.toByteArray()) ?: return
        val parsed = Http2FrameParser.parseHeaders(headers)
        val tracker = streams.computeIfAbsent(streamId) { Http2StreamTracker(streamId) }
        if (clientToServer && parsed.method != null) {
            tracker.method = parsed.method
            tracker.path = parsed.path
            tracker.scheme = parsed.scheme
            tracker.authority = parsed.authority
            tracker.requestHeaders.putAll(parsed.headers)
        } else if (!clientToServer && parsed.status != null) {
            tracker.statusCode = parsed.status
            tracker.responseHeaders.putAll(parsed.headers)
        } else if (!clientToServer) {
            tracker.responseTrailers.putAll(parsed.headers)
        }
        if (flags and FLAG_END_STREAM != 0) {
            if (clientToServer) tracker.isRequestFinished = true else tracker.isResponseFinished = true
        }
        onRecord(tracker.toTrafficRecord(defaultHost, 443, true, sessionId, targetPackage))
    }

    private fun decodeHeaders(state: DirectionState, bytes: ByteArray): List<Header>? = try {
        state.hpackInput.write(bytes)
        state.hpackReader.readHeaders()
        state.hpackReader.getAndResetHeaderList()
    } catch (_: Exception) {
        null
    }

    private fun headerFragment(flags: Int, payload: ByteArray): ByteArray {
        var start = 0
        var end = payload.size
        if (flags and FLAG_PADDED != 0 && payload.isNotEmpty()) { val padding = payload[0].toInt() and 0xff; start++; end -= padding }
        if (flags and FLAG_PRIORITY != 0) start += 5
        return payload.copyOfRange(start.coerceAtMost(end), end.coerceAtLeast(start))
    }

    private fun dataFragment(flags: Int, payload: ByteArray): ByteArray {
        if (flags and FLAG_PADDED == 0 || payload.isEmpty()) return payload
        val padding = payload[0].toInt() and 0xff
        return payload.copyOfRange(1, (payload.size - padding).coerceAtLeast(1))
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private companion object {
        val PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray(Charsets.US_ASCII)
        const val MAX_FRAME_SIZE = 16 * 1024 * 1024
        const val TYPE_DATA = 0
        const val TYPE_HEADERS = 1
        const val TYPE_RST_STREAM = 3
        const val TYPE_CONTINUATION = 9
        const val FLAG_END_STREAM = 0x1
        const val FLAG_END_HEADERS = 0x4
        const val FLAG_PADDED = 0x8
        const val FLAG_PRIORITY = 0x20
    }
}
