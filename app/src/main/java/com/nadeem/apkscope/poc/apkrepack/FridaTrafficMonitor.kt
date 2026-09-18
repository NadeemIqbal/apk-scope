package com.nadeem.apkscope.poc.apkrepack

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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
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
class FridaTrafficMonitor {

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
        val capturedCount: Int = 0,
        val lastError: String? = null,
        val port: Int = LISTEN_PORT
    )

    private val _trafficFlow = MutableSharedFlow<CapturedTraffic>(
        replay = 100,
        extraBufferCapacity = 50
    )
    val trafficFlow: SharedFlow<CapturedTraffic> = _trafficFlow
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private var activeSessionId: String? = null
    private var activeTargetPackage: String? = null

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
        activeSessionId = sessionId
        activeTargetPackage = targetPackage
        if (isRunning) {
            Log.i(TAG, "Traffic monitor already active, session=$sessionId, target=$targetPackage")
            return
        }

        synchronized(this) {
            if (isRunning) return
            isRunning = true
        }

        serverJob = monitorScope.launch {
            runServer()
        }
    }

    private suspend fun runServer() {
        withContext(Dispatchers.IO) {
            try {
                try {
                    serverSocket?.close()
                } catch (_: Exception) {}

                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(LISTEN_PORT))
                }
                _status.value = _status.value.copy(isListening = true, lastError = null)
                Log.i(TAG, "Traffic monitor server listening on 127.0.0.1:$LISTEN_PORT")

                while (isRunning && isActive) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        Log.i(TAG, "New client connected: ${clientSocket.remoteSocketAddress}")
                        monitorScope.launch {
                            handleClient(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (isRunning && isActive) {
                            Log.e(TAG, "Accept error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start monitor: ${e.message}", e)
                _status.value = _status.value.copy(isListening = false, lastError = e.message)
            } finally {
                isRunning = false
                try {
                    serverSocket?.close()
                } catch (_: Exception) {}
                serverSocket = null
                _status.value = _status.value.copy(isListening = false)
            }
        }
    }

    private suspend fun handleClient(clientSocket: Socket) {
        withContext(Dispatchers.IO) {
            var packageName = "unknown"

            try {
                clientSocket.use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))

                    // First line: package name
                    packageName = reader.readLine() ?: "unknown"
                    _status.value = _status.value.copy(connectedPackage = packageName, lastError = null)
                    Log.i(TAG, "Client package connected: $packageName")

                    // Then: JSON-lines of traffic events
                    var line: String? = null
                    while (isRunning && reader.readLine().also { line = it } != null) {
                        val currentLine = line
                        if (currentLine.isNullOrBlank()) continue

                        try {
                            val obj = JSONObject(currentLine)
                            val traffic = CapturedTraffic(
                                pkg = obj.optString("pkg", packageName),
                                dir = obj.optString("dir", "in"),
                                len = obj.optInt("len", 0),
                                ts = obj.optLong("ts", System.currentTimeMillis()),
                                data = obj.optString("data", ""),
                                conn = obj.optString("conn", "default"),
                                source = obj.optString("source", "tls")
                            )

                            _trafficFlow.emit(traffic)
                            processTrafficChunk(traffic, activeSessionId, activeTargetPackage)
                            _status.value = _status.value.copy(
                                capturedCount = _status.value.capturedCount + 1,
                                lastError = null
                            )
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
                _status.value = _status.value.copy(connectedPackage = null)
                try {
                    clientSocket.close()
                } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        isRunning = false
        serverJob?.cancel()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket: ${e.message}")
        }
        serverSocket = null
        activeTransactions.clear()
        http2Connections.clear()
        _status.value = _status.value.copy(isListening = false, connectedPackage = null)
        Log.i(TAG, "Traffic monitor stopped")
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
                protocol = TrafficProtocol.HTTPS,
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
        private val HTTP_REQUEST_REGEX = Regex("^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|CONNECT)\\s+([^\\s]+)\\s+HTTP/1\\.[01]", RegexOption.IGNORE_CASE)
        private val HTTP_RESPONSE_REGEX = Regex("^HTTP/1\\.[01]\\s+(\\d{3})(?:\\s+([^\\r\\n]*))?", RegexOption.IGNORE_CASE)
        val shared = FridaTrafficMonitor()
    }
}
