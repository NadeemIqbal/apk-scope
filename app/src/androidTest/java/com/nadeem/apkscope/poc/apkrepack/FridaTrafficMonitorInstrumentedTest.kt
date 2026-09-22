package com.nadeem.apkscope.poc.apkrepack

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import com.nadeem.apkscope.core.network.traffic.TrafficCaptureState
import com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore

/** Real Android sockets and production channel state; does not claim injected JS execution. */
@RunWith(AndroidJUnit4::class)
class FridaTrafficMonitorInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val monitor = FridaTrafficMonitor(listenPort = 0)
    private val sessions = mutableListOf<String>()
    private val target = "com.apksandbox.fixture"
    private val token = "ab".repeat(32)

    private fun startSession(): String {
        val session = "channel-test-${UUID.randomUUID()}"
        sessions.add(session)
        FridaChannelConfig.storeToken(context, session, target, token)
        monitor.start(context, session, target)
        return session
    }

    private suspend fun readyListener() = withTimeout(5_000) {
        monitor.status.first { it.isListening }
    }

    private fun hello(socket: Socket, pid: Int, helloToken: String = token, pkg: String = target) {
        socket.soTimeout = 5_000
        val message = JSONObject().put("type", "hello").put("version", FridaChannelConfig.PROTOCOL_VERSION)
            .put("pkg", pkg).put("pid", pid).put("token", helloToken)
        socket.getOutputStream().write((message.toString() + "\n").toByteArray())
        socket.getOutputStream().flush()
    }

    private suspend fun readyTarget(pid: Int) = withTimeout(5_000) {
        monitor.status.first { it.commandReady && it.connectedPid == pid }
    }

    private suspend fun traffic(socket: Socket, direction: String, bytes: ByteArray) {
        val before = monitor.status.value.capturedCount
        val frame = JSONObject().put("type", "traffic").put("pkg", target)
            .put("dir", direction).put("conn", "reused-native-handle").put("ts", 123456L)
            .put("len", bytes.size).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        socket.getOutputStream().write((frame.toString() + "\n").toByteArray())
        withTimeout(5_000) { monitor.status.first { it.capturedCount > before } }
    }

    @Test
    fun reconnectAndNewSessionCannotAttachResponsesToOldRequests() = runBlocking {
        for (replaceSession in listOf(false, true)) {
            val firstSession = startSession()
            val port = readyListener().port
            Socket("127.0.0.1", port).use { first ->
                hello(first, 201)
                readyTarget(201)
                traffic(first, "out", "GET /old HTTP/1.1\r\nHost: old.example\r\n\r\n".toByteArray())
            }
            withTimeout(5_000) { monitor.status.first { !it.commandReady } }
            val oldRecord = TrafficInspectionStore.all().single { it.sessionId == firstSession }
            val nextSession = if (replaceSession) startSession() else firstSession
            Socket("127.0.0.1", port).use { second ->
                hello(second, 202)
                readyTarget(202)
                traffic(second, "in", "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nnew".toByteArray())
                assertNull("Old request must remain unanswered", TrafficInspectionStore.get(oldRecord.id)!!.statusCode)
                val unmatched = TrafficInspectionStore.all().single { it.sessionId == nextSession && it.statusCode == 200 }
                assertEquals(TrafficCaptureState.UNSUPPORTED_PROTOCOL, unmatched.state)
                assertNotEquals(oldRecord.id, unmatched.id)
                traffic(second, "out", "GET /new HTTP/1.1\r\nHost: new.example\r\n\r\n".toByteArray())
                val nextRequest = TrafficInspectionStore.all().single { it.sessionId == nextSession && it.url == "https://new.example/new" }
                assertNotEquals(oldRecord.id, nextRequest.id) // identical native handle/timestamp
            }
            monitor.stop()
        }
    }

    @Test
    fun streamedAndCompressedBodiesPublishTruthfulTruncationAndByteCounts() = runBlocking {
        val session = startSession()
        val port = readyListener().port
        Socket("127.0.0.1", port).use { socket ->
            hello(socket, 203)
            readyTarget(203)
            traffic(socket, "out", "GET /large HTTP/1.1\r\nHost: test.example\r\n\r\n".toByteArray())
            traffic(socket, "in", "HTTP/1.1 200 OK\r\nContent-Length: 120000\r\n\r\n".toByteArray())
            repeat(3) { traffic(socket, "in", ByteArray(40_000) { 65 }) }
            val large = TrafficInspectionStore.all().single { it.sessionId == session }
            assertEquals(120_000L, large.responseBodyBytes)
            assertEquals(64 * 1024, large.responseBody!!.length)
            assertTrue(large.isTruncated)
            assertEquals(TrafficCaptureState.TRUNCATED, large.state)

            val output = ByteArrayOutputStream()
            GZIPOutputStream(output).use { it.write(ByteArray(2 * 1024 * 1024)) }
            val compressed = output.toByteArray()
            traffic(socket, "out", "GET /compressed HTTP/1.1\r\nHost: test.example\r\n\r\n".toByteArray())
            traffic(socket, "in", "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\n".toByteArray() + compressed)
            val record = TrafficInspectionStore.all().single { it.sessionId == session && it.url.endsWith("/compressed") }
            assertEquals(compressed.size.toLong(), record.responseBodyBytes)
            assertTrue(record.responseBody!!.startsWith("[Binary Payload:"))
            assertTrue(record.isTruncated)
            assertEquals(TrafficCaptureState.TRUNCATED, record.state)
        }
    }

    @After
    fun cleanup() {
        monitor.stop()
        sessions.forEach { FridaChannelConfig.clearToken(context, it) }
    }

    @Test
    fun rapidStopStartDoesNotLetOldServerCloseItsReplacement() = runBlocking {
        repeat(20) {
            startSession()
            readyListener()
            monitor.stop()
            startSession()
            val status = readyListener()
            Socket("127.0.0.1", status.port).use { socket ->
                hello(socket, it + 1)
                readyTarget(it + 1)
                assertTrue(monitor.sendScript("1 + 1").accepted)
                assertEquals("1 + 1", JSONObject(socket.getInputStream().bufferedReader().readLine()).getString("source"))
            }
            monitor.stop()
        }
    }

    @Test
    fun sessionReplacementKeepsNewConnectionReadyAndReturnsResult() = runBlocking {
        startSession()
        val port = readyListener().port
        var current = Socket("127.0.0.1", port)
        try {
            hello(current, 1)
            readyTarget(1)
            repeat(20) { index ->
                startSession() // closes old target while its reader/finally is still running
                val next = Socket("127.0.0.1", port)
                current.close()
                current = next
                hello(next, index + 2)
                readyTarget(index + 2)
                delay(25) // allow old reader cleanup to race with new authentication
                assertTrue("New authenticated connection lost its ready state", monitor.status.value.commandReady)
                assertFalse(monitor.sendScript(" ").accepted)
                val sent = monitor.sendScript("1 + 1")
                assertTrue(sent.accepted)
                val frame = JSONObject(next.getInputStream().bufferedReader().readLine())
                assertEquals(sent.id, frame.getString("id"))
                val response = JSONObject().put("type", "command_result").put("id", sent.id)
                    .put("pkg", target).put("ok", true).put("result", 2).put("error", JSONObject.NULL)
                next.getOutputStream().write((response.toString() + "\n").toByteArray())
                val result = withTimeout(5_000) { monitor.commandResults.first { it.id == sent.id } }
                assertEquals("2", result.result)
                assertEquals("1 + 1", result.source)
                assertNull(result.error)
            }
        } finally {
            current.close()
        }
    }

    @Test
    fun stalledTargetHasBoundedPendingCommandsAndCannotBlockStop() = runBlocking {
        startSession()
        val port = readyListener().port
        Socket().use { stalled ->
            stalled.receiveBufferSize = 1024
            stalled.connect(InetSocketAddress("127.0.0.1", port))
            hello(stalled, 50)
            readyTarget(50)
            val source = "x".repeat(60_000)
            repeat(128) { assertTrue(monitor.sendScript(source).accepted) }
            assertFalse(monitor.sendScript(source).accepted)
            delay(100) // target intentionally never reads, filling the socket send buffer
            val executor = Executors.newSingleThreadExecutor()
            try {
                executor.submit { monitor.stop() }.get(2, TimeUnit.SECONDS)
                assertFalse(monitor.status.value.commandReady)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun unauthorizedAndDuplicateClientsCannotDisplaceTheAuthenticatedTarget() = runBlocking {
        startSession()
        val port = readyListener().port
        Socket("127.0.0.1", port).use { active ->
            hello(active, 42)
            readyTarget(42)
            for ((candidateToken, candidatePackage) in listOf("00".repeat(32) to target, token to "another.package", token to target)) {
                Socket("127.0.0.1", port).use { rejected ->
                    hello(rejected, 99, candidateToken, candidatePackage)
                    assertFalse(JSONObject(rejected.getInputStream().bufferedReader().readLine()).getBoolean("ok"))
                }
                assertTrue(monitor.status.value.commandReady)
                assertEquals(42, monitor.status.value.connectedPid)
            }
            assertTrue(monitor.sendScript("Process.id").accepted)
            assertEquals("Process.id", JSONObject(active.getInputStream().bufferedReader().readLine()).getString("source"))
        }
    }
}
