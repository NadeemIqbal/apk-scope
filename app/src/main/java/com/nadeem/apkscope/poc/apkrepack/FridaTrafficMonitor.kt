package com.nadeem.apkscope.poc.apkrepack

import android.content.Context
import android.util.Base64
import android.util.Log
import com.nadeem.apkscope.core.network.traffic.TrafficCaptureState
import com.nadeem.apkscope.core.network.traffic.LiveHttp2ConnectionDecoder
import com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore
import com.nadeem.apkscope.core.network.traffic.TrafficProtocol
import com.nadeem.apkscope.core.network.traffic.TrafficRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

/**
 * Listens for live HTTPS/WSS traffic captured by Frida script.
 * Protocol: JSON-lines over TCP from 127.0.0.1:9999
 *
 * Implements complete HTTP/1.x stream reassembly, automatic Gzip/Deflate decompression,
 * JSON formatting, and connection-aware request/response transaction correlation.
 */
class FridaTrafficMonitor(private val listenPort: Int = LISTEN_PORT) {

    private val TAG = "FridaTrafficMonitor"

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null

    data class CapturedTraffic(
        val pkg: String,
        val dir: String,
        val len: Int,
        val ts: Long,
        val data: String,
        val conn: String = "default",
        val source: String = "tls"
    ) {
        fun decodeData(): ByteArray = try {
            Base64.decode(data, Base64.DEFAULT)
        } catch (_: Exception) {
            ByteArray(0)
        }

        fun getDataAsString(): String = try {
            String(decodeData(), Charsets.UTF_8)
        } catch (e: Exception) {
            "[binary: ${data.take(50)}...]"
        }
    }

    data class Status(
        val isListening: Boolean = false,
        val connectedPackage: String? = null,
        val connectedPid: Int? = null,
        val targetPackage: String? = null,
        val commandReady: Boolean = false,
        val lastCommand: CommandResult? = null,
        val capturedCount: Int = 0,
        val lastError: String? = null,
        val port: Int = LISTEN_PORT
    )

    data class CommandResult(
        val id: String,
        val ok: Boolean,
        val result: String?,
        val error: String?,
        val source: String? = null,
        val targetPackage: String? = null,
        val sessionId: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
    )

    data class CommandDispatch(
        val accepted: Boolean,
        val id: String? = null,
        val error: String? = null,
    )

    private val _trafficFlow = MutableSharedFlow<CapturedTraffic>(
        replay = 100,
        extraBufferCapacity = 50
    )
    val trafficFlow: SharedFlow<CapturedTraffic> = _trafficFlow
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _commandResults = MutableSharedFlow<CommandResult>(replay = 20, extraBufferCapacity = 20)
    val commandResults: SharedFlow<CommandResult> = _commandResults

    private var serverSocket: ServerSocket? = null
    private var serverGeneration = 0L
    @Volatile
    private var isRunning = false
    @Volatile private var activeSessionId: String? = null
    @Volatile private var activeTargetPackage: String? = null
    @Volatile private var expectedChannelToken: String? = null
    private var activeClientSocket: Socket? = null
    private var activeClientWriter: BufferedWriter? = null
    private val clientLock = Any()
    private val pendingCommandSources = ConcurrentHashMap<String, String>()

    private val activeTransactions = ConcurrentHashMap<String, InFlightTransaction>()
    private val http2Connections = ConcurrentHashMap<String, LiveHttp2ConnectionDecoder>()

    private data class InFlightTransaction(
        val id: String,
        val connId: String,
        val pkg: String,
        val startTime: Long,
        var method: String,
        var url: String,
        var host: String,
        var port: Int = 443,
        val requestHeaders: MutableMap<String, String> = mutableMapOf(),
        val requestBodyBuffer: ByteArrayOutputStream = ByteArrayOutputStream(),
        var requestEncoding: String? = null,
        var statusCode: Int? = null,
        var statusMessage: String? = null,
        val responseHeaders: MutableMap<String, String> = mutableMapOf(),
        val responseBodyBuffer: ByteArrayOutputStream = ByteArrayOutputStream(),
        var responseEncoding: String? = null,
        var responseContentType: String? = null
    )

    fun start(sessionId: String? = null, targetPackage: String? = null) {
        startInternal(sessionId, targetPackage, null)
    }

    /** Start a Work Profile monitor bound to the token created for this repacked APK. */
    fun start(context: Context, sessionId: String, targetPackage: String) {
        startInternal(sessionId, targetPackage, FridaChannelConfig.readToken(context, sessionId))
    }

