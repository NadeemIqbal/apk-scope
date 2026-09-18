package com.nadeem.apkscope.core.network.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class SseEventParserTest {

    @Test
    fun testSingleLineDataEvent() {
        val events = mutableListOf<SseEvent>()
        val parser = SseEventParser { events.add(it) }

        parser.feedText("data: hello world\n\n")

        assertEquals(1, events.size)
        val evt = events[0]
        assertEquals("message", evt.eventType)
        assertEquals("hello world", evt.data)
        assertFalse(evt.isComment)
    }

    @Test
    fun testMultiLineDataEvent() {
        val events = mutableListOf<SseEvent>()
        val parser = SseEventParser { events.add(it) }

        parser.feedText("data: Y1\r\ndata: Y2\r\ndata: Y3\r\n\r\n")

        assertEquals(1, events.size)
        val evt = events[0]
        assertEquals("Y1\nY2\nY3", evt.data)
    }

    @Test
    fun testCustomEventTypeAndId() {
        val events = mutableListOf<SseEvent>()
        val parser = SseEventParser { events.add(it) }

        parser.feedText("event: alert\nid: evt-999\nretry: 3500\ndata: high memory usage\n\n")

        assertEquals(1, events.size)
        val evt = events[0]
        assertEquals("alert", evt.eventType)
        assertEquals("evt-999", evt.id)
        assertEquals(3500L, evt.retryMs)
        assertEquals("high memory usage", evt.data)
    }

    @Test
    fun testCommentsAndHeartbeats() {
        val events = mutableListOf<SseEvent>()
        val parser = SseEventParser { events.add(it) }

        parser.feedText(": heartbeat ping\n\n")

        assertEquals(1, events.size)
        val evt = events[0]
        assertTrue(evt.isComment)
        assertEquals("heartbeat ping", evt.data)
    }

    @Test
    fun testChunkedArrivalAcrossBuffers() {
        val events = mutableListOf<SseEvent>()
        val parser = SseEventParser { events.add(it) }

        val raw = "id: 1\ndata: fragmented data line\n\n"
        // Feed byte by byte
        val bytes = raw.toByteArray(StandardCharsets.UTF_8)
        for (b in bytes) {
            parser.feed(byteArrayOf(b))
        }

        assertEquals(1, events.size)
        val evt = events[0]
        assertEquals("1", evt.id)
        assertEquals("fragmented data line", evt.data)
    }

    @Test
    fun testLineLengthBoundSafety() {
        val events = mutableListOf<SseEvent>()
        val parser = SseEventParser(maxLineLengthBytes = 64) { events.add(it) }

        val hugeString = "data: " + "A".repeat(200) + "\n\n"
        parser.feedText(hugeString)

        assertEquals(1, events.size)
        assertTrue(events[0].data.length <= 64)
    }
}
