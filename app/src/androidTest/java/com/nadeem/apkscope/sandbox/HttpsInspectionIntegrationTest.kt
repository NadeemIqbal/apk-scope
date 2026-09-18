package com.nadeem.apkscope.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nadeem.apkscope.core.model.NetworkObservation
import com.nadeem.apkscope.core.network.DestinationPolicy
import com.nadeem.apkscope.core.network.EngineLimits
import com.nadeem.apkscope.core.network.TunSink
import com.nadeem.apkscope.core.network.https.CaManager
import com.nadeem.apkscope.core.network.https.HttpsCaptureState
import com.nadeem.apkscope.core.network.https.HttpsInspectionConfig
import com.nadeem.apkscope.core.network.https.HttpsInspectionEngine
import com.nadeem.apkscope.core.network.https.HttpsInspectionStore
import com.nadeem.apkscope.core.network.https.HttpsTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.nadeem.apkscope.domain.toRiskInput
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.Selector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.Header
import okhttp3.internal.http2.Http2Reader
import okhttp3.internal.http2.Http2Writer
import okhttp3.internal.http2.Settings
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.buffer
import okio.sink
import okio.source

@RunWith(AndroidJUnit4::class)
class HttpsInspectionIntegrationTest {

 private val context = InstrumentationRegistry.getInstrumentation().targetContext
 private val caDir by lazy { File(context.filesDir, "test_poc_ca") }

  @Before
  fun setup() {
   caDir.mkdirs()
   HttpsInspectionConfig.isEnabled = true
   HttpsInspectionStore.clear()
   com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore.clear()
  }

 private class TestTunSink : TunSink {
  override val selector: Selector = Selector.open()
  override val tunAddress: ByteArray = byteArrayOf(10, 124, 0, 1)
  override val limits: EngineLimits = EngineLimits.DEFAULT
  override val localSubnets: List<DestinationPolicy.LocalSubnet> = emptyList()
  override fun writeToTun(packet: ByteArray, length: Int) {}
  override fun protectSocket(socket: Socket): Boolean = true // Mocked protect for instrumentation test
  override fun protectDatagram(socket: DatagramSocket): Boolean = true
  override fun onEvidence(test: String, result: String, detail: String) {}
  override fun observe(observation: NetworkObservation) {}
  override fun tryReserveGlobalBuffer(bytes: Int): Boolean = true
  override fun releaseGlobalBuffer(bytes: Int) {}
  override fun currentGlobalBufferedBytes(): Long = 0L
  override fun runOnSelectorThread(task: () -> Unit) { task() }
  // Milestone 9 (userspace traffic ownership verification): real resolution (not mocked), matching
  // production's ForwardingEngine — these tests run in the target app's own process, so passing
  // this app's own real UID as the preamble's owner UID and this app's own package name as
  // targetPackage produces a genuine, on-device MATCHED result, not a stand-in.
  private val instrumentationContext = InstrumentationRegistry.getInstrumentation().targetContext
  override fun resolveTargetUid(targetPackage: String): Int? = try {
   instrumentationContext.packageManager.getPackageUid(targetPackage, 0)
  } catch (_: Exception) { null }
 }

 @Test
 fun testCaGenerationOnDevice() {
  val caManager = CaManager(caDir)
  val cert = caManager.getCaCertificate()
  assertNotNull("CA Certificate must be generated on device", cert)
  assertTrue("CA basicConstraints must indicate CA", cert!!.basicConstraints != -1)
  assertTrue("Issuer must match APK Scope", cert.issuerX500Principal.name.contains("APK Scope"))

  val der = caManager.getCaCertDer()
  assertNotNull("DER bytes must be non-null", der)
  assertTrue("DER bytes must be non-empty", der!!.isNotEmpty())
 }

 @Test
 fun testRedactionAndTruncationOnDevice() {
  val largeBody = "X".repeat(70 * 1024)
  val transaction = HttpsTransaction(
   method = "POST",
   url = "https://httpbin.org/post",
   host = "httpbin.org",
   requestHeaders = mapOf("Authorization" to "Bearer secret_test_token_12345", "Cookie" to "user_session=abc987"),
   requestBody = """{"password":"secret-password-val","note":"sample"}""",
   responseBody = largeBody
  )

  HttpsInspectionStore.record(transaction)
  val recorded = HttpsInspectionStore.all().first()

  assertEquals("[REDACTED]", recorded.requestHeaders["Authorization"])
  assertEquals("[REDACTED]", recorded.requestHeaders["Cookie"])
  assertTrue("Password field must be redacted", recorded.requestBody!!.contains("\"password\":\"[REDACTED]\""))
  assertTrue("Body exceeding 64 KiB must be truncated", recorded.isTruncated)
  assertEquals(HttpsCaptureState.TRUNCATED, recorded.state)
 }

 @Test
 fun testEngineLifecycleAndBinding() {
  val caManager = CaManager(caDir)
  val sink = TestTunSink()
  val engine = HttpsInspectionEngine(sink, caManager)

  assertEquals(0, engine.boundPort)
  engine.start()
  val port = engine.boundPort
  assertTrue("Engine must bind to a dynamic loopback port", port > 0)

  engine.stop()
  assertEquals(0, engine.boundPort)
 }

  @Test
  fun testDestinationPolicyEnforcementOnDevice() {
   val caManager = CaManager(caDir)
   val sink = TestTunSink()
   val engine = HttpsInspectionEngine(sink, caManager)
   engine.start()

   try {
    val socket = Socket("127.0.0.1", engine.boundPort)
    val dos = java.io.DataOutputStream(socket.getOutputStream())
    dos.write(engine.authToken)
    // 10.0.0.1 is RFC 1918 private range, which is denied by DestinationPolicy
    dos.write(byteArrayOf(10, 0, 0, 1))
    dos.writeShort(443)
    dos.writeInt(-1) // Milestone 9: no owner UID in this hand-crafted test preamble (pre-existing test pattern)
    dos.writeShort(0)
    dos.flush()

    // Wait for engine to evaluate policy and close socket
    Thread.sleep(200)
    socket.close()

    val recorded = HttpsInspectionStore.all().firstOrNull { it.host == "10.0.0.1" }
    assertNotNull("Denied destination must be recorded in store", recorded)
    assertEquals(HttpsCaptureState.ENCRYPTED, recorded!!.state)
    assertTrue("Reason must mention DestinationPolicy denial", recorded.failureDetails!!.contains("DestinationPolicy denied"))
   } finally {
    engine.stop()
   }
  }

  @Test
  fun testTargetAppRejectionOfInspectionCaOnDevice() {
   val resolved = try {
    InetAddress.getByName("httpbin.org")
   } catch (_: Exception) {
    null
   }
   if (resolved == null) return

   val caManager = CaManager(caDir)
   val sink = TestTunSink()
   val engine = HttpsInspectionEngine(sink, caManager)
   engine.start()

   try {
    val socket = Socket("127.0.0.1", engine.boundPort)
    val dos = java.io.DataOutputStream(socket.getOutputStream())
    dos.write(engine.authToken)
    dos.write(resolved.address)
    dos.writeShort(443)
    dos.writeInt(-1) // Milestone 9: no owner UID in this hand-crafted test preamble (pre-existing test pattern)
    val sniBytes = "httpbin.org".toByteArray(Charsets.UTF_8)
    dos.writeShort(sniBytes.size)
    dos.write(sniBytes)
    dos.flush()

    // Default SSLContext does NOT trust the locally generated inspection CA
    val defaultCtx = javax.net.ssl.SSLContext.getDefault()
    var handshakeRejected = false
    try {
     val sslSocket = defaultCtx.socketFactory.createSocket(socket, "httpbin.org", 443, true) as SSLSocket
     sslSocket.soTimeout = 5000
     sslSocket.startHandshake()
    } catch (e: SSLHandshakeException) {
     handshakeRejected = true
    } catch (_: Exception) {
     handshakeRejected = true
    }

    assertTrue("Client without local CA in trust store must reject server certificate", handshakeRejected)

    // Give engine thread a moment to record the transaction
    Thread.sleep(500)
    val recorded = HttpsInspectionStore.all().firstOrNull { it.host == "httpbin.org" }
    assertNotNull("Transaction must be recorded for httpbin.org", recorded)
    assertEquals(HttpsCaptureState.TLS_HANDSHAKE_FAILED, recorded!!.state)
    assertTrue("Details must explain client rejected certificate", recorded.failureDetails!!.contains("rejected inspection certificate"))
   } finally {
    engine.stop()
   }
  }

  @Test
  fun testInspectionDisabledPassthroughOnDevice() {
   val resolved = try {
    InetAddress.getByName("httpbin.org")
   } catch (_: Exception) {
    null
   }
   if (resolved == null) return

   HttpsInspectionConfig.isEnabled = false
   val caManager = CaManager(caDir)
   val sink = TestTunSink()
   val engine = HttpsInspectionEngine(sink, caManager)
   engine.start()

   try {
    val socket = Socket("127.0.0.1", engine.boundPort)
    val dos = java.io.DataOutputStream(socket.getOutputStream())
    dos.write(engine.authToken)
    dos.write(resolved.address)
    dos.writeShort(443)
    dos.writeInt(-1) // Milestone 9: no owner UID in this hand-crafted test preamble (pre-existing test pattern)
    dos.writeShort(0)
    // Send initial bytes so peeking doesn't immediately EOF
    dos.write("GET / HTTP/1.1\r\n\r\n".toByteArray())
    dos.flush()

    var recorded: HttpsTransaction? = null
    val deadline = System.currentTimeMillis() + 5000
    while (System.currentTimeMillis() < deadline) {
     recorded = HttpsInspectionStore.all().firstOrNull { it.host.isNotEmpty() }
     if (recorded != null) break
     Thread.sleep(100)
    }

    socket.close()

    assertNotNull("Passthrough transaction should be recorded", recorded)
    assertEquals(HttpsCaptureState.ENCRYPTED, recorded!!.state)
   } finally {
    engine.stop()
    HttpsInspectionConfig.isEnabled = true
   }
  }

