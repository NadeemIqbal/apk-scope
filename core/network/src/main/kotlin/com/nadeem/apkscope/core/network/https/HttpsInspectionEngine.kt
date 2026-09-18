package com.nadeem.apkscope.core.network.https

import com.nadeem.apkscope.core.network.DestinationPolicy
import com.nadeem.apkscope.core.network.TunSink
import com.nadeem.apkscope.core.network.traffic.Direction
import com.nadeem.apkscope.core.network.traffic.Http2RelayHandler
import com.nadeem.apkscope.core.network.traffic.MessageType
import com.nadeem.apkscope.core.network.traffic.SseEvent
import com.nadeem.apkscope.core.network.traffic.SseEventParser
import com.nadeem.apkscope.core.network.traffic.SseSessionData
import com.nadeem.apkscope.core.network.traffic.TrafficCaptureState
import com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore
import com.nadeem.apkscope.core.network.traffic.TrafficProtocol
import com.nadeem.apkscope.core.network.traffic.TrafficRecord
import com.nadeem.apkscope.core.network.traffic.WebSocketFrameParser
import com.nadeem.apkscope.core.network.traffic.WebSocketMessage
import com.nadeem.apkscope.core.network.traffic.WebSocketSessionData
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Local in-process traffic inspection engine running inside the Work Profile.
 *
 * Implements:
 * - Plaintext HTTP/1.1 inspection on port 80/8080 and other non-TLS ports.
 * - Optional dual-leg TLS interception for HTTPS on port 443 with platform trust verification.
 * - WebSocket upgrade detection (101 Switching Protocols) and RFC 6455 recording on ws:// and wss://.
 * - Upstream server certificate validation using standard system trust and hostname verification.
 * - Dynamic leaf certificate generation using the local [CaManager].
 * - Bounded previews (64 KiB) and secret redaction before persistence.
 * - Transparent, non-corrupting forwarding of all application payloads.
 */
