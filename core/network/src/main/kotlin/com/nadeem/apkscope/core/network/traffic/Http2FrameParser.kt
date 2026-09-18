package com.nadeem.apkscope.core.network.traffic

import okhttp3.internal.http2.Header
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/**
 * Parsed pseudo-headers and standard metadata for an HTTP/2 stream request or response.
 */
data class Http2Headers(
    val method: String? = null,
    val path: String? = null,
    val scheme: String? = null,
    val authority: String? = null,
    val status: Int? = null,
    val headers: Map<String, String> = emptyMap()
)

/**
 * State and accumulated data for an active or completed multiplexed HTTP/2 stream.
 */
class Http2StreamTracker(
    val streamId: Int,
    val startTime: Long = System.currentTimeMillis()
) {
    val recordId: String = UUID.randomUUID().toString()

    var method: String? = null
    var path: String? = null
    var scheme: String? = null
    var authority: String? = null

    val requestHeaders = mutableMapOf<String, String>()
    val responseHeaders = mutableMapOf<String, String>()
    val responseTrailers = mutableMapOf<String, String>()

    var statusCode: Int? = null
    var statusMessage: String? = null

    var requestBytes: Long = 0L
    var responseBytes: Long = 0L

    private val maxPreviewBytes = TrafficInspectionStore.MAX_BODY_BYTES
    val requestBodyPreview = ByteArrayOutputStream()
    val responseBodyPreview = ByteArrayOutputStream()
    var requestTruncated = false
    var responseTruncated = false

    var isRequestFinished = false
    var isResponseFinished = false
    var isReset = false
    var resetErrorCode: String? = null

    // Protocol identification
    var isGrpc = false
    var grpcServiceName: String = ""
    var grpcMethodName: String = ""
    val grpcMessages = mutableListOf<GrpcMessage>()
    var grpcRequestDecoder: GrpcMessageDecoder? = null
    var grpcResponseDecoder: GrpcMessageDecoder? = null

    var isSse = false
    val sseEvents = mutableListOf<SseEvent>()
    var sseParser: SseEventParser? = null

    @Synchronized
    fun appendRequestBody(bytes: ByteArray, offset: Int, length: Int) {
        requestBytes += length
        if (requestBodyPreview.size() < maxPreviewBytes) {
            val toWrite = minOf(length, maxPreviewBytes - requestBodyPreview.size())
            requestBodyPreview.write(bytes, offset, toWrite)
            if (toWrite < length) requestTruncated = true
        } else {
            requestTruncated = true
        }
    }

    @Synchronized
    fun appendResponseBody(bytes: ByteArray, offset: Int, length: Int) {
        responseBytes += length
        if (responseBodyPreview.size() < maxPreviewBytes) {
            val toWrite = minOf(length, maxPreviewBytes - responseBodyPreview.size())
            responseBodyPreview.write(bytes, offset, toWrite)
            if (toWrite < length) responseTruncated = true
        } else {
            responseTruncated = true
        }
    }

    fun toTrafficRecord(
        defaultHost: String, defaultPort: Int, isTls: Boolean, sessionId: String? = null, targetPackage: String? = null,
        ownership: OwnershipVerification = OwnershipVerification.UNVERIFIED,
    ): TrafficRecord {
        val effectiveHost = authority?.substringBefore(':') ?: defaultHost
        val effectivePort = authority?.substringAfter(':', "")?.toIntOrNull() ?: defaultPort
        val effectiveScheme = scheme ?: if (isTls) "https" else "http"
        val portSuffix = if ((effectiveScheme == "https" && effectivePort == 443) || (effectiveScheme == "http" && effectivePort == 80)) "" else ":$effectivePort"
        val effectivePath = path ?: "/"
        val fullUrl = "$effectiveScheme://$effectiveHost$portSuffix$effectivePath"

        val duration = System.currentTimeMillis() - startTime

        // Determine protocol
        val protocol = when {
            isGrpc -> TrafficProtocol.GRPC
            isSse -> TrafficProtocol.SSE
            isTls -> TrafficProtocol.HTTPS_2
            else -> TrafficProtocol.HTTP_2
        }

        val captureState = when {
            isReset -> TrafficCaptureState.ERROR
            requestTruncated || responseTruncated -> TrafficCaptureState.TRUNCATED
            else -> TrafficCaptureState.DECODED
        }

        val failure = if (isReset) "Stream reset with $resetErrorCode" else null

        val reqBodyString = if (requestBodyPreview.size() > 0) {
            String(requestBodyPreview.toByteArray(), StandardCharsets.UTF_8)
        } else null

        val respBodyString = if (responseBodyPreview.size() > 0) {
            String(responseBodyPreview.toByteArray(), StandardCharsets.UTF_8)
        } else null

        val grpcSessionData = if (isGrpc) {
            val statusVal = responseTrailers["grpc-status"]?.toIntOrNull()
                ?: responseHeaders["grpc-status"]?.toIntOrNull()
            val statusMsg = responseTrailers["grpc-message"]
                ?: responseHeaders["grpc-message"]
            GrpcSessionData(
                serviceName = grpcServiceName,
                methodName = grpcMethodName,
                messages = synchronized(grpcMessages) { ArrayList(grpcMessages) },
                grpcStatus = statusVal,
                grpcStatusName = statusVal?.let { GrpcMessageDecoder.getStatusName(it) },
                grpcMessage = statusMsg,
                trailers = responseTrailers
            )
        } else null

        val sseSessionData = if (isSse) {
            SseSessionData(
                events = synchronized(sseEvents) { ArrayList(sseEvents) },
                isLive = !isResponseFinished,
                lastEventId = sseEvents.lastOrNull { it.id != null }?.id
            )
        } else null

        return TrafficRecord(
            id = recordId,
            sessionId = sessionId,
            targetPackage = targetPackage,
            observedOwnerUid = ownership.observedOwnerUid,
            ownershipStatus = ownership.status,
            ownershipFailureReason = ownership.failureReason,
            timestamp = Instant.ofEpochMilli(startTime),
            protocol = protocol,
            host = effectiveHost,
            port = effectivePort,
            url = fullUrl,
            method = method ?: "GET",
            statusCode = statusCode,
            statusMessage = statusMessage,
            contentType = responseHeaders["content-type"] ?: requestHeaders["content-type"],
            requestHeaders = requestHeaders,
            responseHeaders = responseHeaders,
            requestBody = reqBodyString,
            responseBody = respBodyString,
            requestBodyBytes = requestBytes,
            responseBodyBytes = responseBytes,
            durationMs = duration,
            state = captureState,
            failureDetails = failure,
            isTruncated = requestTruncated || responseTruncated,
            streamId = streamId,
            grpcSession = grpcSessionData,
            sseSession = sseSessionData
        )
    }
}

/**
 * Utility functions for extracting and parsing HTTP/2 headers from OkHttp Header lists.
 */
object Http2FrameParser {

    fun parseHeaders(headerBlock: List<Header>): Http2Headers {
        var method: String? = null
        var path: String? = null
        var scheme: String? = null
        var authority: String? = null
        var status: Int? = null
        val headers = mutableMapOf<String, String>()

        for (h in headerBlock) {
            val name = h.name.utf8()
            val value = h.value.utf8()

            when (name) {
                ":method" -> method = value
                ":path" -> path = value
                ":scheme" -> scheme = value
                ":authority" -> authority = value
                ":status" -> status = value.toIntOrNull()
                else -> {
                    // Lowercase header name per HTTP/2 spec
                    headers[name.lowercase()] = value
                }
            }
        }

        return Http2Headers(
            method = method,
            path = path,
            scheme = scheme,
            authority = authority,
            status = status,
            headers = headers
        )
    }
}