    private fun startInternal(sessionId: String?, targetPackage: String?, channelToken: String?): Unit = synchronized(clientLock) {
        val targetChanged = isRunning && (
            activeSessionId != sessionId ||
                activeTargetPackage != targetPackage ||
                expectedChannelToken != channelToken
            )
        // WorkEntryScreen and WorkLiveMonitorViewModel both start the shared monitor. If the
        // target is unchanged, this is an idempotent call. Do not clear commandReady here:
        // the target may have authenticated between the first start and this duplicate call.
        if (isRunning && !targetChanged) {
            Log.i(TAG, "Traffic monitor already active, session=$sessionId, target=$targetPackage")
            return
        }
        if (targetChanged) {
            runCatching { activeClientSocket?.close() }
            activeClientSocket = null
            activeClientWriter = null
            pendingCommandSources.clear()
        }
        activeSessionId = sessionId
        activeTargetPackage = targetPackage
        expectedChannelToken = channelToken
        _status.update { it.copy(
            targetPackage = targetPackage,
            connectedPackage = null,
            connectedPid = null,
            commandReady = false,
            lastCommand = null,
        ) }
        if (isRunning) {
            Log.i(TAG, "Traffic monitor already active, session=$sessionId, target=$targetPackage")
            return
        }

        isRunning = true
        val generation = ++serverGeneration
        serverJob = monitorScope.launch {
            runServer(generation)
        }
    }