class HttpsInspectionEngine(
    private val sink: TunSink,
    private val caManager: CaManager,
    private val sessionId: String? = null,
    private val targetPackage: String? = null
) {

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "traffic-worker").apply { isDaemon = true }
    }
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    val authToken: ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }

    @Volatile
    var boundPort: Int = 0
        private set

    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        boundPort = ss.localPort

        acceptThread = Thread({ acceptLoop(ss) }, "traffic-inspector").apply {
            isDaemon = true
            start()
        }
        sink.onEvidence("HttpsInspectionEngine.start", "PASS", "port=$boundPort")
    }

    @Synchronized
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        boundPort = 0

        activeSockets.forEach { s ->
            try { s.close() } catch (_: Exception) {}
        }
        activeSockets.clear()

        executor.shutdownNow()
        try { executor.awaitTermination(1, TimeUnit.SECONDS) } catch (_: Exception) {}
        acceptThread = null
        sink.onEvidence("HttpsInspectionEngine.stop", "PASS", "stopped")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val clientSocket = try {
                ss.accept()
            } catch (_: Exception) {
                break
            }
            activeSockets.add(clientSocket)
            executor.submit {
                try {
                    handleClient(clientSocket)
                } finally {
                    activeSockets.remove(clientSocket)
                    try { clientSocket.close() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun handleClient(clientSocket: Socket) {
        clientSocket.soTimeout = 15000
        val clientIn = clientSocket.getInputStream()
        val clientOut = clientSocket.getOutputStream()

        // 1. Read and verify preamble: [16 bytes token][4 bytes IP][2 bytes Port][2 bytes SNI length][N bytes SNI]
        val dataIn = DataInputStream(clientIn)
        val tokenBytes = ByteArray(16)
        try {
            dataIn.readFully(tokenBytes)
        } catch (_: Exception) {
            return
        }
        if (!tokenBytes.contentEquals(authToken)) {
            android.util.Log.w("TrafficEngine", "Rejecting connection: invalid auth token from ${clientSocket.remoteSocketAddress}")
            return
        }

        val remoteIpBytes = ByteArray(4)
        try {
            dataIn.readFully(remoteIpBytes)
        } catch (_: Exception) {
            return
        }
        val remotePort = dataIn.readUnsignedShort()
        val remoteIpStr = remoteIpBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }

        // Milestone 9 (userspace traffic ownership verification): the 4-byte owner UID TcpProxy
        // resolved for this connection's *original* 4-tuple (-1 = TcpProxy could not determine it —
        // see that side's own ConnDiag log for the specific reason; never fabricated as "no owner"
        // here). Preamble format: [16 token][4 ip][2 port][4 ownerUid][2 sniLen][sni] — every caller
        // that constructs this preamble (production TcpProxy and every hand-crafted test preamble in
        // this codebase) must include this field; there is no backward-compatible "defensive read"
        // for a fixed-format stream protocol like this one (see git history for why an appended,
        // optimistically-read trailing field was reverted — it silently corrupted payload framing
        // for any sender using the older format instead of degrading gracefully). This engine only
        // compares the resolved UID against the session's target UID — it never re-resolves
        // connection ownership itself, since only TcpProxy has the real tuple.
        val observedOwnerUidRaw = dataIn.readInt()

        val sniLen = dataIn.readUnsignedShort()
        val sniHost = if (sniLen > 0) {
            val sb = ByteArray(sniLen)
            dataIn.readFully(sb)
            String(sb, Charsets.UTF_8)
        } else null

        val targetHost = sniHost ?: remoteIpStr
        android.util.Log.i("TrafficEngine", "PREAMBLE_RECEIVED host=$targetHost port=$remotePort sniPresent=${sniHost != null} observedOwnerUid=$observedOwnerUidRaw")

        val ownership = computeOwnershipVerification(observedOwnerUidRaw)
        android.util.Log.i("TrafficEngine", "OWNERSHIP_VERIFICATION host=$targetHost status=${ownership.status} observedOwnerUid=${ownership.observedOwnerUid} reason=${ownership.failureReason}")

        // 2. Validate destination policy
        val policy = DestinationPolicy.evaluate(DestinationPolicy.Destination.V4(remoteIpBytes), sink.localSubnets)
        android.util.Log.i("TrafficEngine", "POLICY_DECISION host=$targetHost verdict=${policy.verdict} reason=${policy.reason}")
        if (policy.verdict == DestinationPolicy.Verdict.DENY) {
            sink.onEvidence("TrafficInspection.policy", "DENIED", "dst=$remoteIpStr:$remotePort reason=${policy.reason}")
            HttpsInspectionStore.record(
                HttpsTransaction(
                    method = "CONNECT",
                    url = "https://$remoteIpStr:$remotePort",
                    host = remoteIpStr,
                    port = remotePort,
                    state = HttpsCaptureState.ENCRYPTED,
                    failureDetails = "DestinationPolicy denied connection: ${policy.reason}"
                ),
                sessionId = sessionId,
                targetPackage = targetPackage,
                ownership = ownership
            )
            return
        }

        // 3. Handle based on TLS (port 443) vs Plaintext (other ports)
        if (remotePort == 443) {
            handleTlsClient(clientSocket, clientIn, clientOut, remoteIpBytes, remotePort, targetHost, sniHost, ownership)
        } else {
            handlePlaintextClient(clientSocket, clientIn, clientOut, remoteIpBytes, remotePort, targetHost, ownership)
        }
    }

    /**
     * Milestone 9 (userspace traffic ownership verification): compares the connection's real owner
     * UID (as resolved by `TcpProxy`, carried across the preamble) against the session's resolved
     * target UID. Neither side is ever assumed — a missing target UID (package not yet installed —
     * see Phase 9.1's Prepare-Sequence finding) or a missing observed UID (TcpProxy's own lookup
     * failed) both produce [OwnershipVerificationStatus.UNKNOWN] with a specific reason, never a
     * silent [OwnershipVerificationStatus.MATCHED].
     */
    private fun computeOwnershipVerification(observedOwnerUidRaw: Int): com.nadeem.apkscope.core.network.traffic.OwnershipVerification {
        val observedOwnerUid = if (observedOwnerUidRaw < 0) null else observedOwnerUidRaw
        if (observedOwnerUid == null) {
            return com.nadeem.apkscope.core.network.traffic.OwnershipVerification(
                null, com.nadeem.apkscope.core.network.traffic.OwnershipVerificationStatus.UNKNOWN,
                "connection owner UID could not be resolved (see TcpProxy ConnDiag log)",
            )
        }
        if (targetPackage.isNullOrEmpty()) {
            return com.nadeem.apkscope.core.network.traffic.OwnershipVerification(
                observedOwnerUid, com.nadeem.apkscope.core.network.traffic.OwnershipVerificationStatus.UNKNOWN,
                "no target package configured for this session",
            )
        }
        val targetUid = sink.resolveTargetUid(targetPackage)
            ?: return com.nadeem.apkscope.core.network.traffic.OwnershipVerification(
                observedOwnerUid, com.nadeem.apkscope.core.network.traffic.OwnershipVerificationStatus.UNKNOWN,
                "target package \"$targetPackage\" UID not yet resolvable in this profile",
            )
        return if (observedOwnerUid == targetUid) {
            com.nadeem.apkscope.core.network.traffic.OwnershipVerification(observedOwnerUid, com.nadeem.apkscope.core.network.traffic.OwnershipVerificationStatus.MATCHED, null)
        } else {
            com.nadeem.apkscope.core.network.traffic.OwnershipVerification(observedOwnerUid, com.nadeem.apkscope.core.network.traffic.OwnershipVerificationStatus.MISMATCHED, null)
        }
    }

    private fun createProtectedUpstreamSocket(): Socket {
        // A plain Socket, not a SocketChannel.open().socket() adapter: VpnService.protect(Socket)
        // accepts either, and TcpProxy's own use of SocketChannel is for a real reason (non-blocking,
        // Selector-multiplexed I/O across many concurrent flows) that doesn't apply here — this is a
        // single blocking connection per relay. The channel-adapter socket previously used here silently
        // dropped a write that followed an earlier one on the same connection (reproduced with a real
        // HTTP/2 POST: the request HEADERS frame was written and read back fine, but a DATA frame
        // written ~200ms later over the same adapter socket never reached the real upstream server —
        // no exception, no partial-write signal, the peer just never saw it). A plain Socket does not
        // reproduce this; every HTTP/2 relay integration test that sends a request body depends on it.
        val socket = Socket()
        // DIAGNOSED (Checkpoint 8.9 acceptance scenario): VpnService.protect(Socket) was observed to
        // return false 100% of the time for a bare `Socket()` here, while TcpProxy's SocketChannel-backed
        // sockets protect successfully every time on the same VpnService instance in the same session
        // (see ConnDiag PROTECT_RESULT events). A newly constructed java.net.Socket() has no live
        // underlying file descriptor until it is bound or connected — protect() has nothing to attach to
        // and silently fails. Binding to an ephemeral local port first forces real fd creation without
        // changing this from a blocking Socket (preserving the HTTP/2 POST-body fix documented above).
        socket.bind(InetSocketAddress(0))
        val protected = sink.protectSocket(socket)
        if (!protected) {
            // Fail closed: an unprotected "upstream" socket is not actually excluded from this VPN's own
            // TUN routing, so traffic sent on it loops back into TcpProxy as a new inbound connection to
            // the same destination instead of ever reaching the real network (see the self-loop analysis
            // above) — silently returning it here previously let every caller proceed as if a genuine
            // upstream connection existed, until the caller's own read/write eventually timed out with no
            // diagnostic pointing at the real cause. Close it immediately and surface a real, typed
            // exception instead, so every call site's existing exception handling (which already records
            // TLS_HANDSHAKE_FAILED / ENCRYPTED / TCP_PASSTHROUGH failures to the inspection stores) reports
            // this as what it is rather than a generic downstream timeout.
            android.util.Log.e("TrafficEngine", "VpnService.protect returned false on upstream socket: $socket — closing, refusing to relay unprotected")
            try { socket.close() } catch (_: Exception) {}
            throw java.io.IOException("VpnService.protect() failed for upstream socket — refusing to forward traffic outside VPN protection")
        }
        return socket
    }

    private fun handlePlaintextClient(
        clientSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        remoteIpBytes: ByteArray,
        remotePort: Int,
        targetHost: String,
        ownership: com.nadeem.apkscope.core.network.traffic.OwnershipVerification
    ) {
        var upstreamRaw: Socket? = null
        try {
            upstreamRaw = createProtectedUpstreamSocket()
            activeSockets.add(upstreamRaw)

            upstreamRaw.connect(InetSocketAddress(InetAddress.getByAddress(remoteIpBytes), remotePort), 10000)
            upstreamRaw.soTimeout = 15000

            val uIn = upstreamRaw.getInputStream()
            val uOut = upstreamRaw.getOutputStream()

            relayHttp11(clientIn, clientOut, uIn, uOut, targetHost, remotePort, isTls = false, ownership = ownership)
        } catch (e: Exception) {
            android.util.Log.e("TrafficEngine", "Plaintext handling failed for $targetHost:$remotePort: ${e.message}")
        } finally {
            if (upstreamRaw != null) {
                activeSockets.remove(upstreamRaw)
                try { upstreamRaw.close() } catch (_: Exception) {}
            }
        }
    }

    private fun handleTlsClient(
        clientSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        remoteIpBytes: ByteArray,
        remotePort: Int,
        targetHost: String,
        sniHost: String?,
        ownership: com.nadeem.apkscope.core.network.traffic.OwnershipVerification
    ) {
        val isTarget = HttpsInspectionConfig.isEnabled && HttpsInspectionConfig.isTargetDestination(targetHost)
        android.util.Log.i("TrafficEngine", "TLS_ROUTE_DECISION host=$targetHost isEnabled=${HttpsInspectionConfig.isEnabled} isTarget=$isTarget -> ${if (isTarget) "DECRYPT" else "RAW_TUNNEL"}")
        if (!isTarget) {
            tunnelRawUpstream(clientIn, clientOut, remoteIpBytes, remotePort, targetHost, ownership)
            return
        }

        val startTime = System.currentTimeMillis()
        var upstreamRawSocket: Socket? = null
        var upstreamSsl: SSLSocket? = null
        var clientSsl: SSLSocket? = null

        try {
            upstreamRawSocket = createProtectedUpstreamSocket()
            activeSockets.add(upstreamRawSocket)

            upstreamRawSocket.connect(InetSocketAddress(InetAddress.getByAddress(remoteIpBytes), remotePort), 10000)
            upstreamRawSocket.soTimeout = 15000

            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSock = sslFactory.createSocket(upstreamRawSocket, targetHost, remotePort, true) as SSLSocket
            try {
                sslSock.sslParameters = sslSock.sslParameters.apply {
                    applicationProtocols = arrayOf("h2", "http/1.1")
                }
            } catch (_: Exception) {}

            android.util.Log.i("TrafficEngine", "UPSTREAM_CONNECT_ATTEMPT host=$targetHost port=$remotePort")
            sslSock.startHandshake()
            android.util.Log.i("TrafficEngine", "UPSTREAM_TLS_HANDSHAKE_OK host=$targetHost cipherSuite=${sslSock.session.cipherSuite} protocol=${sslSock.session.protocol}")

            // Hostname verification
            val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
            if (!verifier.verify(targetHost, sslSock.session)) {
                throw javax.net.ssl.SSLPeerUnverifiedException("Upstream hostname mismatch for $targetHost")
            }
            upstreamSsl = sslSock
            val upstreamProto = try { sslSock.applicationProtocol } catch (_: Exception) { null }
            android.util.Log.i("TrafficEngine", "UPSTREAM_ALPN host=$targetHost negotiated=${upstreamProto ?: "(none)"}")

            // Downstream TLS Connection directly using native clientSocket
            val serverContext = caManager.getOrCreateServerSslContext(targetHost)
            val cSsl = serverContext.socketFactory.createSocket(clientSocket, null, clientSocket.port, false) as SSLSocket
            cSsl.useClientMode = false
            try {
                cSsl.sslParameters = cSsl.sslParameters.apply {
                    applicationProtocols = if (upstreamProto == "h2") {
                        arrayOf("h2", "http/1.1")
                    } else {
                        arrayOf("http/1.1")
                    }
                }
            } catch (_: Exception) {}

            android.util.Log.i("TrafficEngine", "DOWNSTREAM_HANDSHAKE_ATTEMPT host=$targetHost offeredAlpn=${cSsl.sslParameters.applicationProtocols?.joinToString(",")}")
            try {
                cSsl.startHandshake()
                android.util.Log.i("TrafficEngine", "DOWNSTREAM_TLS_HANDSHAKE_OK host=$targetHost cipherSuite=${cSsl.session.cipherSuite}")
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                val reason = "Target app rejected inspection certificate: ${e.javaClass.simpleName}: ${e.message}"
                android.util.Log.w("TrafficEngine", "DOWNSTREAM_TLS_HANDSHAKE_FAILED host=$targetHost reason=$reason durationMs=$duration")
                HttpsInspectionStore.record(
                    HttpsTransaction(
                        method = "CONNECT",
                        url = "https://$targetHost:$remotePort",
                        host = targetHost,
                        port = remotePort,
                        durationMs = duration,
                        state = HttpsCaptureState.TLS_HANDSHAKE_FAILED,
                        failureDetails = reason
                    ),
                    sessionId = sessionId,
                    targetPackage = targetPackage,
                    ownership = ownership
                )
                return
            }
            clientSsl = cSsl
            val downstreamProto = try { cSsl.applicationProtocol } catch (_: Exception) { null }
            android.util.Log.i("TrafficEngine", "DOWNSTREAM_ALPN host=$targetHost negotiated=${downstreamProto ?: "(none)"} upstreamProto=${upstreamProto ?: "(none)"} relayPath=${if (downstreamProto == "h2" && upstreamProto == "h2") "HTTP2_RELAY" else "HTTP11_RELAY"}")

            if (downstreamProto == "h2" && upstreamProto == "h2") {
                Http2RelayHandler(
                    downstreamIn = clientSsl.inputStream,
                    downstreamOut = clientSsl.outputStream,
                    upstreamIn = upstreamSsl.inputStream,
                    upstreamOut = upstreamSsl.outputStream,
                    host = targetHost,
                    port = remotePort,
                    isTls = true,
                    sessionId = sessionId,
                    targetPackage = targetPackage,
                    ownership = ownership
                ).relay()
            } else {
                if (upstreamProto == "h2") {
                    // The upstream offer is unconditional (we don't know the intercepted app's own
                    // ALPN capability until after its handshake completes), so a real h2-capable
                    // server negotiates h2 with us regardless of what the app can actually speak.
                    // relayHttp11 sends plain HTTP/1.1 text framing; feeding that into a connection
                    // the upstream server now treats as binary HTTP/2 breaks silently (wrong/absent
                    // body, missing 101 upgrade, etc. depending on what was requested) — not merely
                    // suboptimal. Re-establish the upstream leg with h2 excluded from its ALPN offer
                    // so both legs genuinely speak HTTP/1.1, which is what relayHttp11 requires.
                    //
                    // The inverse mismatch (downstream negotiates h2 but the real upstream server
                    // only supports HTTP/1.1) is not handled here — it would need a full HTTP/2-to-
                    // HTTP/1.1 translating gateway, not just a reconnect, since a genuine h2 client
                    // cannot simply be asked to speak HTTP/1.1 instead. Left as a known limitation;
                    // rare in practice; not exercised by any current test.
                    try { upstreamSsl.close() } catch (_: Exception) {}
                    activeSockets.remove(upstreamRawSocket)
                    try { upstreamRawSocket.close() } catch (_: Exception) {}

                    val fallbackRawSocket = createProtectedUpstreamSocket()
                    upstreamRawSocket = fallbackRawSocket
                    activeSockets.add(fallbackRawSocket)
                    fallbackRawSocket.connect(InetSocketAddress(InetAddress.getByAddress(remoteIpBytes), remotePort), 10000)
                    fallbackRawSocket.soTimeout = 15000

                    val fallbackSsl = sslFactory.createSocket(fallbackRawSocket, targetHost, remotePort, true) as SSLSocket
                    try {
                        fallbackSsl.sslParameters = fallbackSsl.sslParameters.apply {
                            applicationProtocols = arrayOf("http/1.1")
                        }
                    } catch (_: Exception) {}
                    fallbackSsl.startHandshake()
                    if (!verifier.verify(targetHost, fallbackSsl.session)) {
                        throw javax.net.ssl.SSLPeerUnverifiedException("Upstream hostname mismatch for $targetHost")
                    }
                    upstreamSsl = fallbackSsl
                }
                relayHttp11(clientSsl.inputStream, clientSsl.outputStream, upstreamSsl.inputStream, upstreamSsl.outputStream, targetHost, remotePort, isTls = true, ownership = ownership)
            }

        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            val state = if (e is SSLHandshakeException || e is javax.net.ssl.SSLException) {
                HttpsCaptureState.TLS_HANDSHAKE_FAILED
            } else {
                HttpsCaptureState.ENCRYPTED
            }
            android.util.Log.w("TrafficEngine", "TLS_CLIENT_STAGE_EXCEPTION host=$targetHost durationMs=$duration exception=${e.javaClass.simpleName}: ${e.message}")
            HttpsInspectionStore.record(
                HttpsTransaction(
                    method = "CONNECT",
                    url = "https://$targetHost:$remotePort",
                    host = targetHost,
                    port = remotePort,
                    durationMs = duration,
                    state = state,
                    failureDetails = "${e.javaClass.simpleName}: ${e.message}"
                ),
                sessionId = sessionId,
                targetPackage = targetPackage,
                ownership = ownership
            )
        } finally {
            try { clientSsl?.close() } catch (_: Exception) {}
            try { upstreamSsl?.close() } catch (_: Exception) {}
            if (upstreamRawSocket != null) {
                activeSockets.remove(upstreamRawSocket)
                try { upstreamRawSocket.close() } catch (_: Exception) {}
            }
        }
    }

    private fun tunnelRawUpstream(
        clientIn: InputStream, clientOut: OutputStream, remoteIp: ByteArray, remotePort: Int, host: String,
        ownership: com.nadeem.apkscope.core.network.traffic.OwnershipVerification = com.nadeem.apkscope.core.network.traffic.OwnershipVerification.UNVERIFIED,
    ) {
        val upstream = try {
            createProtectedUpstreamSocket()
        } catch (e: Exception) {
            // Socket creation itself (protect() failure) happens before the try block below starts —
            // without this, that specific failure would propagate uncaught out of this function instead
            // of being recorded like every other failure path here.
            HttpsInspectionStore.record(
                HttpsTransaction(
                    method = "TCP_PASSTHROUGH",
                    url = "https://$host:$remotePort",
                    host = host,
                    port = remotePort,
                    state = HttpsCaptureState.ENCRYPTED,
                    failureDetails = "Passthrough failed: ${e.javaClass.simpleName}: ${e.message}"
                ),
                sessionId = sessionId,
                targetPackage = targetPackage,
                ownership = ownership
            )
            return
        }
        activeSockets.add(upstream)
        try {
            upstream.connect(InetSocketAddress(InetAddress.getByAddress(remoteIp), remotePort), 10000)
            upstream.soTimeout = 30000

            val upstreamIn = upstream.getInputStream()
            val upstreamOut = upstream.getOutputStream()

            HttpsInspectionStore.record(
                HttpsTransaction(
                    method = "TCP_PASSTHROUGH",
                    url = "https://$host:$remotePort",
                    host = host,
                    port = remotePort,
                    state = HttpsCaptureState.ENCRYPTED,
                    failureDetails = "Non-target destination passed through encrypted"
                ),
                sessionId = sessionId,
                targetPackage = targetPackage,
                ownership = ownership
            )

            val t1 = Thread({
                try {
                    clientIn.copyTo(upstreamOut)
                    upstream.shutdownOutput()
                } catch (_: Exception) {}
                finally {
                    try { upstream.close() } catch (_: Exception) {}
                }
            }, "tunnel-c2u")
            val t2 = Thread({
                try { upstreamIn.copyTo(clientOut) } catch (_: Exception) {}
            }, "tunnel-u2c")

            t1.start(); t2.start()
            t1.join(5000); t2.join(5000)

        } catch (e: Exception) {
            HttpsInspectionStore.record(
                HttpsTransaction(
                    method = "TCP_PASSTHROUGH",
                    url = "https://$host:$remotePort",
                    host = host,
                    port = remotePort,
                    state = HttpsCaptureState.ENCRYPTED,
                    failureDetails = "Passthrough failed: ${e.message}"
                ),
                sessionId = sessionId,
                targetPackage = targetPackage,
                ownership = ownership
            )
        } finally {
            activeSockets.remove(upstream)
            try { upstream.close() } catch (_: Exception) {}
        }
    }

    private fun relayHttp11(
        clientIn: InputStream,
        clientOut: OutputStream,
        upstreamIn: InputStream,
        upstreamOut: OutputStream,
        host: String,
        port: Int,
        isTls: Boolean,
        ownership: com.nadeem.apkscope.core.network.traffic.OwnershipVerification = com.nadeem.apkscope.core.network.traffic.OwnershipVerification.UNVERIFIED
    ) {
        val cIn = BufferedInputStream(clientIn)
        val uIn = BufferedInputStream(upstreamIn)
        val validMethods = setOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH", "CONNECT", "TRACE")
        android.util.Log.i("TrafficEngine", "HTTP11_RELAY_ENTERED host=$host port=$port isTls=$isTls")

        while (true) {
            val start = System.currentTimeMillis()

            // Read Request Line
            val reqLine = readLineOrNull(cIn) ?: break
            if (reqLine.isBlank()) continue
            val parts = reqLine.split(" ")
            if (parts.size < 2 || !validMethods.contains(parts[0].uppercase())) {
                // Non-HTTP plaintext traffic: forward faithfully without corruption
                upstreamOut.write(reqLine.toByteArray(Charsets.ISO_8859_1))
                upstreamOut.write("\r\n".toByteArray(Charsets.ISO_8859_1))
                upstreamOut.flush()
                val proto = if (isTls) TrafficProtocol.HTTPS else TrafficProtocol.HTTP
                TrafficInspectionStore.record(
                    TrafficRecord(
                        sessionId = sessionId,
                        targetPackage = targetPackage,
                        observedOwnerUid = ownership.observedOwnerUid,
                        ownershipStatus = ownership.status,
                        ownershipFailureReason = ownership.failureReason,
                        protocol = proto,
                        host = host,
                        port = port,
                        url = "${if (isTls) "https" else "http"}://$host:$port/",
                        method = "RAW_TCP",
                        state = TrafficCaptureState.UNSUPPORTED_PROTOCOL,
                        failureDetails = "Non-HTTP/1.1 framing observed"
                    )
                )
                tunnelStreams(cIn, clientOut, uIn, upstreamOut)
                return
            }

            val method = parts[0]
            val path = parts[1]

            // Read Request Headers
            // Case-insensitive: HTTP header names are case-insensitive per RFC 7230 §3.2, and real
            // servers/clients vary casing (lowercase is common, especially from HTTP/2-originated
            // backends) — a plain HashMap here previously made every lookup below (Content-Type,
            // Transfer-Encoding, Connection, Upgrade, ...) silently miss against non-canonically
            // cased headers, e.g. wrongly skipping SSE detection and falling into the buffered
            // whole-body path, which blocks forever on a live stream that never sends a final chunk.
            val reqHeaders = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
            val reqRawHeaders = StringBuilder()
            reqRawHeaders.append(reqLine).append("\r\n")

            var reqHeaderCount = 0
            while (reqHeaderCount < 100) {
                val line = readLineOrNull(cIn) ?: break
                if (line.isEmpty()) break
                reqHeaderCount++
                reqRawHeaders.append(line).append("\r\n")
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val name = line.substring(0, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    reqHeaders[name] = value
                }
            }
            reqRawHeaders.append("\r\n")

            val isWsUpgrade = reqHeaders["Upgrade"]?.equals("websocket", ignoreCase = true) == true

            // Read Request Body
            val reqContentLength = reqHeaders["Content-Length"]?.toIntOrNull() ?: 0
            val isReqChunked = reqHeaders["Transfer-Encoding"]?.equals("chunked", ignoreCase = true) == true
            val reqBodyBytes = readBody(cIn, reqContentLength, isReqChunked)

            // Write Request to Upstream
            android.util.Log.i("TrafficEngine", "HTTP11_REQUEST_FORWARDED host=$host method=$method path=$path bodyBytes=${reqBodyBytes.size}")
            upstreamOut.write(reqRawHeaders.toString().toByteArray(Charsets.ISO_8859_1))
            if (reqBodyBytes.isNotEmpty()) {
                upstreamOut.write(reqBodyBytes)
            }
            upstreamOut.flush()

            // Read Response Status Line
            var respLine = readLineOrNull(uIn) ?: break
            android.util.Log.i("TrafficEngine", "HTTP11_RESPONSE_LINE_RECEIVED host=$host line=$respLine")
            var respParts = respLine.split(" ")
            var statusCode = if (respParts.size > 1) respParts[1].toIntOrNull() else 200

            // Handle interim 100 Continue
            if (statusCode == 100) {
                val interimRaw = StringBuilder().append(respLine).append("\r\n")
                while (true) {
                    val l = readLineOrNull(uIn) ?: break
                    interimRaw.append(l).append("\r\n")
                    if (l.isEmpty()) break
                }
                clientOut.write(interimRaw.toString().toByteArray(Charsets.ISO_8859_1))
                clientOut.flush()

                // Now read actual response line
                respLine = readLineOrNull(uIn) ?: break
                respParts = respLine.split(" ")
                statusCode = if (respParts.size > 1) respParts[1].toIntOrNull() else 200
            }

            val statusMessage = if (respParts.size > 2) respParts.subList(2, respParts.size).joinToString(" ") else "OK"

            // Read Response Headers (case-insensitive — see reqHeaders above)
            val respHeaders = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
            val respRawHeaders = StringBuilder()
            respRawHeaders.append(respLine).append("\r\n")

            var respHeaderCount = 0
            while (respHeaderCount < 100) {
                val line = readLineOrNull(uIn) ?: break
                if (line.isEmpty()) break
                respHeaderCount++
                respRawHeaders.append(line).append("\r\n")
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val name = line.substring(0, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    respHeaders[name] = value
                }
            }
            respRawHeaders.append("\r\n")

            val scheme = if (isTls) "https" else "http"
            val wsScheme = if (isTls) "wss" else "ws"
            val fullUrl = if (path.startsWith("http")) path else "$scheme://$host$path"

            // Check if this is a successful WebSocket Upgrade (101 Switching Protocols)
            if (statusCode == 101 && isWsUpgrade) {
                clientOut.write(respRawHeaders.toString().toByteArray(Charsets.ISO_8859_1))
                clientOut.flush()

                val wsUrl = "$wsScheme://$host$path"
                val txId = java.util.UUID.randomUUID().toString()
                val wsProto = if (isTls) TrafficProtocol.WSS else TrafficProtocol.WS

                val initialWsRecord = TrafficRecord(
                    id = txId,
                    sessionId = sessionId,
                    targetPackage = targetPackage,
                    observedOwnerUid = ownership.observedOwnerUid,
                    ownershipStatus = ownership.status,
                    ownershipFailureReason = ownership.failureReason,
                    protocol = wsProto,
                    host = host,
                    port = port,
                    url = wsUrl,
                    method = "UPGRADE",
                    statusCode = 101,
                    statusMessage = "Switching Protocols",
                    contentType = respHeaders["Content-Type"] ?: "websocket",
                    requestHeaders = reqHeaders,
                    responseHeaders = respHeaders,
                    durationMs = System.currentTimeMillis() - start,
                    state = TrafficCaptureState.DECODED,
                    webSocketSession = WebSocketSessionData(openedAt = Instant.now())
                )
                TrafficInspectionStore.record(initialWsRecord)

                // Hand over connection to WebSocket bidirectional relay
                relayWebSocket(cIn, clientOut, uIn, upstreamOut, host, port, isTls, wsUrl, txId)
                break
            }

            // Check if this is a Server-Sent Events (SSE) stream
            val isSse = respHeaders["Content-Type"]?.contains("text/event-stream", ignoreCase = true) == true
            if (isSse) {
                // Forward response headers immediately so client starts receiving events
                clientOut.write(respRawHeaders.toString().toByteArray(Charsets.ISO_8859_1))
                clientOut.flush()

                val sseEvents = mutableListOf<SseEvent>()
                val sseSession = SseSessionData(events = emptyList(), isLive = true)
                val txId = java.util.UUID.randomUUID().toString()
                val proto = if (isTls) TrafficProtocol.HTTPS else TrafficProtocol.HTTP

                var currentRecord = TrafficRecord(
                    id = txId,
                    sessionId = sessionId,
                    targetPackage = targetPackage,
                    observedOwnerUid = ownership.observedOwnerUid,
                    ownershipStatus = ownership.status,
                    ownershipFailureReason = ownership.failureReason,
                    protocol = proto,
                    host = host,
                    port = port,
                    url = fullUrl,
                    method = method,
                    statusCode = statusCode,
                    statusMessage = statusMessage,
                    contentType = respHeaders["Content-Type"] ?: "text/event-stream",
                    requestHeaders = reqHeaders,
                    responseHeaders = respHeaders,
                    requestBody = if (reqBodyBytes.isNotEmpty()) String(reqBodyBytes, Charsets.UTF_8) else null,
                    requestBodyBytes = reqBodyBytes.size.toLong(),
                    responseBody = "",
                    responseBodyBytes = 0L,
                    durationMs = System.currentTimeMillis() - start,
                    state = TrafficCaptureState.DECODED,
                    sseSession = sseSession
                )
                TrafficInspectionStore.record(currentRecord)

                val sseParser = SseEventParser { event ->
                    synchronized(sseEvents) {
                        sseEvents.add(event)
                    }
                    val snapshot = synchronized(sseEvents) { sseEvents.toList() }
                    currentRecord = currentRecord.copy(
                        sseSession = sseSession.copy(
                            events = snapshot,
                            isLive = true,
                            lastEventId = snapshot.lastOrNull { it.id != null }?.id
                        ),
                        responseBody = snapshot.joinToString("\n\n") { it.data },
                        durationMs = System.currentTimeMillis() - start
                    )
                    TrafficInspectionStore.record(currentRecord)
                }

                val isRespChunked = respHeaders["Transfer-Encoding"]?.equals("chunked", ignoreCase = true) == true
                relaySseStream(uIn, clientOut, isRespChunked, sseParser) { bytesRelayed ->
                    currentRecord = currentRecord.copy(
                        responseBodyBytes = currentRecord.responseBodyBytes + bytesRelayed,
                        durationMs = System.currentTimeMillis() - start
                    )
                    TrafficInspectionStore.record(currentRecord)
                }

                sseParser.finish()
                val finalEvents = synchronized(sseEvents) { sseEvents.toList() }
                currentRecord = currentRecord.copy(
                    sseSession = sseSession.copy(
                        events = finalEvents,
                        isLive = false,
                        lastEventId = finalEvents.lastOrNull { it.id != null }?.id
                    ),
                    responseBody = finalEvents.joinToString("\n\n") { it.data },
                    durationMs = System.currentTimeMillis() - start
                )
                TrafficInspectionStore.record(currentRecord)
                break
            }

            // Regular HTTP Response Body Handling
            val isNoBodyResponse = method.equals("HEAD", ignoreCase = true) ||
                    statusCode == 204 || statusCode == 304 || (statusCode in 100..199)
            val respContentLength = if (isNoBodyResponse) 0 else respHeaders["Content-Length"]?.toIntOrNull() ?: -1
            val isRespChunked = !isNoBodyResponse && respHeaders["Transfer-Encoding"]?.equals("chunked", ignoreCase = true) == true
            val isClose = respHeaders["Connection"]?.equals("close", ignoreCase = true) == true
            val respBodyBytes = if (isNoBodyResponse) ByteArray(0) else {
                readBody(uIn, respContentLength, isRespChunked, readUntilEof = isClose && respContentLength < 0)
            }

            // Write Response to Client
            clientOut.write(respRawHeaders.toString().toByteArray(Charsets.ISO_8859_1))
            if (respBodyBytes.isNotEmpty()) {
                clientOut.write(respBodyBytes)
            }
            clientOut.flush()

            val duration = System.currentTimeMillis() - start
            val reqBodyStr = if (reqBodyBytes.isNotEmpty()) String(reqBodyBytes, Charsets.UTF_8) else null
            val respPayload = if (isRespChunked) unchunk(respBodyBytes) else respBodyBytes
            val respBodyStr = decodeResponseBodyPreview(respPayload, respHeaders["Content-Encoding"])

            val txId = java.util.UUID.randomUUID().toString()
            val proto = if (isTls) TrafficProtocol.HTTPS else TrafficProtocol.HTTP
            android.util.Log.i("TrafficEngine", "CAPTURE_STORE_INSERT txId=$txId host=$host method=$method statusCode=$statusCode protocol=$proto reqBodyBytes=${reqBodyBytes.size} respBodyBytes=${respBodyBytes.size}")

            // Record in unified TrafficInspectionStore
            TrafficInspectionStore.record(
                TrafficRecord(
                    id = txId,
                    sessionId = sessionId,
                    targetPackage = targetPackage,
                    observedOwnerUid = ownership.observedOwnerUid,
                    ownershipStatus = ownership.status,
                    ownershipFailureReason = ownership.failureReason,
                    protocol = proto,
                    host = host,
                    port = port,
                    url = fullUrl,
                    method = method,
                    statusCode = statusCode,
                    statusMessage = statusMessage,
                    contentType = respHeaders["Content-Type"],
                    requestHeaders = reqHeaders,
                    responseHeaders = respHeaders,
                    requestBody = reqBodyStr,
                    responseBody = respBodyStr,
                    requestBodyBytes = reqBodyBytes.size.toLong(),
                    responseBodyBytes = respBodyBytes.size.toLong(),
                    durationMs = duration,
                    state = TrafficCaptureState.DECODED,
                    isTruncated = false
                )
            )

            // Maintain backwards compatibility with HttpsTransaction
            HttpsInspectionStore.record(
                HttpsTransaction(
                    id = txId,
                    method = method,
                    url = fullUrl,
                    host = host,
                    port = port,
                    statusCode = statusCode ?: 200,
                    statusMessage = statusMessage,
                    requestHeaders = reqHeaders,
                    responseHeaders = respHeaders,
                    requestBody = reqBodyStr,
                    responseBody = respBodyStr,
                    requestBodyBytes = reqBodyBytes.size,
                    responseBodyBytes = respBodyBytes.size,
                    durationMs = duration,
                    state = HttpsCaptureState.DECODED,
                    isTruncated = false
                ),
                sessionId = sessionId,
                targetPackage = targetPackage,
                ownership = ownership
            )

            val clientClose = reqHeaders["Connection"]?.equals("close", ignoreCase = true) == true
            if (clientClose || isClose) break
        }
    }

    private fun relayWebSocket(
        cIn: InputStream,
        cOut: OutputStream,
        uIn: InputStream,
        uOut: OutputStream,
        host: String,
        port: Int,
        isTls: Boolean,
        url: String,
        txId: String
    ) {
        val seqCounter = AtomicInteger(0)
        val clientReconstructor = WebSocketFrameParser.BoundedMessageReconstructor()
        val upstreamReconstructor = WebSocketFrameParser.BoundedMessageReconstructor()

        // Thread 1: Client to Upstream (Outbound)
        val t1 = Thread({
            try {
                while (true) {
                    val frame = WebSocketFrameParser.readFrame(cIn) ?: break

                    // 1. Transparent forwarding first (never alter traffic delivered to either peer)
                    val wire = WebSocketFrameParser.serializeFrame(frame)
                    try {
                        uOut.write(wire)
                        uOut.flush()
                    } catch (_: Exception) {
                    }

                    // 2. Feed into bounded reconstructor
                    val reconstructed = clientReconstructor.processFrame(frame)
                    if (reconstructed != null) {
                        val seq = seqCounter.incrementAndGet()
                        val (closeCode, closeReason) = if (reconstructed.type == MessageType.CLOSE) {
                            WebSocketFrameParser.parseClosePayload(reconstructed.payload)
                        } else null to null

                        val finalPayloadBytes = if (reconstructed.isDeflated) {
                            try {
                                WebSocketFrameParser.inflatePermessageDeflate(reconstructed.payload)
                            } catch (_: Exception) {
                                reconstructed.payload
                            }
                        } else {
                            reconstructed.payload
                        }

                        val preview = when (reconstructed.type) {
                            MessageType.TEXT -> {
                                val str = String(finalPayloadBytes, Charsets.UTF_8)
                                val tag = if (reconstructed.isFragmented) "[${reconstructed.fragmentCount} fragments] " else ""
                                val deflTag = if (reconstructed.isDeflated) "[deflated] " else ""
                                val base = "$tag$deflTag$str"
                                if (base.length > 2000) base.take(2000) + "... [truncated]" else base
                            }
                            MessageType.BINARY -> {
                                val tag = if (reconstructed.isFragmented) "[${reconstructed.fragmentCount} fragments] " else ""
                                "$tag[Binary ${finalPayloadBytes.size} bytes]"
                            }
                            MessageType.PING -> "Ping: ${String(finalPayloadBytes, Charsets.UTF_8)}"
                            MessageType.PONG -> "Pong: ${String(finalPayloadBytes, Charsets.UTF_8)}"
                            MessageType.CLOSE -> "Close code=$closeCode reason=$closeReason"
                        }

                        val msg = WebSocketMessage(
                            sequence = seq,
                            direction = Direction.OUTBOUND,
                            type = reconstructed.type,
                            timestamp = Instant.now(),
                            payloadLength = finalPayloadBytes.size.toLong(),
                            payloadPreview = preview,
                            isMasked = frame.isMasked,
                            isDeflated = reconstructed.isDeflated,
                            closeCode = closeCode,
                            closeReason = closeReason,
                            isFragmented = reconstructed.isFragmented,
                            isReconstructed = reconstructed.isFragmented,
                            fragmentCount = reconstructed.fragmentCount
                        )

                        TrafficInspectionStore.updateRecord(txId) { current ->
                            current.copy(
                                webSocketSession = current.webSocketSession?.withMessage(msg)
                            )
                        }
                    }

                    if (frame.opcode == WebSocketFrameParser.OPCODE_CLOSE) break
                }
            } catch (_: Exception) {
            }
        }, "ws-c2u")

        // Thread 2: Upstream to Client (Inbound)
        val t2 = Thread({
            try {
                while (true) {
                    val frame = WebSocketFrameParser.readFrame(uIn) ?: break

                    // 1. Transparent forwarding first (never alter traffic delivered to either peer)
                    val wire = WebSocketFrameParser.serializeFrame(frame)
                    try {
                        cOut.write(wire)
                        cOut.flush()
                    } catch (_: Exception) {
                    }

                    // 2. Feed into bounded reconstructor
                    val reconstructed = upstreamReconstructor.processFrame(frame)
                    if (reconstructed != null) {
                        val seq = seqCounter.incrementAndGet()
                        val (closeCode, closeReason) = if (reconstructed.type == MessageType.CLOSE) {
                            WebSocketFrameParser.parseClosePayload(reconstructed.payload)
                        } else null to null

                        val finalPayloadBytes = if (reconstructed.isDeflated) {
                            try {
                                WebSocketFrameParser.inflatePermessageDeflate(reconstructed.payload)
                            } catch (_: Exception) {
                                reconstructed.payload
                            }
                        } else {
                            reconstructed.payload
                        }

                        val preview = when (reconstructed.type) {
                            MessageType.TEXT -> {
                                val str = String(finalPayloadBytes, Charsets.UTF_8)
                                val tag = if (reconstructed.isFragmented) "[${reconstructed.fragmentCount} fragments] " else ""
                                val deflTag = if (reconstructed.isDeflated) "[deflated] " else ""
                                val base = "$tag$deflTag$str"
                                if (base.length > 2000) base.take(2000) + "... [truncated]" else base
                            }
                            MessageType.BINARY -> {
                                val tag = if (reconstructed.isFragmented) "[${reconstructed.fragmentCount} fragments] " else ""
                                "$tag[Binary ${finalPayloadBytes.size} bytes]"
                            }
                            MessageType.PING -> "Ping: ${String(finalPayloadBytes, Charsets.UTF_8)}"
                            MessageType.PONG -> "Pong: ${String(finalPayloadBytes, Charsets.UTF_8)}"
                            MessageType.CLOSE -> "Close code=$closeCode reason=$closeReason"
                        }

                        val msg = WebSocketMessage(
                            sequence = seq,
                            direction = Direction.INBOUND,
                            type = reconstructed.type,
                            timestamp = Instant.now(),
                            payloadLength = finalPayloadBytes.size.toLong(),
                            payloadPreview = preview,
                            isMasked = frame.isMasked,
                            isDeflated = reconstructed.isDeflated,
                            closeCode = closeCode,
                            closeReason = closeReason,
                            isFragmented = reconstructed.isFragmented,
                            isReconstructed = reconstructed.isFragmented,
                            fragmentCount = reconstructed.fragmentCount
                        )

                        TrafficInspectionStore.updateRecord(txId) { current ->
                            current.copy(
                                webSocketSession = current.webSocketSession?.withMessage(msg)
                            )
                        }
                    }

                    if (frame.opcode == WebSocketFrameParser.OPCODE_CLOSE) break
                }
            } catch (_: Exception) {
            }
        }, "ws-u2c")

        t1.start()
        t2.start()
        try { t1.join(60000) } catch (_: Exception) {}
        try { t2.join(60000) } catch (_: Exception) {}

        TrafficInspectionStore.updateRecord(txId) { current ->
            current.copy(
                webSocketSession = current.webSocketSession?.copy(closedAt = Instant.now())
            )
        }
    }

    private fun tunnelStreams(cIn: InputStream, cOut: OutputStream, uIn: InputStream, uOut: OutputStream) {
        val t1 = Thread({
            try { cIn.copyTo(uOut); uOut.flush() } catch (_: Exception) {}
        }, "raw-c2u")
        val t2 = Thread({
            try { uIn.copyTo(cOut); cOut.flush() } catch (_: Exception) {}
        }, "raw-u2c")
        t1.start(); t2.start()
        try { t1.join(10000) } catch (_: Exception) {}
        try { t2.join(10000) } catch (_: Exception) {}
    }

    private fun unchunk(chunkedBytes: ByteArray): ByteArray {
        val inStream = chunkedBytes.inputStream()
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLineOrNull(inStream)?.trim() ?: break
            val chunkSize = try {
                sizeLine.split(";")[0].trim().toInt(16)
            } catch (_: Exception) {
                0
            }
            if (chunkSize <= 0) break
            val chunk = ByteArray(chunkSize)
            var read = 0
            while (read < chunkSize) {
                val n = inStream.read(chunk, read, chunkSize - read)
                if (n < 0) break
                read += n
            }
            out.write(chunk, 0, read)
            readLineOrNull(inStream) // trailing CRLF
        }
        return out.toByteArray()
    }

    private fun decodeResponseBodyPreview(body: ByteArray, encoding: String?): String? {
        if (body.isEmpty()) return null
        return try {
            if (encoding?.equals("gzip", ignoreCase = true) == true) {
                GZIPInputStream(body.inputStream()).use { gzip ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    var total = 0
                    while (total < TrafficInspectionStore.MAX_BODY_BYTES) {
                        val n = gzip.read(buf, 0, minOf(buf.size, TrafficInspectionStore.MAX_BODY_BYTES - total))
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        total += n
                    }
                    String(out.toByteArray(), Charsets.UTF_8)
                }
            } else if (encoding?.equals("deflate", ignoreCase = true) == true) {
                val inflater = Inflater()
                inflater.setInput(body)
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                var total = 0
                try {
                    while (!inflater.finished() && total < TrafficInspectionStore.MAX_BODY_BYTES) {
                        val count = inflater.inflate(buf)
                        if (count <= 0) break
                        out.write(buf, 0, count)
                        total += count
                    }
                } finally {
                    inflater.end()
                }
                String(out.toByteArray(), Charsets.UTF_8)
            } else {
                String(body, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            String(body, Charsets.UTF_8)
        }
    }

    private fun readLineOrNull(inStream: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inStream.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\r'.code) {
                val next = inStream.read()
                if (next == '\n'.code) return sb.toString()
                if (next >= 0) sb.append(next.toChar())
                return sb.toString()
            }
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun readBody(inStream: InputStream, contentLength: Int, chunked: Boolean, readUntilEof: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        if (chunked) {
            while (true) {
                val sizeLine = readLineOrNull(inStream)?.trim() ?: break
                val chunkSize = try {
                    sizeLine.split(";")[0].trim().toInt(16)
                } catch (_: Exception) {
                    0
                }
                out.write("$sizeLine\r\n".toByteArray(Charsets.ISO_8859_1))
                if (chunkSize <= 0) {
                    readLineOrNull(inStream)
                    out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
                    break
                }
                val chunk = ByteArray(chunkSize)
                var read = 0
                while (read < chunkSize) {
                    val n = inStream.read(chunk, read, chunkSize - read)
                    if (n < 0) break
                    read += n
                }
                out.write(chunk, 0, read)
                readLineOrNull(inStream)
                out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
            }
        } else if (contentLength > 0) {
            val buf = ByteArray(minOf(contentLength, 16384))
            var remaining = contentLength
            while (remaining > 0) {
                val n = inStream.read(buf, 0, minOf(buf.size, remaining))
                if (n < 0) break
                out.write(buf, 0, n)
                remaining -= n
            }
        } else if (readUntilEof) {
            val buf = ByteArray(4096)
            while (true) {
                val n = inStream.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun relaySseStream(
        inStream: InputStream,
        clientOut: OutputStream,
        chunked: Boolean,
        sseParser: SseEventParser,
        onProgress: (Int) -> Unit
    ) {
        if (chunked) {
            while (true) {
                val sizeLine = readLineOrNull(inStream)?.trim() ?: break
                clientOut.write("$sizeLine\r\n".toByteArray(Charsets.ISO_8859_1))
                clientOut.flush()

                val chunkSize = try {
                    sizeLine.split(";")[0].trim().toInt(16)
                } catch (_: Exception) {
                    0
                }
                if (chunkSize <= 0) {
                    readLineOrNull(inStream)
                    clientOut.write("\r\n".toByteArray(Charsets.ISO_8859_1))
                    clientOut.flush()
                    break
                }

                val chunk = ByteArray(chunkSize)
                var read = 0
                while (read < chunkSize) {
                    val n = inStream.read(chunk, read, chunkSize - read)
                    if (n < 0) break
                    read += n
                }
                clientOut.write(chunk, 0, read)
                clientOut.flush()
                sseParser.feed(chunk, 0, read)
                onProgress(read)

                readLineOrNull(inStream)
                clientOut.write("\r\n".toByteArray(Charsets.ISO_8859_1))
                clientOut.flush()
            }
        } else {
            val buf = ByteArray(4096)
            while (true) {
                val n = inStream.read(buf)
                if (n < 0) break
                clientOut.write(buf, 0, n)
                clientOut.flush()
                sseParser.feed(buf, 0, n)
                onProgress(n)
            }
        }
    }
}
