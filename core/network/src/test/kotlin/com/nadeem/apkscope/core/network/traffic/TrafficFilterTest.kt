package com.nadeem.apkscope.core.network.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class TrafficFilterTest {

    @Before
    fun setup() {
        TrafficInspectionStore.clear()
    }

    @Test
    fun testRecordRetainsSecretsAndRedactedPresentationHidesThem() {
        val record = TrafficRecord(
            protocol = TrafficProtocol.HTTP,
            host = "api.example.com",
            port = 80,
            url = "http://api.example.com/login?token=my_secret_token_123",
            method = "POST",
            statusCode = 200,
            requestHeaders = mapOf(
                "Authorization" to "Bearer sensitive_credential",
                "User-Agent" to "TestClient/1.0"
            ),
            requestBody = """{"password":"super_secret_pw","username":"alice"}""",
            responseBody = "OK"
        )

        val recorded = TrafficInspectionStore.record(record)

        assertEquals("Bearer sensitive_credential", recorded.requestHeaders["Authorization"])
        assertEquals("TestClient/1.0", recorded.requestHeaders["User-Agent"])
        assertTrue(recorded.url.contains("token=my_secret_token_123"))
        assertTrue(recorded.requestBody!!.contains("\"password\":\"super_secret_pw\""))
        val redacted = TrafficInspectionStore.redacted(recorded)
        assertEquals("[REDACTED]", redacted.requestHeaders["Authorization"])
        assertTrue(redacted.url.contains("token=[REDACTED]"))
        assertTrue(redacted.requestBody!!.contains("\"password\":\"[REDACTED]\""))
    }

    @Test
    fun testTruncationCapAt64KiB() {
        val largeString = "A".repeat(70 * 1024)
        val record = TrafficRecord(
            protocol = TrafficProtocol.HTTP,
            host = "large.example.com",
            port = 80,
            url = "http://large.example.com/data",
            responseBody = largeString
        )

        val recorded = TrafficInspectionStore.record(record)

        assertTrue("Payload > 64 KiB must be truncated", recorded.isTruncated)
        assertEquals(TrafficCaptureState.TRUNCATED, recorded.state)
        assertTrue(recorded.responseBody!!.contains("[TRUNCATED: Exceeded 64 KiB preview limit]"))
    }

    @Test
    fun updatedBodiesCannotBypassPreviewLimit() {
        TrafficInspectionStore.record(TrafficRecord(id = "large-update", host = "example.com"))
        val updated = TrafficInspectionStore.updateRecord("large-update") {
            it.copy(responseBody = "x".repeat(100_000), state = TrafficCaptureState.DECODED)
        }!!
        assertTrue(updated.isTruncated)
        assertEquals(TrafficCaptureState.TRUNCATED, updated.state)
        assertTrue(updated.responseBody!!.toByteArray().size <= TrafficInspectionStore.MAX_BODY_BYTES)
        assertEquals(updated, TrafficInspectionStore.get("large-update"))
    }

    @Test
    fun truncationPreservesUnicodeAndIsIdempotent() {
        for (character in listOf("€", "😀", "a")) {
            val (body, truncated) = TrafficInspectionStore.truncateBody(character.repeat(70_000))
            assertTrue(truncated)
            assertTrue(body!!.toByteArray().size <= TrafficInspectionStore.MAX_BODY_BYTES)
            assertEquals(body, String(body.toByteArray(), Charsets.UTF_8))
            assertEquals(body to false, TrafficInspectionStore.truncateBody(body))
        }
    }

    @Test
    fun redactionPreservesLiteralReplacementCharactersInKeys() {
        val body = """{"token${'$'}1":"secret","auth\\key":"private"}"""
        val redacted = TrafficInspectionStore.redacted(TrafficRecord(host = "example.com", responseBody = body))
        assertEquals("""{"token${'$'}1":"[REDACTED]","auth\\key":"[REDACTED]"}""", redacted.responseBody)
    }

    @Test
    fun searchShortCircuitsAfterCaseInsensitiveUrlMatch() {
        val unreadableHeaders = object : AbstractMap<String, String>() {
            override val entries: Set<Map.Entry<String, String>>
                get() = error("Headers must not be scanned when URL already matches")
        }
        val record = TrafficRecord(host = "example.com", url = "https://example.com/Found", requestHeaders = unreadableHeaders)
        assertEquals(listOf(record), TrafficInspectionStore.filter(TrafficFilterQuery(searchText = "FOUND"), listOf(record)))
    }

    @Test
    fun testMultiAttributeFiltering() {
        val r1 = TrafficRecord(
            host = "auth.service.com",
            protocol = TrafficProtocol.HTTPS,
            method = "POST",
            statusCode = 200,
            state = TrafficCaptureState.DECODED,
            url = "https://auth.service.com/token"
        )
        val r2 = TrafficRecord(
            host = "api.service.com",
            protocol = TrafficProtocol.HTTP,
            method = "GET",
            statusCode = 404,
            state = TrafficCaptureState.DECODED,
            url = "http://api.service.com/items"
        )
        val r3 = TrafficRecord(
            host = "stream.service.com",
            protocol = TrafficProtocol.WS,
            method = "GET",
            statusCode = 101,
            state = TrafficCaptureState.DECODED,
            url = "ws://stream.service.com/events",
            webSocketSession = WebSocketSessionData(
                messages = listOf(
                    WebSocketMessage(
                        sequence = 1,
                        direction = Direction.OUTBOUND,
                        type = MessageType.TEXT,
                        payloadPreview = "hello websocket"
                    )
                )
            )
        )

        val list = listOf(r1, r2, r3)

        // Filter by Protocol
        val httpOnly = TrafficInspectionStore.filter(TrafficFilterQuery(protocol = TrafficProtocol.HTTP), list)
        assertEquals(1, httpOnly.size)
        assertEquals("api.service.com", httpOnly[0].host)

        // Filter by Method
        val postOnly = TrafficInspectionStore.filter(TrafficFilterQuery(method = "POST"), list)
        assertEquals(1, postOnly.size)
        assertEquals("auth.service.com", postOnly[0].host)

        // Filter by Status Code
        val status404 = TrafficInspectionStore.filter(TrafficFilterQuery(statusCode = 404), list)
        assertEquals(1, status404.size)
        assertEquals(404, status404[0].statusCode)

        // Filter by Host
        val streamHost = TrafficInspectionStore.filter(TrafficFilterQuery(host = "stream"), list)
        assertEquals(1, streamHost.size)
        assertEquals(TrafficProtocol.WS, streamHost[0].protocol)

        // Filter by WebSocket Direction and Message Type
        val wsText = TrafficInspectionStore.filter(TrafficFilterQuery(wsDirection = Direction.OUTBOUND, wsMessageType = MessageType.TEXT), list)
        assertEquals(1, wsText.size)
        assertEquals("stream.service.com", wsText[0].host)

        val wsBinary = TrafficInspectionStore.filter(TrafficFilterQuery(wsMessageType = MessageType.BINARY), list)
        assertTrue(wsBinary.isEmpty())

        // Free text search
        val textSearch = TrafficInspectionStore.filter(TrafficFilterQuery(searchText = "hello websocket"), list)
        assertEquals(1, textSearch.size)
        assertEquals("stream.service.com", textSearch[0].host)

        // Time range filter
        val now = java.time.Instant.now()
        val inPast = r1.copy(timestamp = now.minusSeconds(100))
        val inPresent = r2.copy(timestamp = now)
        val inFuture = r3.copy(timestamp = now.plusSeconds(100))
        val timeList = listOf(inPast, inPresent, inFuture)

        val windowFilter = TrafficInspectionStore.filter(
            TrafficFilterQuery(
                timeRangeStart = now.minusSeconds(10),
                timeRangeEnd = now.plusSeconds(10)
            ),
            timeList
        )
        assertEquals(1, windowFilter.size)
        assertEquals("api.service.com", windowFilter[0].host)
    }

    @Test
    fun testUpdateWebSocketSessionMessages() {
        val initial = TrafficRecord(
            id = "test-ws-1",
            protocol = TrafficProtocol.WS,
            host = "echo.websocket.org",
            port = 80,
            url = "ws://echo.websocket.org/",
            webSocketSession = WebSocketSessionData()
        )
        TrafficInspectionStore.record(initial)

        val msg1 = WebSocketMessage(
            sequence = 1,
            direction = Direction.OUTBOUND,
            type = MessageType.TEXT,
            payloadLength = 5,
            payloadPreview = "ping!"
        )

        val updated = TrafficInspectionStore.updateRecord("test-ws-1") { current ->
            current.copy(
                webSocketSession = current.webSocketSession?.withMessage(msg1)
            )
        }

        assertNotNull(updated)
        assertEquals(1, updated!!.webSocketSession!!.messages.size)
        assertEquals("ping!", updated.webSocketSession!!.messages[0].payloadPreview)
        assertEquals(5L, updated.webSocketSession!!.bytesOut)
    }
}