    private suspend fun runServer(generation: Long) {
        withContext(Dispatchers.IO) {
            var listener: ServerSocket? = null
            try {
                synchronized(clientLock) {
                    if (!isRunning || generation != serverGeneration) return@withContext
                    listener = ServerSocket()
                    listener.also {
                        it.reuseAddress = true
                        it.bind(InetSocketAddress("127.0.0.1", listenPort))
                    }
                    serverSocket = listener
                    _status.update { it.copy(
                        isListening = true,
                        port = listener.localPort,
                        targetPackage = activeTargetPackage,
                        commandReady = false,
                        lastError = if (activeTargetPackage != null && expectedChannelToken == null) {
                            "Target channel token is unavailable for this session"
                        } else null,
                    ) }
                }
                val boundListener = checkNotNull(listener)
                Log.i(TAG, "Traffic monitor server listening on 127.0.0.1:${boundListener.localPort}")

                while (isRunning && isActive) {
                    try {
                        val clientSocket = boundListener.accept()
                        Log.i(TAG, "New client connected: ${clientSocket.remoteSocketAddress}")
                        monitorScope.launch {
                            handleClient(clientSocket, generation)
                        }
                    } catch (e: Exception) {
                        if (isRunning && isActive) {
                            Log.e(TAG, "Accept error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start monitor: ${e.message}", e)
                synchronized(clientLock) {
                    if (generation == serverGeneration) {
                        _status.update { it.copy(isListening = false, lastError = e.message) }
                    }
                }
            } finally {
                runCatching { listener?.close() }
                synchronized(clientLock) {
                    if (generation == serverGeneration) {
                        isRunning = false
                        serverSocket = null
                        _status.update { it.copy(isListening = false) }
                    }
                }
            }
        }
    }

    private suspend fun handleClient(clientSocket: Socket, generation: Long) {
        withContext(Dispatchers.IO) {
            var packageName = "unknown"
            var processId: Int? = null
            var accepted = false
            var boundTargetPackage: String? = null
            var boundSessionId: String? = null
            var boundChannelToken: String? = null

            try {
                clientSocket.use { socket ->
                    socket.soTimeout = 5_000 // unauthenticated clients must not occupy a reader forever
                    val reader = BoundedLineReader(socket.inputStream, FridaChannelConfig.MAX_COMMAND_CHARS)
                    val writer = socket.outputStream.bufferedWriter(Charsets.UTF_8)

                    // First line: target-originated, authenticated hello. There is no controller
                    // API that accepts a package name or PID, so a caller cannot retarget this
                    // endpoint to another installed package/process.
                    val helloLine = reader.readLine()
                    if (helloLine.isNullOrBlank() || helloLine.length > FridaChannelConfig.MAX_COMMAND_CHARS) {
                        sendProtocolError(writer, "", "missing or oversized target hello")
                        return@use
                    }
                    val hello = runCatching { JSONObject(helloLine) }.getOrNull()
                    if (hello == null || hello.optString("type") != "hello" ||
                        hello.optInt("version", -1) != FridaChannelConfig.PROTOCOL_VERSION
                    ) {
                        sendProtocolError(writer, "", "unsupported target hello")
                        return@use
                    }

                    packageName = hello.optString("pkg", "")
                    processId = hello.optInt("pid", -1).takeIf { it >= 0 }
                    val helloToken = hello.optString("token", "")
                    boundTargetPackage = activeTargetPackage
                    boundSessionId = activeSessionId
                    boundChannelToken = expectedChannelToken
                    if (!FridaCommandPolicy.acceptsHello(boundTargetPackage, expectedChannelToken, packageName, helloToken)) {
                        sendProtocolError(writer, "", "target package or channel token mismatch")
                        Log.w(TAG, "Rejected Frida client package=$packageName expected=$boundTargetPackage")
                        return@use
                    }

                    synchronized(clientLock) {
                        if (!isRunning || generation != serverGeneration || activeClientSocket != null ||
                            activeSessionId != boundSessionId || activeTargetPackage != boundTargetPackage ||
                            expectedChannelToken != boundChannelToken
                        ) return@synchronized
                        activeClientSocket = socket
                        activeClientWriter = writer
                        accepted = true
                        socket.soTimeout = 0
                        _status.update { it.copy(
                            connectedPackage = packageName,
                            connectedPid = processId,
                            targetPackage = boundTargetPackage,
                            commandReady = boundTargetPackage != null && boundChannelToken != null,
                            lastError = null,
                        ) }
                    }
                    if (!accepted) {
                        sendProtocolError(writer, "", "another target connection is already active")
                        return@use
                    }

                    Log.i(TAG, "Target client connected: $packageName pid=${processId ?: "unknown"}")

                    // Then: JSON-lines of traffic events
                    var line: String? = null
                    while (isRunning && reader.readLine().also { line = it } != null) {
                        if (activeSessionId != boundSessionId || activeTargetPackage != boundTargetPackage ||
                            expectedChannelToken != boundChannelToken
                        ) break
                        val currentLine = line
                        if (currentLine.isNullOrBlank() || currentLine.length > FridaChannelConfig.MAX_COMMAND_CHARS) continue

                        try {
                            val obj = JSONObject(currentLine)
                            when (obj.optString("type")) {
                                "command_result" -> {
                                    if (obj.optString("pkg", packageName) != packageName) continue
                                    val result = CommandResult(
                                        id = obj.optString("id", ""),
                                        ok = obj.optBoolean("ok", false),
                                        result = obj.optionalJsonString("result"),
                                        error = obj.optionalJsonString("error"),
                                        source = pendingCommandSources.remove(obj.optString("id", "")),
                                        targetPackage = packageName,
                                        sessionId = boundSessionId,
                                        timestamp = obj.optLong("ts", System.currentTimeMillis()),
                                    )
                                    synchronized(clientLock) {
                                        if (activeClientSocket === socket) {
                                            _status.update { it.copy(lastCommand = result, lastError = result.error) }
                                        }
                                    }
                                    _commandResults.emit(result)
                                    continue
                                }
                                "hello" -> continue
                            }

                            val eventPackage = obj.optString("pkg", packageName)
                            if (eventPackage != packageName ||
                                boundTargetPackage != null && eventPackage != boundTargetPackage
                            ) {
                                Log.w(TAG, "Ignoring event from unexpected package=$eventPackage expected=$packageName")
                                continue
                            }
                            val traffic = CapturedTraffic(
                                pkg = eventPackage,
                                dir = obj.optString("dir", "in"),
                                len = obj.optInt("len", 0),
                                ts = obj.optLong("ts", System.currentTimeMillis()),
                                data = obj.optString("data", ""),
                                conn = obj.optString("conn", "default"),
                                source = obj.optString("source", "tls")
                            )

                            _trafficFlow.emit(traffic)
                            processTrafficChunk(traffic, boundSessionId, boundTargetPackage)
                            _status.update { it.copy(capturedCount = it.capturedCount + 1) }
                            Log.i(TAG, "Traffic chunk: ${traffic.pkg} ${traffic.dir} ${traffic.len}b conn=${traffic.conn}")

                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse event: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Client socket error ($packageName): ${e.message}")
            } finally {
                Log.i(TAG, "Client disconnected: $packageName")
                synchronized(clientLock) {
                    if (activeClientSocket === clientSocket) {
                        activeClientSocket = null
                        activeClientWriter = null
                        _status.update { it.copy(
                            connectedPackage = null,
                            connectedPid = null,
                            commandReady = false,
                        ) }
                    }
                }
                try {
                    clientSocket.close()
                } catch (_: Exception) {}
            }
        }
    }

    private fun sendProtocolError(writer: BufferedWriter, id: String, message: String) {
        runCatching {
            writer.write(
                JSONObject()
                    .put("type", "command_result")
                    .put("version", FridaChannelConfig.PROTOCOL_VERSION)
                    .put("id", id)
                    .put("ok", false)
                    .put("error", message)
                    .toString()
            )
            writer.newLine()
            writer.flush()
        }
    }

    /** Prevent an untrusted local client from forcing an unbounded line allocation. */
    private class BoundedLineReader(input: InputStream, private val maxChars: Int) {
        private val bufferedReader = input.reader(Charsets.UTF_8).buffered()

        fun readLine(): String? {
            val line = StringBuilder()
            while (true) {
                val next = bufferedReader.read()
                if (next == -1) return if (line.isEmpty()) null else line.toString()
                if (next == '\n'.code) return line.toString()
                if (next == '\r'.code) continue
                if (line.length >= maxChars) throw IOException("protocol line exceeds $maxChars characters")
                line.append(next.toChar())
            }
        }
    }

    fun stop(): Unit = synchronized(clientLock) {
        isRunning = false
        serverGeneration++
        serverJob?.cancel()
        runCatching { activeClientSocket?.close() }
        activeClientSocket = null
        activeClientWriter = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket: ${e.message}")
        }
        serverSocket = null
        activeTransactions.clear()
        http2Connections.clear()
        activeSessionId = null
        activeTargetPackage = null
        expectedChannelToken = null
        pendingCommandSources.clear()
        _status.update { it.copy(
            isListening = false,
            connectedPackage = null,
            connectedPid = null,
            targetPackage = null,
            commandReady = false,
            lastCommand = null,
        ) }
        Log.i(TAG, "Traffic monitor stopped")
    }

    /** Send JavaScript for evaluation in the already-authenticated active target process. */
    fun sendScript(source: String): CommandDispatch = synchronized(clientLock) {
        if (source.isBlank()) return CommandDispatch(false, error = "Enter a script first")
        if (source.length > FridaChannelConfig.MAX_COMMAND_CHARS) {
            return CommandDispatch(false, error = "Script exceeds the 256 KiB limit")
        }

        val target = activeTargetPackage
        val token = expectedChannelToken
        if (target.isNullOrBlank() || token.isNullOrBlank()) {
            return CommandDispatch(false, error = "Dynamic commands require an active target-bound Work session")
        }

        val id = java.util.UUID.randomUUID().toString()
        if (!_status.value.commandReady ||
            !FridaCommandPolicy.acceptsCommandTarget(activeTargetPackage, target)
        ) {
            return CommandDispatch(false, error = "The target APK is not connected")
        }

        val commandJson = JSONObject()
            .put("type", "command")
            .put("version", FridaChannelConfig.PROTOCOL_VERSION)
            .put("id", id)
            .put("pkg", target)
            .put("op", "evaluate")
            .put("source", source)
            .toString()
        // MAX_COMMAND_CHARS applies to the complete JSON-lines frame, not just the script field.
        // Check the encoded envelope before writing so a boundary-sized script gets an explicit
        // dispatch error instead of being dropped by the receiver's bounded line reader.
        if (commandJson.length > FridaChannelConfig.MAX_COMMAND_CHARS) {
            return CommandDispatch(false, error = "Serialized command exceeds the 256 KiB limit")
        }

        val dispatchSocket = activeClientSocket
            ?: return CommandDispatch(false, error = "The target APK is not connected")
        val dispatchWriter = activeClientWriter
            ?: return CommandDispatch(false, error = "The target APK is not connected")
        if (pendingCommandSources.size >= 128) {
            return CommandDispatch(false, error = "Too many commands are waiting for the target. Wait for responses or restart the session.")
        }
        pendingCommandSources[id] = source
        val dispatchSessionId = activeSessionId
        monitorScope.launch {
            delay(COMMAND_RESPONSE_TIMEOUT_MS)
            // Keep the source after the soft timeout. Android may freeze the target while APK
            // Scope is in the foreground; when the target is resumed it can still deliver the
            // real result. The later command_result uses the same id and replaces this interim
            // timeout entry in the console transcript.
            val pendingSource = pendingCommandSources[id] ?: return@launch
            _commandResults.emit(
                CommandResult(
                    id = id,
                    ok = false,
                    result = null,
                    error = "The target has not responded yet. Keep the target app open and its result will appear here when Android resumes it.",
                    source = pendingSource,
                    targetPackage = target,
                    sessionId = dispatchSessionId,
                ),
            )
        }
        monitorScope.launch {
            try {
                // Serialize frames on this writer, not the lifecycle lock. A stalled target
                // must not prevent stop()/reconnect from closing its socket to unblock I/O.
                synchronized(dispatchWriter) {
                    synchronized(clientLock) {
                        if (activeClientSocket !== dispatchSocket || !_status.value.commandReady ||
                            activeSessionId != dispatchSessionId || activeTargetPackage != target || expectedChannelToken != token
                        ) {
                            throw IOException("target channel closed before command write")
                        }
                    }
                    dispatchWriter.write(commandJson)
                    dispatchWriter.newLine()
                    dispatchWriter.flush()
                }
                Log.i(TAG, "Command sent id=$id target=$target sourceChars=${source.length}")
            } catch (e: Exception) {
                pendingCommandSources.remove(id)
                val detail = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                synchronized(clientLock) {
                    // A queued write from the old channel must never close its replacement.
                    if (activeClientSocket === dispatchSocket) {
                        runCatching { dispatchSocket.close() }
                        activeClientSocket = null
                        activeClientWriter = null
                        _status.update { it.copy(
                            connectedPackage = null,
                            connectedPid = null,
                            commandReady = false,
                            lastError = "Command send failed: $detail",
                        ) }
                    }
                }
                Log.e(TAG, "Command send failed id=$id target=$target ($detail)", e)
                _commandResults.emit(
                    CommandResult(
                        id = id,
                        ok = false,
                        result = null,
                        error = "Command send failed: $detail",
                        source = source,
                        targetPackage = target,
                        sessionId = dispatchSessionId,
                    ),
                )
            }
        }
        return CommandDispatch(true, id = id)
    }

    fun isRunning(): Boolean = isRunning

    private fun processTrafficChunk(traffic: CapturedTraffic, sessionId: String?, targetPackage: String?) {
        val rawBytes = traffic.decodeData()
        if (rawBytes.isEmpty()) return

        val connKey = "${traffic.pkg}-${traffic.conn}"

        // SSL_read/SSL_write sees plaintext after TLS. Once a connection announces the HTTP/2
        // preface, keep its directional HPACK state here and never route its binary frames through
        // the HTTP/1 parser.
        val http2 = http2Connections[connKey]
        if (traffic.source == "tls" && (http2 != null || isHttp2Preface(rawBytes))) {
            val decoder = http2 ?: LiveHttp2ConnectionDecoder(
                defaultHost = traffic.pkg,
                sessionId = sessionId,
                targetPackage = targetPackage ?: traffic.pkg,
                onRecord = TrafficInspectionStore::record
            ).also { http2Connections[connKey] = it }
            decoder.feed(clientToServer = traffic.dir == "out", bytes = rawBytes)
            return
        }

        if (traffic.dir == "out") {
            handleOutbound(traffic, rawBytes, connKey, sessionId, targetPackage)
        } else {
            handleInbound(traffic, rawBytes, connKey, sessionId, targetPackage)
        }
    }

    private fun handleOutbound(
        traffic: CapturedTraffic,
        rawBytes: ByteArray,
        connKey: String,
        sessionId: String?,
        targetPackage: String?
    ) {
        if (isHttp2Preface(rawBytes) || isHttp2ControlFrame(rawBytes)) {
            Log.d(TAG, "Ignoring HTTP/2 setup/control outbound frame")
            return
        }

        val sep = findHeaderBodySeparator(rawBytes)
        val text = String(rawBytes, 0, minOf(rawBytes.size, 1024), Charsets.US_ASCII)
        val firstLine = text.lineSequence().firstOrNull().orEmpty().trim()
        val requestMatch = HTTP_REQUEST_REGEX.find(firstLine)

        if (requestMatch != null) {
            val method = requestMatch.groupValues[1].uppercase()
            val rawPath = requestMatch.groupValues[2]

            val headers = mutableMapOf<String, String>()
            var bodyOffset = rawBytes.size
            if (sep != -1) {
                val headerText = String(rawBytes, 0, sep, Charsets.UTF_8)
                parseHeaders(headerText, headers)
                bodyOffset = sep + getSeparatorLength(rawBytes, sep)
            }

            val host = headers["host"] ?: headers["Host"] ?: traffic.pkg
            val url = if (rawPath.startsWith("http://") || rawPath.startsWith("https://")) {
                rawPath
            } else {
                "https://$host$rawPath"
            }
            val isWebSocketUpgrade = headers["upgrade"]?.equals("websocket", ignoreCase = true) == true ||
                headers["connection"]?.contains("upgrade", ignoreCase = true) == true
            val encoding = headers["content-encoding"] ?: headers["Content-Encoding"]
            val contentType = headers["content-type"] ?: headers["Content-Type"]

            val txId = "frida-${traffic.pkg}-${traffic.conn}-${traffic.ts}"
            val tx = InFlightTransaction(
                id = txId,
                connId = traffic.conn,
                pkg = traffic.pkg,
                startTime = traffic.ts,
                method = method,
                url = url,
                host = host,
                requestHeaders = headers,
                requestEncoding = encoding
            )

            if (bodyOffset < rawBytes.size) {
                tx.requestBodyBuffer.write(rawBytes, bodyOffset, rawBytes.size - bodyOffset)
            }
            activeTransactions[connKey] = tx

            val decodedReqBody = decodeBodyPreview(tx.requestBodyBuffer.toByteArray(), tx.requestEncoding, contentType)
            val record = TrafficRecord(
                id = tx.id,
                sessionId = sessionId,
                targetPackage = targetPackage ?: traffic.pkg,
                timestamp = Instant.ofEpochMilli(tx.startTime),
                protocol = if (isWebSocketUpgrade) TrafficProtocol.WSS else TrafficProtocol.HTTPS,
                host = tx.host,
                port = tx.port,
                url = tx.url,
                method = tx.method,
                requestHeaders = tx.requestHeaders,
                requestBody = decodedReqBody,
                requestBodyBytes = tx.requestBodyBuffer.size().toLong(),
                state = TrafficCaptureState.DECODED
            )
            TrafficInspectionStore.record(record)
        } else {
            val tx = activeTransactions[connKey]
            if (tx != null && tx.statusCode == null) {
                tx.requestBodyBuffer.write(rawBytes)
                val decodedReqBody = decodeBodyPreview(
                    tx.requestBodyBuffer.toByteArray(),
                    tx.requestEncoding,
                    tx.requestHeaders["content-type"]
                )
                TrafficInspectionStore.updateRecord(tx.id) { rec ->
                    rec.copy(
                        requestBody = decodedReqBody,
                        requestBodyBytes = tx.requestBodyBuffer.size().toLong()
                    )
                }
            }
        }
    }

    private fun handleInbound(
        traffic: CapturedTraffic,
        rawBytes: ByteArray,
        connKey: String,
        sessionId: String?,
        targetPackage: String?
    ) {
        if (isHttp2ControlFrame(rawBytes)) {
            Log.d(TAG, "Ignoring HTTP/2 control inbound frame (${rawBytes.size}b)")
            return
        }

        val sep = findHeaderBodySeparator(rawBytes)
        val text = String(rawBytes, 0, minOf(rawBytes.size, 1024), Charsets.US_ASCII)
        val firstLine = text.lineSequence().firstOrNull().orEmpty().trim()
        val responseMatch = HTTP_RESPONSE_REGEX.find(firstLine)

        if (responseMatch != null) {
            val statusCode = responseMatch.groupValues[1].toIntOrNull() ?: 200
            val statusMessage = responseMatch.groupValues.getOrNull(2)?.trim() ?: "OK"

            val headers = mutableMapOf<String, String>()
            var bodyOffset = rawBytes.size
            if (sep != -1) {
                val headerText = String(rawBytes, 0, sep, Charsets.UTF_8)
                parseHeaders(headerText, headers)
                bodyOffset = sep + getSeparatorLength(rawBytes, sep)
            }

            val encoding = headers["content-encoding"] ?: headers["Content-Encoding"]
            val contentType = headers["content-type"] ?: headers["Content-Type"]

            val tx = activeTransactions[connKey]
            if (tx != null) {
                tx.statusCode = statusCode
                tx.statusMessage = statusMessage
                tx.responseHeaders.putAll(headers)
                tx.responseEncoding = encoding
                tx.responseContentType = contentType

                if (bodyOffset < rawBytes.size) {
                    tx.responseBodyBuffer.write(rawBytes, bodyOffset, rawBytes.size - bodyOffset)
                }

                val decodedRespBody = decodeBodyPreview(tx.responseBodyBuffer.toByteArray(), tx.responseEncoding, contentType)
                val duration = System.currentTimeMillis() - tx.startTime

                TrafficInspectionStore.updateRecord(tx.id) { rec ->
                    rec.copy(
                        statusCode = tx.statusCode,
                        statusMessage = tx.statusMessage,
                        contentType = tx.responseContentType ?: rec.contentType,
                        responseHeaders = tx.responseHeaders,
                        responseBody = decodedRespBody,
                        responseBodyBytes = tx.responseBodyBuffer.size().toLong(),
                        durationMs = duration,
                        state = TrafficCaptureState.DECODED
                    )
                }
            } else {
                val respBodyBytes = if (bodyOffset < rawBytes.size) {
                    rawBytes.copyOfRange(bodyOffset, rawBytes.size)
                } else {
                    ByteArray(0)
                }
                val decodedRespBody = decodeBodyPreview(respBodyBytes, encoding, contentType)
                val record = TrafficRecord(
                    id = "frida-${traffic.pkg}-${traffic.conn}-${traffic.ts}",
                    sessionId = sessionId,
                    targetPackage = targetPackage ?: traffic.pkg,
                    timestamp = Instant.ofEpochMilli(traffic.ts),
                    protocol = TrafficProtocol.HTTP,
                    host = traffic.pkg,
                    port = 443,
                    url = "https://${traffic.pkg}/",
                    method = "GET",
                    statusCode = statusCode,
                    statusMessage = statusMessage,
                    contentType = contentType,
                    responseHeaders = headers,
                    responseBody = decodedRespBody,
                    responseBodyBytes = respBodyBytes.size.toLong(),
                    state = TrafficCaptureState.UNSUPPORTED_PROTOCOL,
                    failureDetails = "Raw Frida I/O bytes; HTTP/1.x framing was not detected"
                )
                TrafficInspectionStore.record(record)
            }
        } else {
            val tx = activeTransactions[connKey]
            if (tx != null && tx.statusCode != null) {
                tx.responseBodyBuffer.write(rawBytes)
                val decodedRespBody = decodeBodyPreview(
                    tx.responseBodyBuffer.toByteArray(),
                    tx.responseEncoding,
                    tx.responseContentType
                )
                val duration = System.currentTimeMillis() - tx.startTime
                TrafficInspectionStore.updateRecord(tx.id) { rec ->
                    rec.copy(
                        responseBody = decodedRespBody,
                        responseBodyBytes = tx.responseBodyBuffer.size().toLong(),
                        durationMs = duration
                    )
                }
            } else {
                val decoded = decodeBodyPreview(rawBytes, null, null)
                val record = TrafficRecord(
                    id = "frida-chunk-${traffic.pkg}-${traffic.ts}-${traffic.len}",
                    sessionId = sessionId,
                    targetPackage = targetPackage ?: traffic.pkg,
                    timestamp = Instant.ofEpochMilli(traffic.ts),
                    protocol = TrafficProtocol.HTTP,
                    host = traffic.pkg,
                    port = 443,
                    url = "[Payload Chunk ${traffic.len}b]",
                    method = traffic.dir.uppercase(),
                    requestBody = if (traffic.dir == "out") decoded else null,
                    responseBody = if (traffic.dir == "in") decoded else null,
                    requestBodyBytes = if (traffic.dir == "out") traffic.len.toLong() else 0L,
                    responseBodyBytes = if (traffic.dir == "in") traffic.len.toLong() else 0L,
                    state = TrafficCaptureState.UNSUPPORTED_PROTOCOL,
                    failureDetails = "Raw Frida I/O bytes; HTTP/1.x framing was not detected"
                )
                TrafficInspectionStore.record(record)
            }
        }
    }

    private fun decodeBodyPreview(bytes: ByteArray, encoding: String?, contentType: String?): String {
        if (bytes.isEmpty()) return ""

        // 1. Automatic Gzip / Deflate decompression
        val decompressed = try {
            when {
                encoding?.equals("gzip", ignoreCase = true) == true || isGzip(bytes) -> decompressGzip(bytes)
                encoding?.equals("deflate", ignoreCase = true) == true || isZlibDeflate(bytes) -> decompressDeflate(bytes)
                else -> bytes
            }
        } catch (e: Exception) {
            bytes
        }

        // 2. Plain text / JSON validation
        if (isMostlyPrintableText(decompressed)) {
            val text = String(decompressed, Charsets.UTF_8)
            return tryPrettyPrintJson(text)
        }

        // 3. Binary media summary (never show corrupted binary noise)
        val binType = detectBinaryType(decompressed) ?: contentType ?: "application/octet-stream"
        val hex = decompressed.take(48).joinToString(" ") { "%02X".format(it) }
        return "[Binary Payload: $binType (${decompressed.size} bytes)]\nHex: $hex..."
    }

    private fun isGzip(bytes: ByteArray): Boolean {
        return bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()
    }

    private fun isZlibDeflate(bytes: ByteArray): Boolean {
        return bytes.size >= 2 && bytes[0] == 0x78.toByte() &&
                (bytes[1] == 0x01.toByte() || bytes[1] == 0x9C.toByte() || bytes[1] == 0xDA.toByte())
    }

    private fun decompressGzip(bytes: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
    }

    private fun decompressDeflate(bytes: ByteArray): ByteArray {
        val inflater = Inflater(false)
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream(bytes.size * 2)
        val buf = ByteArray(4096)
        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count <= 0) break
                out.write(buf, 0, count)
            }
        } catch (_: Exception) {
            val rawInflater = Inflater(true)
            rawInflater.setInput(bytes)
            val rawOut = ByteArrayOutputStream(bytes.size * 2)
            while (!rawInflater.finished()) {
                val count = rawInflater.inflate(buf)
                if (count <= 0) break
                rawOut.write(buf, 0, count)
            }
            rawInflater.end()
            return rawOut.toByteArray()
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun isMostlyPrintableText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        var nonPrintable = 0
        val sampleSize = minOf(bytes.size, 512)
        for (i in 0 until sampleSize) {
            val b = bytes[i].toInt() and 0xFF
            if (b != 9 && b != 10 && b != 13 && (b < 32 || b > 126) && b < 128) {
                nonPrintable++
            }
        }
        return (nonPrintable.toDouble() / sampleSize) < 0.15
    }

    private fun tryPrettyPrintJson(str: String): String {
        val trimmed = str.trim()
        return try {
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                JSONObject(trimmed).toString(2)
            } else if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                JSONArray(trimmed).toString(2)
            } else {
                str
            }
        } catch (_: Exception) {
            str
        }
    }

    private fun detectBinaryType(bytes: ByteArray): String? {
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()) {
            return "image/png"
        }
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        if (bytes.size >= 12 && String(bytes.sliceArray(0..3), Charsets.US_ASCII) == "RIFF" &&
            String(bytes.sliceArray(8..11), Charsets.US_ASCII) == "WEBP") {
            return "image/webp"
        }
        return null
    }

