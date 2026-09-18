package com.nadeem.apkscope.core.network.traffic

import okhttp3.internal.http2.Header
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Http2FrameParserTest {

    @Test
    fun testHeaderParsingAndPseudoHeaders() {
        val headers = listOf(
            Header(":method", "POST"),
            Header(":path", "/api/v2/items"),
            Header(":scheme", "https"),
            Header(":authority", "api.example.com:8443"),
            Header("content-type", "application/json"),
            Header("accept", "*/*")
        )

        val parsed = Http2FrameParser.parseHeaders(headers)

        assertEquals("POST", parsed.method)
        assertEquals("/api/v2/items", parsed.path)
        assertEquals("https", parsed.scheme)
        assertEquals("api.example.com:8443", parsed.authority)
        assertEquals("application/json", parsed.headers["content-type"])
        assertEquals("*/*", parsed.headers["accept"])
    }

    @Test
    fun testResponseHeadersAndStatus() {
        val headers = listOf(
            Header(":status", "200"),
            Header("content-type", "text/plain"),
            Header("server", "nginx/1.24")
        )

        val parsed = Http2FrameParser.parseHeaders(headers)

        assertEquals(200, parsed.status)
        assertEquals("text/plain", parsed.headers["content-type"])
        assertEquals("nginx/1.24", parsed.headers["server"])
    }

    @Test
    fun testHttp2StreamTrackerLifecycle() {
        val tracker = Http2StreamTracker(streamId = 1)
        tracker.method = "GET"
        tracker.path = "/test"
        tracker.scheme = "https"
        tracker.authority = "example.com"
        tracker.requestHeaders["user-agent"] = "TestAgent"

        val reqBytes = "sample request".toByteArray()
        tracker.appendRequestBody(reqBytes, 0, reqBytes.size)

        tracker.statusCode = 200
        tracker.statusMessage = "OK"
        tracker.responseHeaders["content-type"] = "text/html"

        val respBytes = "<html>hello</html>".toByteArray()
        tracker.appendResponseBody(respBytes, 0, respBytes.size)

        tracker.isRequestFinished = true
        tracker.isResponseFinished = true

        val record = tracker.toTrafficRecord(defaultHost = "example.com", defaultPort = 443, isTls = true)

        assertEquals(1, record.streamId)
        assertEquals(TrafficProtocol.HTTPS_2, record.protocol)
        // Default HTTPS port is omitted from the URL, matching standard URL conventions.
        assertEquals("https://example.com/test", record.url)
        assertEquals("GET", record.method)
        assertEquals(200, record.statusCode)
        assertEquals("sample request", record.requestBody)
        assertEquals("<html>hello</html>", record.responseBody)
        assertEquals(TrafficCaptureState.DECODED, record.state)
        assertFalse(record.isTruncated)
    }

    @Test
    fun testHttp2StreamTrackerWithGrpc() {
        val tracker = Http2StreamTracker(streamId = 3)
        tracker.method = "POST"
        tracker.path = "/helloworld.Greeter/SayHello"
        tracker.authority = "grpc.example.com"
        tracker.isGrpc = true
        tracker.grpcServiceName = "helloworld.Greeter"
        tracker.grpcMethodName = "SayHello"

        tracker.statusCode = 200
        tracker.responseHeaders["content-type"] = "application/grpc"
        tracker.responseTrailers["grpc-status"] = "0"
        tracker.responseTrailers["grpc-message"] = "Success"

        tracker.grpcMessages.add(
            GrpcMessage(sequence = 1, direction = Direction.OUTBOUND, length = 10, payloadPreview = "req")
        )
        tracker.grpcMessages.add(
            GrpcMessage(sequence = 2, direction = Direction.INBOUND, length = 15, payloadPreview = "resp")
        )

        val record = tracker.toTrafficRecord(defaultHost = "grpc.example.com", defaultPort = 443, isTls = true)

        assertEquals(TrafficProtocol.GRPC, record.protocol)
        assertNotNull(record.grpcSession)
        val grpc = record.grpcSession!!
        assertEquals("helloworld.Greeter", grpc.serviceName)
        assertEquals("SayHello", grpc.methodName)
        assertEquals(0, grpc.grpcStatus)
        assertEquals("OK", grpc.grpcStatusName)
        assertEquals(2, grpc.messages.size)
    }

    @Test
    fun testHttp2StreamTrackerWithSse() {
        val tracker = Http2StreamTracker(streamId = 5)
        tracker.method = "GET"
        tracker.path = "/events"
        tracker.authority = "sse.example.com"
        tracker.isSse = true
        tracker.statusCode = 200
        tracker.responseHeaders["content-type"] = "text/event-stream"

        tracker.sseEvents.add(
            SseEvent(id = "1", eventType = "tick", data = "val: 42")
        )

        val record = tracker.toTrafficRecord(defaultHost = "sse.example.com", defaultPort = 443, isTls = true)

        assertEquals(TrafficProtocol.SSE, record.protocol)
        assertNotNull(record.sseSession)
        assertEquals(1, record.sseSession!!.events.size)
        assertEquals("1", record.sseSession!!.lastEventId)
    }

    @Test
    fun testStreamResetHandling() {
        val tracker = Http2StreamTracker(streamId = 7)
        tracker.method = "POST"
        tracker.path = "/cancel"
        tracker.isReset = true
        tracker.resetErrorCode = "CANCEL"

        val record = tracker.toTrafficRecord(defaultHost = "example.com", defaultPort = 443, isTls = true)

        assertEquals(TrafficCaptureState.ERROR, record.state)
        assertTrue(record.failureDetails?.contains("CANCEL") == true)
    }

    @Test
    fun testBoundedBodyPreview() {
        val tracker = Http2StreamTracker(streamId = 9)
        val chunk = ByteArray(1024) { 'A'.code.toByte() }
        // Append 70 KiB (exceeding 64 KiB cap)
        for (i in 0 until 70) {
            tracker.appendResponseBody(chunk, 0, chunk.size)
        }

        val record = tracker.toTrafficRecord(defaultHost = "example.com", defaultPort = 443, isTls = true)

        assertTrue("Must be marked truncated", record.isTruncated)
        assertEquals(TrafficCaptureState.TRUNCATED, record.state)
        assertTrue("Body preview must be capped at 64 KiB", tracker.responseBodyPreview.size() <= 64 * 1024)
        assertEquals(70 * 1024L, record.responseBodyBytes)
    }
}
