package com.nadeem.apkscope.poc.apkrepack

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