  @Test
  fun testEngineInterceptionWithRealPublicHttpsGetOnDevice() {
   val resolved = try {
    InetAddress.getByName("httpbin.org")
   } catch (_: Exception) {
    null
   }
   if (resolved == null) return

   val caManager = CaManager(caDir)
   val sink = TestTunSink()
   val engine = HttpsInspectionEngine(sink, caManager)
   engine.start()

   try {
    val socket = Socket("127.0.0.1", engine.boundPort)
    val dos = java.io.DataOutputStream(socket.getOutputStream())
    dos.write(engine.authToken)
    dos.write(resolved.address)
    dos.writeShort(443)
    dos.writeInt(-1) // Milestone 9: no owner UID in this hand-crafted test preamble (pre-existing test pattern)
    val sniBytes = "httpbin.org".toByteArray(Charsets.UTF_8)
    dos.writeShort(sniBytes.size)
    dos.write(sniBytes)
    dos.flush()

    // Client trusting the local CA
    val caCert = caManager.getCaCertificate()!!
    val clientCtx = createClientSslContext(caCert)
    val sslSocket = clientCtx.socketFactory.createSocket(socket, "httpbin.org", 443, true) as SSLSocket
    sslSocket.soTimeout = 15000
    android.util.Log.i("HttpsTest", "Client starting handshake with httpbin.org")
    sslSocket.startHandshake()
    android.util.Log.i("HttpsTest", "Client handshake completed")

    val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(sslSocket.outputStream, Charsets.UTF_8))
    writer.write("GET /get HTTP/1.1\r\nHost: httpbin.org\r\nConnection: close\r\n\r\n")
    writer.flush()

    val reader = java.io.BufferedReader(java.io.InputStreamReader(sslSocket.inputStream, Charsets.UTF_8))
    val respLines = StringBuilder()
    var line = reader.readLine()
    while (line != null) {
     respLines.append(line).append("\n")
     line = reader.readLine()
    }
    sslSocket.close()

    Thread.sleep(500)
    val recorded = HttpsInspectionStore.all().firstOrNull { it.host == "httpbin.org" && it.method == "GET" }
    assertNotNull("Transaction must be recorded for httpbin.org", recorded)
    assertEquals(HttpsCaptureState.DECODED, recorded!!.state)
    assertEquals(200, recorded.statusCode)
    assertTrue("Response body must contain decoded JSON url", recorded.responseBody!!.contains("httpbin.org/get") || recorded.responseBody!!.contains("origin"))
   } finally {
    engine.stop()
   }
  }

  /**
   * Milestone 9 attribution fix (2026-09-12): every one of [HttpsInspectionEngine]'s own
   * `TrafficRecord` construction sites (the plain-HTTP/1.1 decode path exercised here) previously
   * omitted `sessionId`/`targetPackage` entirely — every real captured HTTP/1.1 transaction had
   * `sessionId = null`, so `TrafficInspectionStore.forSession(sessionId, targetPackage)` (what
   * `SandboxWorkQueryActivity.exportUrlEvidence()` and the Live Monitor's session-scoped queries
   * both depend on) silently returned nothing for the most common capture case, regardless of
   * whether a real session was active. This test is the one in this suite that constructs the
   * engine *with* a real sessionId/targetPackage (every other test here uses the two-arg
   * constructor, matching how this bug went unnoticed) and asserts the session-scoped query
   * actually returns the real transaction — not merely that `HttpsInspectionStore` (the legacy,
   * session-unaware model [testEngineInterceptionWithRealPublicHttpsGetOnDevice] above checks) saw
   * it.
   */
  @Test
  fun testTrafficRecordCarriesSessionAttributionOnDevice() {
   val resolved = try {
    InetAddress.getByName("httpbin.org")
   } catch (_: Exception) {
    null
   }
   if (resolved == null) return

   val sessionId = "m9_attribution_session_${System.currentTimeMillis()}"
   // Milestone 9: the *real* app under test (this instrumentation runs inside its own process, so
   // its own real UID is exactly what a genuine connection from it would resolve to) — not a
   // fixture package name with no real UID behind it, which would only ever produce
   // ownershipStatus=UNKNOWN and could never verify forSession's MATCHED-only evidence contract.
   val targetPackage = context.packageName
   val realOwnerUid = context.packageManager.getPackageUid(targetPackage, 0)
   val caManager = CaManager(caDir)
   val sink = TestTunSink()
   val engine = HttpsInspectionEngine(sink, caManager, sessionId = sessionId, targetPackage = targetPackage)
   engine.start()

   try {
    val socket = Socket("127.0.0.1", engine.boundPort)
    val dos = java.io.DataOutputStream(socket.getOutputStream())
    dos.write(engine.authToken)
    dos.write(resolved.address)
    dos.writeShort(443)
    dos.writeInt(realOwnerUid) // Milestone 9: this connection's genuine real owner UID — produces a real MATCHED verification, not a stand-in
    val sniBytes = "httpbin.org".toByteArray(Charsets.UTF_8)
    dos.writeShort(sniBytes.size)
    dos.write(sniBytes)
    dos.flush()

    val caCert = caManager.getCaCertificate()!!
    val clientCtx = createClientSslContext(caCert)
    val sslSocket = clientCtx.socketFactory.createSocket(socket, "httpbin.org", 443, true) as SSLSocket
    sslSocket.soTimeout = 15000
    sslSocket.startHandshake()

    val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(sslSocket.outputStream, Charsets.UTF_8))
    writer.write("GET /get HTTP/1.1\r\nHost: httpbin.org\r\nConnection: close\r\n\r\n")
    writer.flush()

    val reader = java.io.BufferedReader(java.io.InputStreamReader(sslSocket.inputStream, Charsets.UTF_8))
    while (reader.readLine() != null) { /* drain */ }
    sslSocket.close()

    Thread.sleep(500)

    // The session-scoped query — what URL evidence export and the Live Monitor actually use —
    // must contain this real transaction, not merely TrafficInspectionStore.all(). Since Milestone
    // 9's ownership-verification phase, this also requires a genuinely MATCHED ownership result —
    // exercised for real here via the real owner UID sent above, not merely attribution.
    val sessionScoped = com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore.forSession(sessionId, targetPackage)
    assertTrue(
     "forSession(sessionId, targetPackage) must return the real httpbin.org transaction with verified MATCHED ownership, not silently exclude it",
     sessionScoped.any { it.host == "httpbin.org" && it.method == "GET" && it.statusCode == 200 },
    )
    val matchedRecord = sessionScoped.first { it.host == "httpbin.org" }
    assertEquals(com.nadeem.apkscope.core.network.traffic.OwnershipVerificationStatus.MATCHED, matchedRecord.ownershipStatus)
    assertEquals(realOwnerUid, matchedRecord.observedOwnerUid)
    // And it must not leak into a *different* session's scoped view.
    val wrongSession = com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore.forSession("some-other-session", targetPackage)
    assertTrue(
     "A record attributed to this session must not appear under an unrelated sessionId",
     wrongSession.none { it.host == "httpbin.org" && it.method == "GET" },
    )
   } finally {
    engine.stop()
   }
  }

  @Test
  fun testEngineInterceptionWithRealPublicHttpsPostOnDevice() {
   val resolved = try {
    InetAddress.getByName("httpbin.org")
   } catch (_: Exception) {
    null
   }
   if (resolved == null) return

   val caManager = CaManager(caDir)
   val sink = TestTunSink()
   val engine = HttpsInspectionEngine(sink, caManager)
   engine.start()

   try {
    val socket = Socket("127.0.0.1", engine.boundPort)
    val dos = java.io.DataOutputStream(socket.getOutputStream())
    dos.write(engine.authToken)
    dos.write(resolved.address)
    dos.writeShort(443)
    dos.writeInt(-1) // Milestone 9: no owner UID in this hand-crafted test preamble (pre-existing test pattern)
    val sniBytes = "httpbin.org".toByteArray(Charsets.UTF_8)
    dos.writeShort(sniBytes.size)
    dos.write(sniBytes)
    dos.flush()

    val caCert = caManager.getCaCertificate()!!
    val clientCtx = createClientSslContext(caCert)
    val sslSocket = clientCtx.socketFactory.createSocket(socket, "httpbin.org", 443, true) as SSLSocket
    sslSocket.soTimeout = 15000
    android.util.Log.i("HttpsTest", "Client starting POST handshake with httpbin.org")
    sslSocket.startHandshake()
    android.util.Log.i("HttpsTest", "Client POST handshake completed")

    val payload = """{"sender":"apk-scope-test","action":"verify_post","timestamp":12345678}"""
    val payloadBytes = payload.toByteArray(Charsets.UTF_8)

    val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(sslSocket.outputStream, Charsets.UTF_8))
    writer.write("POST /post HTTP/1.1\r\n")
    writer.write("Host: httpbin.org\r\n")
    writer.write("Content-Type: application/json\r\n")
    writer.write("Content-Length: ${payloadBytes.size}\r\n")
    writer.write("Connection: close\r\n\r\n")
    writer.write(payload)
    writer.flush()

    val reader = java.io.BufferedReader(java.io.InputStreamReader(sslSocket.inputStream, Charsets.UTF_8))
    val respLines = StringBuilder()
    var line = reader.readLine()
    while (line != null) {
     respLines.append(line).append("\n")
     line = reader.readLine()
    }
    sslSocket.close()

    Thread.sleep(500)
    val recorded = HttpsInspectionStore.all().firstOrNull { it.host == "httpbin.org" && it.method == "POST" }
    assertNotNull("POST transaction must be recorded for httpbin.org", recorded)
    assertEquals(HttpsCaptureState.DECODED, recorded!!.state)
    assertEquals(200, recorded.statusCode)
    assertNotNull("Request body must be captured", recorded.requestBody)
    assertTrue("Request body must contain payload", recorded.requestBody!!.contains("verify_post"))
    assertNotNull("Response body must be captured", recorded.responseBody)
    assertTrue("Response body must contain json echo", recorded.responseBody!!.contains("apk-scope-test") || recorded.responseBody!!.contains("httpbin.org/post"))
   } finally {
    engine.stop()
   }
  }

  @Test
  fun testConscryptHandshakeOnLoopback() {
   val caManager = CaManager(caDir)
   val serverCtx = caManager.getOrCreateServerSslContext("test.local")
   val ss = serverCtx.serverSocketFactory.createServerSocket(0)
   val port = ss.localPort

   val clientCtx = createClientSslContext(caManager.getCaCertificate()!!)

   var serverDone = false
   var clientDone = false

   val t = Thread {
    try {
     val rawClient = Socket("127.0.0.1", port)
     val sslClient = clientCtx.socketFactory.createSocket(rawClient, "test.local", port, true) as SSLSocket
     sslClient.startHandshake()
     clientDone = true
     sslClient.close()
    } catch (e: Exception) {
     android.util.Log.e("HttpsTest", "Client error: ${e.message}", e)
    }
   }
   t.start()

   val rawServer = ss.accept()
   (rawServer as SSLSocket).startHandshake()
   serverDone = true
   t.join(5000)
   rawServer.close()
   ss.close()

   assertTrue("Server handshake must complete", serverDone)
   assertTrue("Client handshake must complete", clientDone)
  }

  @Test
  fun testInvalidUpstreamCertificateRejectionOnDevice() {
   val resolved = try {
    InetAddress.getByName("self-signed.badssl.com")
   } catch (_: Exception) {
    null
   }
   if (resolved == null) return

   val caManager = CaManager(caDir)
   val sink = TestTunSink()
   val engine = HttpsInspectionEngine(sink, caManager)
   engine.start()

   try {
    val socket = Socket("127.0.0.1", engine.boundPort)
    val dos = java.io.DataOutputStream(socket.getOutputStream())
    dos.write(engine.authToken)
    dos.write(resolved.address)
    dos.writeShort(443)
    dos.writeInt(-1) // Milestone 9: no owner UID in this hand-crafted test preamble (pre-existing test pattern)
    val sniBytes = "self-signed.badssl.com".toByteArray(Charsets.UTF_8)
    dos.writeShort(sniBytes.size)
    dos.write(sniBytes)
    dos.flush()

    // Client connects trusting the local CA, but engine must reject self-signed upstream
    val caCert = caManager.getCaCertificate()!!
    val clientCtx = createClientSslContext(caCert)
    try {
     val sslSocket = clientCtx.socketFactory.createSocket(socket, "self-signed.badssl.com", 443, true) as SSLSocket
     sslSocket.soTimeout = 5000
     sslSocket.startHandshake()
    } catch (_: Exception) {
     // Downstream handshake aborted because upstream connection failed
    }

    Thread.sleep(500)
    val recorded = HttpsInspectionStore.all().firstOrNull { it.host == "self-signed.badssl.com" }
    assertNotNull("Transaction must be recorded for self-signed.badssl.com", recorded)
    assertEquals(HttpsCaptureState.TLS_HANDSHAKE_FAILED, recorded!!.state)
    assertTrue("Details must mention certificate error", recorded.failureDetails!!.contains("CertificateException") || recorded.failureDetails!!.contains("Handshake"))
   } finally {
    engine.stop()
   }
  }

  @Test
  fun testUpstreamHostnameMismatchRejectionOnDevice() {
   val resolved = try {
    InetAddress.getByName("wrong.host.badssl.com")
   } catch (_: Exception) {
    null
   }
   if (resolved == null) return

   val caManager = CaManager(caDir)
   val sink = TestTunSink()
   val engine = HttpsInspectionEngine(sink, caManager)
   engine.start()

   try {
    val socket = Socket("127.0.0.1", engine.boundPort)
    val dos = java.io.DataOutputStream(socket.getOutputStream())
    dos.write(engine.authToken)
    dos.write(resolved.address)
    dos.writeShort(443)
    dos.writeInt(-1) // Milestone 9: no owner UID in this hand-crafted test preamble (pre-existing test pattern)
    val sniBytes = "wrong.host.badssl.com".toByteArray(Charsets.UTF_8)
    dos.writeShort(sniBytes.size)
    dos.write(sniBytes)
    dos.flush()

    val caCert = caManager.getCaCertificate()!!
    val clientCtx = createClientSslContext(caCert)
    try {
     val sslSocket = clientCtx.socketFactory.createSocket(socket, "wrong.host.badssl.com", 443, true) as SSLSocket
     sslSocket.soTimeout = 5000
     sslSocket.startHandshake()
    } catch (_: Exception) {
    }

    Thread.sleep(500)
    val recorded = HttpsInspectionStore.all().firstOrNull { it.host == "wrong.host.badssl.com" }
    assertNotNull("Transaction must be recorded for wrong.host.badssl.com", recorded)
    assertEquals(HttpsCaptureState.TLS_HANDSHAKE_FAILED, recorded!!.state)
    assertTrue("Details must mention hostname mismatch", recorded.failureDetails!!.contains("hostname mismatch") || recorded.failureDetails!!.contains("SSLPeerUnverifiedException"))
   } finally {
    engine.stop()
   }
  }

  @Test
  fun testUnauthorizedInspectorAccessRejectedOnDevice() {
   val caManager = CaManager(caDir)
   val sink = TestTunSink()
   val engine = HttpsInspectionEngine(sink, caManager)
   engine.start()

   try {
    val socket = Socket("127.0.0.1", engine.boundPort)
    val dos = java.io.DataOutputStream(socket.getOutputStream())
    // Write a bogus 16-byte token
    dos.write(ByteArray(16) { 0x42.toByte() })
    dos.write(byteArrayOf(1, 1, 1, 1))
    dos.writeShort(443)
    dos.writeShort(0)
    dos.flush()

    // Engine must reject and close connection
    socket.soTimeout = 2000
    val b = try { socket.getInputStream().read() } catch (_: Exception) { -1 }
    assertEquals("Connection must be closed by inspector when auth token is invalid", -1, b)
   } finally {
    engine.stop()
   }
  }

  @Test
  fun testResetLifecycleOnDevice() {
   val caManager = CaManager(caDir)
   val cert = caManager.getCaCertificate()
   assertNotNull("CA must be created", cert)
   assertTrue("CA cert file must exist", caManager.hasCaCertificate())

   HttpsInspectionStore.record(
    HttpsTransaction(method = "GET", url = "https://httpbin.org/get", host = "httpbin.org")
   )
   assertFalse("Store must not be empty", HttpsInspectionStore.all().isEmpty())

   HttpsInspectionConfig.isEnabled = true
   val sink = TestTunSink()
   val engine = HttpsInspectionEngine(sink, caManager)
   engine.start()
   assertTrue("Engine must be bound", engine.boundPort > 0)

   // Perform full reset
   engine.stop()
   assertEquals("Port must be 0 after stop", 0, engine.boundPort)

   caManager.reset()
   assertFalse("CA cert file must be deleted after reset", caManager.hasCaCertificate())
   assertNull("getCaCertificate must return null after reset", caManager.getCaCertificate())

   HttpsInspectionStore.clear()
   assertTrue("Store must be empty after clear", HttpsInspectionStore.all().isEmpty())

   HttpsInspectionConfig.reset()
   assertFalse("Inspection config must be disabled after reset", HttpsInspectionConfig.isEnabled)
  }

  @Test
  fun testPlaintextHttpGetOnDevice() {
   val resolved = try {
    InetAddress.getByName("httpbin.org")
   } catch (_: Exception) {
    null
   }
   if (resolved == null) return

   val caManager = CaManager(caDir)
   val sink = TestTunSink()
   val engine = HttpsInspectionEngine(sink, caManager)
   engine.start()

   try {
    val socket = Socket("127.0.0.1", engine.boundPort)
    val dos = java.io.DataOutputStream(socket.getOutputStream())
    dos.write(engine.authToken)
    dos.write(resolved.address)
    dos.writeShort(80) // Port 80 for plaintext HTTP
    dos.writeInt(-1) // Milestone 9: no owner UID in this hand-crafted test preamble (pre-existing test pattern)
    val sniBytes = "httpbin.org".toByteArray(Charsets.UTF_8)
    dos.writeShort(sniBytes.size)
    dos.write(sniBytes)
    dos.flush()

    val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
    writer.write("GET /get HTTP/1.1\r\nHost: httpbin.org\r\nConnection: close\r\n\r\n")
    writer.flush()

    val reader = java.io.BufferedReader(java.io.InputStreamReader(socket.inputStream, Charsets.UTF_8))
    val respLines = StringBuilder()
    var line = reader.readLine()
    while (line != null) {
     respLines.append(line).append("\n")
     line = reader.readLine()
    }
    socket.close()

    Thread.sleep(500)
    val recorded = com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore.all().firstOrNull {
     it.host == "httpbin.org" && it.method == "GET" && it.protocol == com.nadeem.apkscope.core.network.traffic.TrafficProtocol.HTTP
    }
    assertNotNull("HTTP plaintext transaction must be recorded", recorded)
    assertEquals(com.nadeem.apkscope.core.network.traffic.TrafficCaptureState.DECODED, recorded!!.state)
    assertEquals(200, recorded.statusCode)
    assertTrue("Response body must contain httpbin content", recorded.responseBody!!.contains("httpbin.org/get") || recorded.responseBody!!.contains("origin"))
   } finally {
    engine.stop()
   }
  }

  @Test
  fun testPlaintextHttpPostOnDevice() {
   val resolved = try {
    InetAddress.getByName("httpbin.org")
   } catch (_: Exception) {
    null
   }
   if (resolved == null) return

   val caManager = CaManager(caDir)
   val sink = TestTunSink()
   val engine = HttpsInspectionEngine(sink, caManager)
   engine.start()

   try {
    val socket = Socket("127.0.0.1", engine.boundPort)
    val dos = java.io.DataOutputStream(socket.getOutputStream())
    dos.write(engine.authToken)
    dos.write(resolved.address)
    dos.writeShort(80)
    dos.writeInt(-1) // Milestone 9: no owner UID in this hand-crafted test preamble (pre-existing test pattern)
    val sniBytes = "httpbin.org".toByteArray(Charsets.UTF_8)
    dos.writeShort(sniBytes.size)
    dos.write(sniBytes)
    dos.flush()

    val payload = """{"sender":"apk-scope-integration","action":"test_http_post"}"""
    val payloadBytes = payload.toByteArray(Charsets.UTF_8)

    val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
    writer.write("POST /post HTTP/1.1\r\n")
    writer.write("Host: httpbin.org\r\n")
    writer.write("Content-Type: application/json\r\n")
    writer.write("Content-Length: ${payloadBytes.size}\r\n")
    writer.write("Connection: close\r\n\r\n")
    writer.write(payload)
    writer.flush()

    val reader = java.io.BufferedReader(java.io.InputStreamReader(socket.inputStream, Charsets.UTF_8))
    val respLines = StringBuilder()
    var line = reader.readLine()
    while (line != null) {
     respLines.append(line).append("\n")
     line = reader.readLine()
    }
    socket.close()

    Thread.sleep(500)
    val recorded = com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore.all().firstOrNull {
     it.host == "httpbin.org" && it.method == "POST" && it.protocol == com.nadeem.apkscope.core.network.traffic.TrafficProtocol.HTTP
    }
    assertNotNull("HTTP POST transaction must be recorded", recorded)
    assertEquals(com.nadeem.apkscope.core.network.traffic.TrafficCaptureState.DECODED, recorded!!.state)
    assertEquals(200, recorded.statusCode)
    assertNotNull("Request body must be captured", recorded.requestBody)
    assertTrue("Request body must contain payload", recorded.requestBody!!.contains("test_http_post"))
   } finally {
    engine.stop()
   }
  }

  @Test
  fun testWebSocketUpgradeAndRelayOnDevice() {
   val resolved = try {
    InetAddress.getByName("echo.websocket.org")
   } catch (_: Exception) {
    null
   }
   if (resolved == null) return

   val caManager = CaManager(caDir)
   val sink = TestTunSink()
   val engine = HttpsInspectionEngine(sink, caManager)
   engine.start()

   try {
    val socket = Socket("127.0.0.1", engine.boundPort)
    val dos = java.io.DataOutputStream(socket.getOutputStream())
    dos.write(engine.authToken)
    dos.write(resolved.address)
    dos.writeShort(443)
    dos.writeInt(-1) // Milestone 9: no owner UID in this hand-crafted test preamble (pre-existing test pattern)
    val sniBytes = "echo.websocket.org".toByteArray(Charsets.UTF_8)
    dos.writeShort(sniBytes.size)
    dos.write(sniBytes)
    dos.flush()

    // Client connects trusting the local CA for WSS inspection
    val caCert = caManager.getCaCertificate()!!
    val clientCtx = createClientSslContext(caCert)
    val sslSocket = clientCtx.socketFactory.createSocket(socket, "echo.websocket.org", 443, true) as SSLSocket
    sslSocket.soTimeout = 15000
    sslSocket.startHandshake()

    // Send HTTP Upgrade request over TLS (WSS)
    val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(sslSocket.outputStream, Charsets.UTF_8))
    writer.write("GET / HTTP/1.1\r\n")
    writer.write("Host: echo.websocket.org\r\n")
    writer.write("Upgrade: websocket\r\n")
    writer.write("Connection: Upgrade\r\n")
    writer.write("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
    writer.write("Sec-WebSocket-Version: 13\r\n\r\n")
    writer.flush()

    // Read 101 Switching Protocols response line-by-line
    val dis = java.io.DataInputStream(sslSocket.inputStream)
    val headerLines = StringBuilder()
    var line: String
    while (true) {
     val sb = StringBuilder()
     var c: Int
     while (dis.read().also { c = it } != -1) {
      if (c == '\n'.code) break
      if (c != '\r'.code) sb.append(c.toChar())
     }
     line = sb.toString()
     headerLines.append(line).append("\n")
     if (line.isEmpty()) break
    }

    assertTrue("Response must indicate 101 Switching Protocols", headerLines.contains("101"))

    // Send client masked text frame: "Hello APK Scope WebSocket"
    val textPayload = "Hello APK Scope WebSocket".toByteArray(Charsets.UTF_8)
    val frameOut = java.io.ByteArrayOutputStream()
    frameOut.write(0x81) // FIN + opcode 1 (TEXT)
    frameOut.write(0x80 or textPayload.size) // Masked + len
    val maskKey = byteArrayOf(1, 2, 3, 4)
    frameOut.write(maskKey)
    val maskedPayload = ByteArray(textPayload.size) { i -> (textPayload[i].toInt() xor maskKey[i % 4].toInt()).toByte() }
    frameOut.write(maskedPayload)
    sslSocket.outputStream.write(frameOut.toByteArray())
    sslSocket.outputStream.flush()

    // Cleanly read server banner and echoed text to ensure outbound frame was received & forwarded
    try {
     com.nadeem.apkscope.core.network.traffic.WebSocketFrameParser.readFrame(sslSocket.inputStream)
     com.nadeem.apkscope.core.network.traffic.WebSocketFrameParser.readFrame(sslSocket.inputStream)
    } catch (_: Exception) {}

    // Send client close frame (opcode 8)
    val closeOut = java.io.ByteArrayOutputStream()
    closeOut.write(0x88) // FIN + opcode 8 (CLOSE)
    closeOut.write(0x82) // Masked + 2 bytes
    closeOut.write(maskKey)
    val closePayload = byteArrayOf(0x03.toByte(), 0xE8.toByte()) // 1000 normal closure
    val maskedClose = ByteArray(2) { i -> (closePayload[i].toInt() xor maskKey[i % 4].toInt()).toByte() }
    closeOut.write(maskedClose)
    sslSocket.outputStream.write(closeOut.toByteArray())
    sslSocket.outputStream.flush()

    try {
     com.nadeem.apkscope.core.network.traffic.WebSocketFrameParser.readFrame(sslSocket.inputStream)
    } catch (_: Exception) {}

    sslSocket.close()

    val recorded = com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore.all().firstOrNull {
     it.host == "echo.websocket.org" && (it.protocol == com.nadeem.apkscope.core.network.traffic.TrafficProtocol.WS || it.protocol == com.nadeem.apkscope.core.network.traffic.TrafficProtocol.WSS)
    }
    assertNotNull("WebSocket session must be recorded in store", recorded)
    val session = recorded!!.webSocketSession
    assertNotNull("WebSocket session data must be present", session)
    android.util.Log.i("HttpsInspectionTest", "WS messages in session: ${session!!.messages.map { "${it.direction}:${it.type}:${it.payloadPreview}" }}")
    assertTrue("At least one message must be recorded", session.messages.isNotEmpty())
    val clientMsg = session.messages.firstOrNull { it.direction == com.nadeem.apkscope.core.network.traffic.Direction.OUTBOUND }
    assertNotNull("Client-to-server text message must be recorded (found: ${session.messages.map { it.direction }})", clientMsg)
    assertTrue("Message preview must contain plaintext text", clientMsg!!.payloadPreview!!.contains("Hello APK Scope WebSocket"))
   } finally {
    engine.stop()
   }
  }

  @Test
  fun testPostQuantumClientHelloAccumulatorOnDevice() {
   val parser = com.nadeem.apkscope.core.network.https.TlsClientHelloParser
   // Create ClientHello with 3000 bytes padding extension
   val bos = java.io.ByteArrayOutputStream()
   bos.write(0x16) // ContentType: Handshake (22)
   bos.write(0x03); bos.write(0x01) // TLS 1.0 record layer
   val recordLenPos = bos.size()
   bos.write(0x00); bos.write(0x00) // Placeholder for record length

   val hsStart = bos.size()
   bos.write(0x01) // HandshakeType: ClientHello (1)
   bos.write(0x00); bos.write(0x00); bos.write(0x00) // Handshake length placeholder

   val clientHelloStart = bos.size()
   bos.write(0x03); bos.write(0x03) // TLS 1.2
   bos.write(ByteArray(32) { 0x01 }) // Random (32 bytes)
   bos.write(0x00) // Session ID length (0)
   bos.write(0x00); bos.write(0x02); bos.write(0x13); bos.write(0x01) // Cipher suites (2 bytes len, 0x1301)
   bos.write(0x01); bos.write(0x00) // Compression methods (1 byte len, 0x00 null)

   // Extensions
   val extStream = java.io.ByteArrayOutputStream()
   // SNI extension (type 0)
   val sniHostname = "kyber.test.example.com".toByteArray(Charsets.US_ASCII)
   extStream.write(0x00); extStream.write(0x00) // Type 0 (server_name)
   val sniEntryLen = 3 + sniHostname.size
   val sniExtLen = 2 + sniEntryLen
   extStream.write((sniExtLen shr 8) and 0xFF); extStream.write(sniExtLen and 0xFF)
   extStream.write((sniEntryLen shr 8) and 0xFF); extStream.write(sniEntryLen and 0xFF)
   extStream.write(0x00) // NameType: host_name
   extStream.write((sniHostname.size shr 8) and 0xFF); extStream.write(sniHostname.size and 0xFF)
   extStream.write(sniHostname)

   // Post-Quantum simulated key_share extension (type 0x0033) with ~2800 bytes
   val pqKeyShareBytes = ByteArray(2800) { 0x77.toByte() }
   extStream.write(0x00); extStream.write(0x33) // Type 51
   extStream.write((pqKeyShareBytes.size shr 8) and 0xFF); extStream.write(pqKeyShareBytes.size and 0xFF)
   extStream.write(pqKeyShareBytes)

   val extBytes = extStream.toByteArray()
   bos.write((extBytes.size shr 8) and 0xFF); bos.write(extBytes.size and 0xFF)
   bos.write(extBytes)

   val totalBytes = bos.toByteArray()
   val hsLength = totalBytes.size - clientHelloStart
   totalBytes[hsStart + 1] = ((hsLength shr 16) and 0xFF).toByte()
   totalBytes[hsStart + 2] = ((hsLength shr 8) and 0xFF).toByte()
   totalBytes[hsStart + 3] = (hsLength and 0xFF).toByte()

   val recLength = totalBytes.size - 5
   totalBytes[recordLenPos] = ((recLength shr 8) and 0xFF).toByte()
   totalBytes[recordLenPos + 1] = (recLength and 0xFF).toByte()

   assertTrue("Synthetic record must be > 2048 bytes to test Kyber threshold", totalBytes.size > 2048)
   assertTrue("Parser must mark record complete", parser.isRecordComplete(totalBytes, 0, totalBytes.size))

   val sni = parser.extractSni(totalBytes, 0, totalBytes.size)
   assertEquals("Parser must correctly extract SNI from > 2048 byte ClientHello", "kyber.test.example.com", sni)
  }

  @Ignore("Demo data seeding - excluded from runtime acceptance evidence")
  @Test
  fun testPopulateTrafficViewerDemo() {
   val now = java.time.Instant.now()

   // 1. HTTP GET
   com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore.record(
    com.nadeem.apkscope.core.network.traffic.TrafficRecord(
     id = java.util.UUID.randomUUID().toString(),
     protocol = com.nadeem.apkscope.core.network.traffic.TrafficProtocol.HTTP,
     host = "httpbin.org",
     port = 80,
     url = "http://httpbin.org/get",
     method = "GET",
     statusCode = 200,
     statusMessage = "OK",
     contentType = "application/json",
     requestHeaders = mapOf("Host" to "httpbin.org", "User-Agent" to "APK-Sandbox-Fixture"),
     responseHeaders = mapOf("Content-Type" to "application/json", "Server" to "gunicorn"),
     responseBody = """{"args":{},"headers":{"Host":"httpbin.org"},"origin":"10.124.0.1","url":"http://httpbin.org/get"}""",
     responseBodyBytes = 104,
     durationMs = 120,
     timestamp = now.minusSeconds(120),
     state = com.nadeem.apkscope.core.network.traffic.TrafficCaptureState.DECODED
    )
   )

   // 2. HTTP POST
   com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore.record(
    com.nadeem.apkscope.core.network.traffic.TrafficRecord(
     id = java.util.UUID.randomUUID().toString(),
     protocol = com.nadeem.apkscope.core.network.traffic.TrafficProtocol.HTTP,
     host = "httpbin.org",
     port = 80,
     url = "http://httpbin.org/post",
     method = "POST",
     statusCode = 200,
     statusMessage = "OK",
     contentType = "application/json",
     requestHeaders = mapOf("Host" to "httpbin.org", "Content-Type" to "application/json"),
     responseHeaders = mapOf("Content-Type" to "application/json"),
     requestBody = """{"sender":"apk-scope-fixture","action":"test_http_post"}""",
     responseBody = """{"data":"{\"sender\":\"apk-scope-fixture\",\"action\":\"test_http_post\"}","json":{"action":"test_http_post"}}""",
     requestBodyBytes = 57,
     responseBodyBytes = 118,
     durationMs = 145,
     timestamp = now.minusSeconds(90),
     state = com.nadeem.apkscope.core.network.traffic.TrafficCaptureState.DECODED
    )
   )

   // 3. HTTPS GET
   com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore.record(
    com.nadeem.apkscope.core.network.traffic.TrafficRecord(
     id = java.util.UUID.randomUUID().toString(),
     protocol = com.nadeem.apkscope.core.network.traffic.TrafficProtocol.HTTPS,
     host = "httpbin.org",
     port = 443,
     url = "https://httpbin.org/get",
     method = "GET",
     statusCode = 200,
     statusMessage = "OK",
     contentType = "application/json",
     requestHeaders = mapOf("Host" to "httpbin.org", "Accept" to "application/json"),
     responseHeaders = mapOf("Content-Type" to "application/json", "Connection" to "keep-alive"),
     responseBody = """{"origin":"10.124.0.1","url":"https://httpbin.org/get"}""",
     responseBodyBytes = 55,
     durationMs = 210,
     timestamp = now.minusSeconds(60),
     state = com.nadeem.apkscope.core.network.traffic.TrafficCaptureState.DECODED
    )
   )

   // 4. HTTPS POST
   com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore.record(
    com.nadeem.apkscope.core.network.traffic.TrafficRecord(
     id = java.util.UUID.randomUUID().toString(),
     protocol = com.nadeem.apkscope.core.network.traffic.TrafficProtocol.HTTPS,
     host = "httpbin.org",
     port = 443,
     url = "https://httpbin.org/post",
     method = "POST",
     statusCode = 200,
     statusMessage = "OK",
     contentType = "application/json",
     requestHeaders = mapOf("Host" to "httpbin.org", "Content-Type" to "application/json"),
     responseHeaders = mapOf("Content-Type" to "application/json"),
     requestBody = """{"client":"Work Profile VPN","intercepted":true}""",
     responseBody = """{"json":{"client":"Work Profile VPN","intercepted":true}}""",
     requestBodyBytes = 47,
     responseBodyBytes = 65,
     durationMs = 230,
     timestamp = now.minusSeconds(40),
     state = com.nadeem.apkscope.core.network.traffic.TrafficCaptureState.DECODED
    )
   )

   // 5. WS (Plaintext WebSocket Session)
   val wsSession = com.nadeem.apkscope.core.network.traffic.WebSocketSessionData(
    openedAt = now.minusSeconds(30),
    closedAt = now.minusSeconds(20),
    messages = listOf(
     com.nadeem.apkscope.core.network.traffic.WebSocketMessage(
      sequence = 1,
      direction = com.nadeem.apkscope.core.network.traffic.Direction.INBOUND,
      type = com.nadeem.apkscope.core.network.traffic.MessageType.TEXT,
      timestamp = now.minusSeconds(29),
      payloadLength = 32,
      payloadPreview = "Request served by 2867542a161128",
      isMasked = false
     ),
     com.nadeem.apkscope.core.network.traffic.WebSocketMessage(
      sequence = 2,
      direction = com.nadeem.apkscope.core.network.traffic.Direction.OUTBOUND,
      type = com.nadeem.apkscope.core.network.traffic.MessageType.TEXT,
      timestamp = now.minusSeconds(28),
      payloadLength = 26,
      payloadPreview = "Hello APK Scope WS Frame 1",
      isMasked = true
     ),
     com.nadeem.apkscope.core.network.traffic.WebSocketMessage(
      sequence = 3,
      direction = com.nadeem.apkscope.core.network.traffic.Direction.INBOUND,
      type = com.nadeem.apkscope.core.network.traffic.MessageType.TEXT,
      timestamp = now.minusSeconds(27),
      payloadLength = 26,
      payloadPreview = "Hello APK Scope WS Frame 1",
      isMasked = false
     ),
     com.nadeem.apkscope.core.network.traffic.WebSocketMessage(
      sequence = 4,
      direction = com.nadeem.apkscope.core.network.traffic.Direction.OUTBOUND,
      type = com.nadeem.apkscope.core.network.traffic.MessageType.BINARY,
      timestamp = now.minusSeconds(26),
      payloadLength = 5,
      payloadPreview = "[Binary 5 bytes]",
      isMasked = true
     ),
     com.nadeem.apkscope.core.network.traffic.WebSocketMessage(
      sequence = 5,
      direction = com.nadeem.apkscope.core.network.traffic.Direction.OUTBOUND,
      type = com.nadeem.apkscope.core.network.traffic.MessageType.CLOSE,
      timestamp = now.minusSeconds(20),
      payloadLength = 2,
      payloadPreview = "Close code=1000 reason=normal closure",
      isMasked = true,
      closeCode = 1000,
      closeReason = "normal closure"
     )
    )
   )
   com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore.record(
    com.nadeem.apkscope.core.network.traffic.TrafficRecord(
     id = java.util.UUID.randomUUID().toString(),
     protocol = com.nadeem.apkscope.core.network.traffic.TrafficProtocol.WS,
     host = "echo.websocket.org",
     port = 80,
     url = "ws://echo.websocket.org/",
     method = "UPGRADE",
     statusCode = 101,
     statusMessage = "Switching Protocols",
     contentType = "websocket",
     requestHeaders = mapOf("Upgrade" to "websocket", "Connection" to "Upgrade"),
     responseHeaders = mapOf("Upgrade" to "websocket", "Connection" to "Upgrade"),
     durationMs = 10000,
     timestamp = now.minusSeconds(30),
     state = com.nadeem.apkscope.core.network.traffic.TrafficCaptureState.DECODED,
     webSocketSession = wsSession
    )
   )

   // 6. WSS (Secure WebSocket with Defragmented Continuation Frames)
   val wssSession = com.nadeem.apkscope.core.network.traffic.WebSocketSessionData(
    openedAt = now.minusSeconds(15),
    closedAt = now.minusSeconds(2),
    messages = listOf(
     com.nadeem.apkscope.core.network.traffic.WebSocketMessage(
      sequence = 1,
      direction = com.nadeem.apkscope.core.network.traffic.Direction.INBOUND,
      type = com.nadeem.apkscope.core.network.traffic.MessageType.TEXT,
      timestamp = now.minusSeconds(14),
      payloadLength = 32,
      payloadPreview = "Request served by 4d896d95b55478",
      isMasked = false
     ),
     com.nadeem.apkscope.core.network.traffic.WebSocketMessage(
      sequence = 2,
      direction = com.nadeem.apkscope.core.network.traffic.Direction.OUTBOUND,
      type = com.nadeem.apkscope.core.network.traffic.MessageType.TEXT,
      timestamp = now.minusSeconds(12),
      payloadLength = 65,
      payloadPreview = "[2 fragments] Defragmented Secure WebSocket RFC 6455 Message from Client",
      isMasked = true,
      isFragmented = true,
      isReconstructed = true,
      fragmentCount = 2
     ),
     com.nadeem.apkscope.core.network.traffic.WebSocketMessage(
      sequence = 3,
      direction = com.nadeem.apkscope.core.network.traffic.Direction.INBOUND,
      type = com.nadeem.apkscope.core.network.traffic.MessageType.PING,
      timestamp = now.minusSeconds(10),
      payloadLength = 4,
      payloadPreview = "Ping: heartbeat",
      isMasked = false
     ),
     com.nadeem.apkscope.core.network.traffic.WebSocketMessage(
      sequence = 4,
      direction = com.nadeem.apkscope.core.network.traffic.Direction.OUTBOUND,
      type = com.nadeem.apkscope.core.network.traffic.MessageType.PONG,
      timestamp = now.minusSeconds(9),
      payloadLength = 4,
      payloadPreview = "Pong: heartbeat",
      isMasked = true
     ),
     com.nadeem.apkscope.core.network.traffic.WebSocketMessage(
      sequence = 5,
      direction = com.nadeem.apkscope.core.network.traffic.Direction.INBOUND,
      type = com.nadeem.apkscope.core.network.traffic.MessageType.BINARY,
      timestamp = now.minusSeconds(8),
      payloadLength = 128,
      payloadPreview = "[3 fragments] [Binary 128 bytes]",
      isMasked = false,
      isFragmented = true,
      isReconstructed = true,
      fragmentCount = 3
     ),
     com.nadeem.apkscope.core.network.traffic.WebSocketMessage(
      sequence = 6,
      direction = com.nadeem.apkscope.core.network.traffic.Direction.OUTBOUND,
      type = com.nadeem.apkscope.core.network.traffic.MessageType.CLOSE,
      timestamp = now.minusSeconds(2),
      payloadLength = 2,
      payloadPreview = "Close code=1000 reason=normal closure",
      isMasked = true,
      closeCode = 1000,
      closeReason = "normal closure"
     )
    )
   )
   com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore.record(
    com.nadeem.apkscope.core.network.traffic.TrafficRecord(
     id = java.util.UUID.randomUUID().toString(),
     protocol = com.nadeem.apkscope.core.network.traffic.TrafficProtocol.WSS,
     host = "echo.websocket.org",
     port = 443,
     url = "wss://echo.websocket.org/",
     method = "UPGRADE",
     statusCode = 101,
     statusMessage = "Switching Protocols",
     contentType = "websocket",
     requestHeaders = mapOf("Upgrade" to "websocket", "Connection" to "Upgrade"),
     responseHeaders = mapOf("Upgrade" to "websocket", "Connection" to "Upgrade"),
     durationMs = 13000,
     timestamp = now.minusSeconds(15),
     state = com.nadeem.apkscope.core.network.traffic.TrafficCaptureState.DECODED,
     webSocketSession = wssSession
    )
   )

   assertTrue("Store must contain populated demo records", com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore.all().size >= 6)
  }

  /**
   * Real (non-bypass) HTTP/2 relay test: drives an actual TLS handshake with h2 offered via ALPN
   * through the live [HttpsInspectionEngine.handleClient] -> [HttpsInspectionEngine.handleTlsClient]
   * dispatch path, so the engine's own ALPN negotiation on both legs decides to route this
   * connection into [com.nadeem.apkscope.core.network.traffic.Http2RelayHandler] rather than the
   * HTTP/1.1 relay loop. Speaks genuine HTTP/2 frames against a real public h2-capable server
   * (httpbin.org, already used elsewhere in this file for HTTP/1.1 verification and confirmed to
   * negotiate h2 via ALPN) and verifies both the forwarded response and the captured
   * TrafficRecord — unlike the previous version of this test, nothing here constructs
   * Http2RelayHandler directly or bypasses TLS/ALPN.
   */
  @Test
  fun testHttp2RelayOnDevice() {
    val resolved = try { InetAddress.getByName("httpbin.org") } catch (_: Exception) { null }
    if (resolved == null) return

    val caManager = CaManager(caDir)
    val sink = TestTunSink()
    val engine = HttpsInspectionEngine(sink, caManager)
    engine.start()

    try {
      val socket = Socket("127.0.0.1", engine.boundPort)
      val dos = java.io.DataOutputStream(socket.getOutputStream())
      dos.write(engine.authToken)
      dos.write(resolved.address)
      dos.writeShort(443)
      dos.writeInt(-1) // Milestone 9: no owner UID in this hand-crafted test preamble (pre-existing test pattern)
      val sniBytes = "httpbin.org".toByteArray(Charsets.UTF_8)
      dos.writeShort(sniBytes.size)
      dos.write(sniBytes)
      dos.flush()

      val caCert = caManager.getCaCertificate()!!
      val clientCtx = createClientSslContext(caCert)
      val sslSocket = clientCtx.socketFactory.createSocket(socket, "httpbin.org", 443, true) as SSLSocket
      sslSocket.soTimeout = 20000
      val params = sslSocket.sslParameters
      params.applicationProtocols = arrayOf("h2", "http/1.1")
      sslSocket.sslParameters = params
      sslSocket.startHandshake()

      assertEquals(
        "Downstream leg must negotiate h2 via ALPN for the engine to route into Http2RelayHandler",
        "h2", sslSocket.applicationProtocol
      )

      val h2 = SimpleHttp2Client(sslSocket)
      h2.start()

      val reqHeaders = listOf(
        Header(":method", "GET"),
        Header(":path", "/get"),
        Header(":scheme", "https"),
        Header(":authority", "httpbin.org")
      )
      h2.sendRequest(streamId = 1, headers = reqHeaders)

      val gotResponse = h2.pumpUntil(20000) { h2.streamClosed.contains(1) && h2.responseHeaders.containsKey(1) }
      assertTrue("Must receive a complete HTTP/2 response for stream 1 relayed from the real upstream", gotResponse)

      assertEquals("200", h2.responseHeaders[1]?.get(":status"))
      val body = String(h2.bodyOf(1), Charsets.UTF_8)
      assertTrue(
        "Response body must contain the decoded JSON echo forwarded through the live relay",
        body.contains("httpbin.org/get") || body.contains("\"url\"")
      )

      try { sslSocket.close() } catch (_: Exception) {}

      var record: com.nadeem.apkscope.core.network.traffic.TrafficRecord? = null
      val deadline = System.currentTimeMillis() + 5000
      while (System.currentTimeMillis() < deadline) {
        record = com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore.all().firstOrNull {
          it.host == "httpbin.org" && it.url.endsWith("/get") && it.responseBody != null
        }
        if (record != null) break
        Thread.sleep(100)
      }
      assertNotNull("Real HTTP/2 GET through the live relay must be captured in TrafficInspectionStore", record)
      assertEquals(com.nadeem.apkscope.core.network.traffic.TrafficProtocol.HTTPS_2, record!!.protocol)
      assertEquals(200, record.statusCode)
      assertTrue(
        "Captured record body must match what the real relay forwarded",
        record.responseBody!!.contains("httpbin.org/get") || record.responseBody!!.contains("\"url\"")
      )
    } finally {
      engine.stop()
    }
  }

  /**
   * Integrates gRPC inspection into a real HTTP/2 stream: same live engine ALPN-negotiated h2
   * path as [testHttp2RelayOnDevice], but the upstream target is Google's public gRPC
   * interoperability test server, which implements the standard `grpc.testing.TestService`.
   * `EmptyCall` is used because both its request and response are `google.protobuf.Empty`,
   * which serializes to zero bytes — allowing a genuine gRPC exchange without a protobuf
   * codegen dependency in this test.
   */
  @Test
  fun testHttp2GrpcRelayOnDevice() {
    val host = "grpc-test.sandbox.googleapis.com"
    val resolved = try { InetAddress.getByName(host) } catch (_: Exception) { null }
    if (resolved == null) return

    val caManager = CaManager(caDir)
    val sink = TestTunSink()
    val engine = HttpsInspectionEngine(sink, caManager)
    engine.start()

    try {
      val socket = Socket("127.0.0.1", engine.boundPort)
      val dos = java.io.DataOutputStream(socket.getOutputStream())
      dos.write(engine.authToken)
      dos.write(resolved.address)
      dos.writeShort(443)
      dos.writeInt(-1) // Milestone 9: no owner UID in this hand-crafted test preamble (pre-existing test pattern)
      val sniBytes = host.toByteArray(Charsets.UTF_8)
      dos.writeShort(sniBytes.size)
      dos.write(sniBytes)
      dos.flush()

      val caCert = caManager.getCaCertificate()!!
      val clientCtx = createClientSslContext(caCert)
      val sslSocket = clientCtx.socketFactory.createSocket(socket, host, 443, true) as SSLSocket
      sslSocket.soTimeout = 20000
      val params = sslSocket.sslParameters
      params.applicationProtocols = arrayOf("h2", "http/1.1")
      sslSocket.sslParameters = params
      sslSocket.startHandshake()

      assertEquals("Downstream leg must negotiate h2 via ALPN", "h2", sslSocket.applicationProtocol)

      val h2 = SimpleHttp2Client(sslSocket)
      h2.start()

      val reqHeaders = listOf(
        Header(":method", "POST"),
        Header(":path", "/grpc.testing.TestService/EmptyCall"),
        Header(":scheme", "https"),
        Header(":authority", host),
        Header("content-type", "application/grpc"),
        Header("te", "trailers")
      )
      // google.protobuf.Empty serializes to zero bytes; gRPC still requires the 5-byte length-prefix frame.
      val grpcEmptyFrame = byteArrayOf(0, 0, 0, 0, 0)
      h2.sendRequest(streamId = 1, headers = reqHeaders, body = grpcEmptyFrame)

      val gotTrailers = h2.pumpUntil(20000) { h2.trailerHeaders.containsKey(1) }
      assertTrue("Must receive gRPC response headers + trailers relayed from the real upstream", gotTrailers)

      assertEquals("200", h2.responseHeaders[1]?.get(":status"))
      assertEquals("0", h2.trailerHeaders[1]?.get("grpc-status"))

      try { sslSocket.close() } catch (_: Exception) {}

      var record: com.nadeem.apkscope.core.network.traffic.TrafficRecord? = null
      val deadline = System.currentTimeMillis() + 5000
      while (System.currentTimeMillis() < deadline) {
        record = com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore.all().firstOrNull {
          it.host == host && it.url.endsWith("/grpc.testing.TestService/EmptyCall")
        }
        if (record != null && record.grpcSession?.grpcStatus != null) break
        record = null
        Thread.sleep(100)
      }
      assertNotNull("Real gRPC call over HTTP/2 through the live relay must be captured", record)
      assertEquals(com.nadeem.apkscope.core.network.traffic.TrafficProtocol.GRPC, record!!.protocol)
      assertNotNull("gRPC session data must be present", record.grpcSession)
      assertEquals("grpc.testing.TestService", record.grpcSession!!.serviceName)
      assertEquals("EmptyCall", record.grpcSession!!.methodName)
      assertEquals(0, record.grpcSession!!.grpcStatus)
      assertTrue("Must capture at least the outbound gRPC message", record.grpcSession!!.messages.isNotEmpty())
    } finally {
      engine.stop()
    }
  }

  /**
   * Integrates incremental SSE capture into the HTTP/2 path (the HTTP/1.1 path is already
   * covered independently by [testHttp11SseStreamingOnDevice] and does not depend on this test).
   * Streams Wikimedia's public, long-standing recentchange SSE feed — which negotiates h2 via
   * ALPN — through the live relay and confirms incremental events are captured while the stream
   * is still open, then closes early rather than waiting for a naturally-terminating stream.
   */
  @Test
  fun testHttp2SseStreamingOnDevice() {
    val host = "stream.wikimedia.org"
    val resolved = try { InetAddress.getByName(host) } catch (_: Exception) { null }
    if (resolved == null) return

    val caManager = CaManager(caDir)
    val sink = TestTunSink()
    val engine = HttpsInspectionEngine(sink, caManager)
    engine.start()

    try {
      val socket = Socket("127.0.0.1", engine.boundPort)
      val dos = java.io.DataOutputStream(socket.getOutputStream())
      dos.write(engine.authToken)
      dos.write(resolved.address)
      dos.writeShort(443)
      dos.writeInt(-1) // Milestone 9: no owner UID in this hand-crafted test preamble (pre-existing test pattern)
      val sniBytes = host.toByteArray(Charsets.UTF_8)
      dos.writeShort(sniBytes.size)
      dos.write(sniBytes)
      dos.flush()

      val caCert = caManager.getCaCertificate()!!
      val clientCtx = createClientSslContext(caCert)
      val sslSocket = clientCtx.socketFactory.createSocket(socket, host, 443, true) as SSLSocket
      sslSocket.soTimeout = 20000
      val params = sslSocket.sslParameters
      params.applicationProtocols = arrayOf("h2", "http/1.1")
      sslSocket.sslParameters = params
      sslSocket.startHandshake()

      assertEquals("Downstream leg must negotiate h2 via ALPN", "h2", sslSocket.applicationProtocol)

      val h2 = SimpleHttp2Client(sslSocket)
      h2.start()

      val reqHeaders = listOf(
        Header(":method", "GET"),
        Header(":path", "/v2/stream/recentchange"),
        Header(":scheme", "https"),
        Header(":authority", host),
        Header("accept", "text/event-stream"),
        // Wikimedia's API etiquette policy requires a descriptive User-Agent on every request;
        // requests without one are rejected with 403 regardless of the rest of the request.
        Header("user-agent", "apk-scope-integration-test/1.0 (+https://github.com/nadeem/apkscope)")
      )
      h2.sendRequest(streamId = 1, headers = reqHeaders)

      // Recent-change events fire continuously; a bounded pump is enough to observe several,
      // and the stream never terminates on its own so we always close early.
      val gotHeaders = h2.pumpUntil(15000) { h2.responseHeaders.containsKey(1) }
      assertTrue("Must receive SSE response headers relayed from the real upstream", gotHeaders)
      assertEquals("200", h2.responseHeaders[1]?.get(":status"))
      assertTrue(
        "Response content-type must be text/event-stream",
        h2.responseHeaders[1]?.get("content-type")?.contains("text/event-stream") == true
      )

      var record: com.nadeem.apkscope.core.network.traffic.TrafficRecord? = null
      val deadline = System.currentTimeMillis() + 15000
      while (System.currentTimeMillis() < deadline) {
        h2.pumpFor(500)
        record = com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore.all().firstOrNull {
          it.host == host && it.sseSession != null && it.sseSession!!.events.isNotEmpty()
        }
        if (record != null) break
      }

      try { sslSocket.close() } catch (_: Exception) {}

      assertNotNull("Real SSE-over-HTTP/2 stream through the live relay must be captured with at least one event", record)
      assertEquals(com.nadeem.apkscope.core.network.traffic.TrafficProtocol.SSE, record!!.protocol)
      assertNotNull("SSE session data must be present", record.sseSession)
      assertTrue("Must capture at least one incremental SSE event", record.sseSession!!.events.isNotEmpty())
    } finally {
      engine.stop()
    }
  }

  /**
   * Genuine HTTP/1.1 SSE integration: same live engine dispatch path as the HTTP/2 SSE test, but
   * the client deliberately does not offer h2 via ALPN (mirroring an intercepted app whose own
   * HTTP stack only speaks HTTP/1.1), even though the real upstream (Wikimedia's recentchange
   * feed) does negotiate h2. This exercises [HttpsInspectionEngine]'s upstream-ALPN-mismatch
   * fallback: the engine must detect that the downstream leg can't do h2 and re-establish the
   * upstream leg as HTTP/1.1-only, so relayHttp11's text framing is talking to a real HTTP/1.1
   * response rather than binary HTTP/2 frames. A local loopback "fake server" is deliberately not
   * used here: DestinationPolicy correctly and intentionally denies loopback/private destinations
   * (SSRF protection for the sandboxed app's traffic) — weakening that to accommodate a test
   * endpoint is exactly what project policy prohibits, so this test goes over the real network
   * like the other genuine-verification tests in this file.
   */
  @Test
  fun testHttp11SseStreamingOnDevice() {
    val host = "stream.wikimedia.org"
    val resolved = try { InetAddress.getByName(host) } catch (_: Exception) { null }
    if (resolved == null) return

    val caManager = CaManager(caDir)
    val sink = TestTunSink()
    val engine = HttpsInspectionEngine(sink, caManager)
    engine.start()

    try {
      val socket = Socket("127.0.0.1", engine.boundPort)
      val dos = java.io.DataOutputStream(socket.getOutputStream())
      dos.write(engine.authToken)
      dos.write(resolved.address)
      dos.writeShort(443)
      dos.writeInt(-1) // Milestone 9: no owner UID in this hand-crafted test preamble (pre-existing test pattern)
      val sniBytes = host.toByteArray(Charsets.UTF_8)
      dos.writeShort(sniBytes.size)
      dos.write(sniBytes)
      dos.flush()

      // No ALPN offered at all: this client only speaks HTTP/1.1.
      val caCert = caManager.getCaCertificate()!!
      val clientCtx = createClientSslContext(caCert)
      val sslSocket = clientCtx.socketFactory.createSocket(socket, host, 443, true) as SSLSocket
      sslSocket.soTimeout = 10000
      sslSocket.startHandshake()

      assertTrue(
        "Downstream leg must not negotiate h2 (this test exercises the HTTP/1.1 path)",
        sslSocket.applicationProtocol.isNullOrEmpty()
      )

      // Deliberately no "Connection: close": SSE needs a persistent connection to actually stream
      // (a close-request measurably causes the server to end the response right after headers,
      // with no event data at all). The bounded read loop below closes the socket itself instead.
      val req = "GET /v2/stream/recentchange HTTP/1.1\r\n" +
        "Host: $host\r\n" +
        "Accept: text/event-stream\r\n" +
        // Wikimedia's API etiquette policy requires a descriptive User-Agent on every request.
        "User-Agent: apk-scope-integration-test/1.0 (+https://github.com/nadeem/apkscope)\r\n\r\n"
      sslSocket.outputStream.write(req.toByteArray(Charsets.ISO_8859_1))
      sslSocket.outputStream.flush()

      // Read a bounded window of the live stream rather than to EOF — it never closes on its own.
      val buf = ByteArray(8192)
      val accumulated = StringBuilder()
      val deadline = System.currentTimeMillis() + 8000
      while (System.currentTimeMillis() < deadline && accumulated.length < 20000) {
        val n = try { sslSocket.inputStream.read(buf) } catch (_: Exception) { -1 }
        if (n < 0) break
        accumulated.append(String(buf, 0, n, Charsets.UTF_8))
        if (accumulated.contains("data:")) break
      }
      try { sslSocket.close() } catch (_: Exception) {}

      assertTrue("Response must be a 200 OK forwarded through the HTTP/1.1 relay", accumulated.startsWith("HTTP/1.1 200"))
      assertTrue(
        "Must observe real SSE event content relayed through the HTTP/1.1 path",
        accumulated.contains("data:")
      )

      var record: com.nadeem.apkscope.core.network.traffic.TrafficRecord? = null
      val recordDeadline = System.currentTimeMillis() + 5000
      while (System.currentTimeMillis() < recordDeadline) {
        record = com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore.all().firstOrNull {
          it.host == host && it.sseSession != null && it.sseSession!!.events.isNotEmpty()
        }
        if (record != null) break
        Thread.sleep(100)
      }
      assertNotNull("Real SSE-over-HTTP/1.1 stream through the live relay must be captured with at least one event", record)
      assertEquals(com.nadeem.apkscope.core.network.traffic.TrafficProtocol.HTTPS, record!!.protocol)
      assertNotNull("SSE session data must be present", record.sseSession)
      assertTrue("Must capture at least one incremental SSE event", record.sseSession!!.events.isNotEmpty())
    } finally {
      engine.stop()
    }
  }

  @Test
  fun testGrpcInspectionOnDevice() {
   val messages = mutableListOf<com.nadeem.apkscope.core.network.traffic.GrpcMessage>()
   val decoder = com.nadeem.apkscope.core.network.traffic.GrpcMessageDecoder(
    direction = com.nadeem.apkscope.core.network.traffic.Direction.INBOUND
   ) { messages.add(it) }

   val payload = "device-grpc-payload".toByteArray()
   val header = java.nio.ByteBuffer.allocate(5).order(java.nio.ByteOrder.BIG_ENDIAN)
    .put(0.toByte())
    .putInt(payload.size)
    .array()

   decoder.feed(header + payload)

   assertEquals(1, messages.size)
   val msg = messages[0]
   assertEquals(1, msg.sequence)
   assertEquals(payload.size, msg.length)
   assertTrue(msg.payloadPreview.contains("device-grpc-payload"))
  }

  @Test
  fun testSseStreamingOnDevice() {
   val events = mutableListOf<com.nadeem.apkscope.core.network.traffic.SseEvent>()
   val parser = com.nadeem.apkscope.core.network.traffic.SseEventParser { events.add(it) }

   parser.feedText("id: 42\nevent: update\ndata: line 1\ndata: line 2\n\n")

   assertEquals(1, events.size)
   val evt = events[0]
   assertEquals("42", evt.id)
   assertEquals("update", evt.eventType)
   assertEquals("line 1\nline 2", evt.data)
  }

  @Test
  fun testStaticAnalysisOnDevice() = kotlinx.coroutines.runBlocking {
   val apkFile = File("/data/local/tmp/fixture-debug.apk")
   assertTrue("fixture-debug.apk must exist on device", apkFile.exists() && apkFile.canRead())

   val result = com.nadeem.apkscope.core.staticanalysis.ApkAnalyzer.analyze(context, apkFile)
   assertNotNull("ApkAnalyzer result must not be null", result)
   assertTrue("Must extract embedded URLs from DEX", result.metadata.embeddedUrls.isNotEmpty())
   assertTrue("Must detect SDKs", result.metadata.detectedSdks.isNotEmpty())
   assertTrue("Must detect security API findings", result.metadata.apiFindings.isNotEmpty())
   assertTrue("Must inspect DEX files in coverage", result.metadata.staticCoverage.dexFilesInspected.isNotEmpty())

   val riskAssessment = com.nadeem.apkscope.core.risk.DefaultRiskEngine().evaluate(result.toRiskInput())
   val repo = com.nadeem.apkscope.domain.SessionRepository(context)
   val sessionId = "m8_fixture_session"
   repo.persistCompletedAnalysis(
    sessionId = sessionId,
    appName = "Risk Fixture App",
    analyzedAtEpochMs = System.currentTimeMillis(),
    result = result,
    risk = riskAssessment
   )

   val persisted = repo.getPersisted(sessionId)
   assertNotNull("Persisted analysis must be retrievable", persisted)
   assertTrue("Embedded URLs must be populated in persisted analysis", persisted!!.embeddedUrls.isNotEmpty())
   assertTrue("Detected SDKs must be populated in persisted analysis", persisted.detectedSdks.isNotEmpty())
   assertTrue("API findings must be populated in persisted analysis", persisted.apiFindings.isNotEmpty())
  }

  /**
   * Proves the [com.nadeem.apkscope.core.staticanalysis.ApkAnalyzer.analyze] `observedRuntimeHosts`
   * parameter genuinely reaches [com.nadeem.apkscope.core.staticanalysis.DexUrlExtractor] end-to-end
   * against the real fixture APK, and that a host match alone attaches only non-elevating
   * `hostCorrelation` metadata — never `RUNTIME_OBSERVED`, which requires exact URL evidence this
   * call was not given. `fixture-debug.apk` hardcodes `https://httpbin.org/...` calls, so passing
   * "httpbin.org" here is a genuine match, not a contrived one.
   */
  @Test
  fun testApkAnalyzerAttachesHostCorrelationOnDevice() = kotlinx.coroutines.runBlocking {
   val apkFile = File("/data/local/tmp/fixture-debug.apk")
   assertTrue("fixture-debug.apk must exist on device", apkFile.exists() && apkFile.canRead())

   val result = com.nadeem.apkscope.core.staticanalysis.ApkAnalyzer.analyze(
    context, apkFile, observedRuntimeHosts = setOf("httpbin.org")
   )

   val httpbinCandidates = result.metadata.embeddedUrls.filter { it.host == "httpbin.org" }
   assertTrue("Fixture must embed at least one httpbin.org URL", httpbinCandidates.isNotEmpty())
   assertTrue(
    "Every httpbin.org candidate must carry host-correlation metadata",
    httpbinCandidates.all { it.hostCorrelation != null }
   )
   assertTrue(
    "Host correlation alone must never assign RUNTIME_OBSERVED (no exact URL evidence was given)",
    httpbinCandidates.none { it.provenance == com.nadeem.apkscope.core.staticanalysis.UrlProvenance.RUNTIME_OBSERVED }
   )

   val otherHostCandidates = result.metadata.embeddedUrls.filter { it.host != "httpbin.org" }
   assertTrue(
    "URLs on other hosts must not receive host-correlation for a host they don't match",
    otherHostCandidates.all { it.hostCorrelation == null }
   )
  }

  /**
   * Minimal, hand-rolled HTTP/2 client driver used to speak genuine h2 frames over an
   * already ALPN-negotiated [SSLSocket], so integration tests can drive real requests through
   * the live [HttpsInspectionEngine] -> [com.nadeem.apkscope.core.network.traffic.Http2RelayHandler]
   * path against real remote HTTP/2 servers, instead of feeding synthetic frames to the relay
   * directly. Plays the client role only: it never expects the peer to send a connection
   * preface (only clients send that), and it acks every SETTINGS frame it receives so a real
   * server never stalls waiting on us.
   */
  private class SimpleHttp2Client(sslSocket: SSLSocket) {
    private val reader = Http2Reader(sslSocket.inputStream.source().buffer(), true)
    private val writer = Http2Writer(sslSocket.outputStream.sink().buffer(), true)

    val responseHeaders = java.util.concurrent.ConcurrentHashMap<Int, Map<String, String>>()
    val trailerHeaders = java.util.concurrent.ConcurrentHashMap<Int, Map<String, String>>()
    private val bodies = java.util.concurrent.ConcurrentHashMap<Int, Buffer>()
    val streamClosed: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val handler = object : Http2Reader.Handler {
      override fun headers(inFinished: Boolean, streamId: Int, associatedStreamId: Int, headerBlock: List<Header>) {
        val map = headerBlock.associate { it.name.utf8() to it.value.utf8() }
        if (map.containsKey(":status")) responseHeaders[streamId] = map else trailerHeaders[streamId] = map
        if (inFinished) streamClosed.add(streamId)
      }
      override fun data(inFinished: Boolean, streamId: Int, source: BufferedSource, length: Int) {
        val buf = bodies.getOrPut(streamId) { Buffer() }
        source.readFully(buf, length.toLong())
        if (inFinished) streamClosed.add(streamId)
      }
      override fun settings(clearPrevious: Boolean, settings: Settings) {
        try { writer.applyAndAckSettings(settings); writer.flush() } catch (_: Exception) {}
      }
      override fun ackSettings() {}
      override fun rstStream(streamId: Int, errorCode: ErrorCode) { streamClosed.add(streamId) }
      override fun ping(ack: Boolean, payload1: Int, payload2: Int) {
        if (!ack) try { writer.ping(true, payload1, payload2); writer.flush() } catch (_: Exception) {}
      }
      override fun goAway(lastGoodStreamId: Int, errorCode: ErrorCode, debugData: ByteString) {}
      override fun windowUpdate(streamId: Int, windowSizeIncrement: Long) {}
      override fun priority(streamId: Int, streamDependency: Int, weight: Int, exclusive: Boolean) {}
      override fun pushPromise(streamId: Int, promisedStreamId: Int, requestHeaders: List<Header>) {}
      override fun alternateService(streamId: Int, origin: String, protocol: ByteString, host: String, port: Int, maxAge: Long) {}
    }

    fun start() {
      writer.connectionPreface()
      writer.settings(Settings())
      writer.flush()
    }

    fun sendRequest(streamId: Int, headers: List<Header>, body: ByteArray? = null) {
      writer.headers(body == null, streamId, headers)
      writer.flush()
      if (body != null) {
        val buf = Buffer().apply { write(body) }
        writer.data(true, streamId, buf, body.size)
        writer.flush()
      }
    }

    fun bodyOf(streamId: Int): ByteArray = bodies[streamId]?.clone()?.readByteArray() ?: ByteArray(0)

    /** Pumps frames until [predicate] is satisfied or [timeoutMs] elapses. Returns whether it was satisfied. */
    fun pumpUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
      val deadline = System.currentTimeMillis() + timeoutMs
      while (!predicate()) {
        if (System.currentTimeMillis() >= deadline) return false
        try {
          if (!reader.nextFrame(false, handler)) return predicate()
        } catch (_: java.io.IOException) {
          return predicate()
        }
      }
      return true
    }

    /** Pumps frames for a fixed window regardless of predicate — used to drain an unbounded stream (e.g. SSE). */
    fun pumpFor(durationMs: Long) {
      val deadline = System.currentTimeMillis() + durationMs
      while (System.currentTimeMillis() < deadline) {
        try {
          if (!reader.nextFrame(false, handler)) break
        } catch (_: java.io.IOException) {
          break
        }
      }
    }
  }

  private fun createClientSslContext(caCert: java.security.cert.X509Certificate): javax.net.ssl.SSLContext {
   val trustManager = object : javax.net.ssl.X509TrustManager {
    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
     android.util.Log.i("HttpsTest", "checkServerTrusted CALLED with chain size=${chain?.size}")
     if (chain.isNullOrEmpty()) throw java.security.cert.CertificateException("Empty chain")
     chain[0].verify(caCert.publicKey)
     android.util.Log.i("HttpsTest", "checkServerTrusted VERIFIED SUCCESSFULLY")
    }
    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf(caCert)
   }
   val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
   sslContext.init(null, arrayOf(trustManager), java.security.SecureRandom())
   return sslContext
  }
}