    private fun isHttp2Preface(bytes: ByteArray): Boolean {
        if (bytes.size < 24) return false
        val preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
        return String(bytes, 0, 24, Charsets.US_ASCII) == preface
    }

    private fun isHttp2ControlFrame(bytes: ByteArray): Boolean {
        if (bytes.size in 9..32) {
            val type = bytes.getOrNull(3)?.toInt()?.and(0xFF) ?: return false
            if (type == 4 || type == 6 || type == 8) return true
        }
        return false
    }

    private fun findHeaderBodySeparator(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 3) {
            if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() && bytes[i + 3] == '\n'.code.toByte()) {
                return i
            }
        }
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == '\n'.code.toByte() && bytes[i + 1] == '\n'.code.toByte()) {
                return i
            }
        }
        return -1
    }

    private fun getSeparatorLength(bytes: ByteArray, index: Int): Int {
        return if (index + 3 < bytes.size && bytes[index] == '\r'.code.toByte()) 4 else 2
    }

    private fun parseHeaders(headerText: String, map: MutableMap<String, String>) {
        val lines = headerText.lineSequence().drop(1)
        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon > 0) {
                val key = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                map[key] = value
            }
        }
    }

    companion object {
        private const val LISTEN_PORT = 9999
        private const val COMMAND_RESPONSE_TIMEOUT_MS = 15_000L
        private val HTTP_REQUEST_REGEX = Regex("^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|CONNECT)\\s+([^\\s]+)\\s+HTTP/1\\.[01]", RegexOption.IGNORE_CASE)
        private val HTTP_RESPONSE_REGEX = Regex("^HTTP/1\\.[01]\\s+(\\d{3})(?:\\s+([^\\r\\n]*))?", RegexOption.IGNORE_CASE)
        val shared = FridaTrafficMonitor()
    }
}

private fun JSONObject.optionalJsonString(key: String): String? {
    val value = opt(key)
    return if (value == null || value === JSONObject.NULL) null else value.toString()
}
