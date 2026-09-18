package com.apksandbox.fixture

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FixtureActivity : Activity() {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private lateinit var logView: TextView

    private fun log(message: String) {
        Log.i("SandboxFixture", message)
        runOnUiThread {
            if (::logView.isInitialized) {
                logView.append("$message\n")
            }
        }
    }

    override fun onNewIntent(i: android.content.Intent) {
        super.onNewIntent(i)
        setIntent(i)
        handleHeadlessTriggers(i)
    }

    private fun handleHeadlessTriggers(i: android.content.Intent) {
        if (i.getBooleanExtra("runStressSuite", false)) startForegroundService(android.content.Intent(this, StressForegroundService::class.java))
        i.getStringExtra("runWorkloads")?.let { workloads ->
            startForegroundService(android.content.Intent(this, RootCauseForegroundService::class.java).putExtra("workloads", workloads))
        }
        if (i.getBooleanExtra("runHttpsGet", false)) doHttpsGet()
        if (i.getBooleanExtra("runHttpsPost", false)) doHttpsPost()
        if (i.getBooleanExtra("runHttpsPublicApi", false)) doHttpsPublicApi()
        if (i.getBooleanExtra("runHttpsQuote", false)) doHttpsQuote()
        if (i.getBooleanExtra("runHttpsPut", false)) doHttpsPut()
        if (i.getBooleanExtra("runHttpsDelete", false)) doHttpsDelete()
        if (i.getBooleanExtra("runHttpsReject", false)) doHttpsReject()
        if (i.getBooleanExtra("runPlaintextHttpGet", false)) doPlaintextHttpGet()
        if (i.getBooleanExtra("runPlaintextHttpPost", false)) doPlaintextHttpPost()
        if (i.getBooleanExtra("runPersistentHttp", false)) doPersistentHttp()
        if (i.getBooleanExtra("runWebSocketWs", false)) doWebSocketWs()
        if (i.getBooleanExtra("runWebSocketWss", false)) doWebSocketWss()

        if (i.getBooleanExtra("runCorrectnessFixture", false)) Thread {
            fun logOutcome(test: String, block: () -> String) {
                val outcome = try { block() } catch (e: Exception) { "${e.javaClass.simpleName}: ${e.message}" }
                Log.i("SandboxFixture", "CORRECTNESS_STEP $test $outcome")
            }
            logOutcome("dnsAndHttpsRequest") {
                val c = URL("https://example.com/").openConnection() as javax.net.ssl.HttpsURLConnection
                c.connectTimeout = 5000; c.readTimeout = 5000
                try { "HTTP ${c.responseCode}" } finally { c.disconnect() }
            }
            logOutcome("rawIpTcpNoDns") {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("1.1.1.1", 443), 3000) }
                "CONNECTED"
            }
            logOutcome("blockedPrivateDestination") {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("192.168.1.1", 80), 3000) }
                "CONNECTED"
            }
            logOutcome("rawUdp53DnsQuery") {
                val packet = java.io.ByteArrayOutputStream().apply {
                    write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                    "example.com".split(".").forEach { label -> write(label.length); write(label.toByteArray(Charsets.US_ASCII)) }
                    write(0x00)
                    write(byteArrayOf(0x00, 0x01, 0x00, 0x01))
                }.toByteArray()
                java.net.DatagramSocket().use { socket ->
                    socket.soTimeout = 3000
                    socket.send(java.net.DatagramPacket(packet, packet.size, java.net.InetAddress.getByName("1.1.1.1"), 53))
                    val responseBuf = ByteArray(512)
                    socket.receive(java.net.DatagramPacket(responseBuf, responseBuf.size))
                    "RESPONSE_RECEIVED"
                }
            }
            Log.i("SandboxFixture", "CORRECTNESS_FIXTURE_COMPLETE")
        }.start()
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)

        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#0F141C"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
        }
        scroll.addView(root)

        // Header
        val title = TextView(this).apply {
            text = "APK Scope Fixture"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Live Traffic Inspection & Work Profile Test Suite"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 4, 0, 24)
        }
        root.addView(subtitle)

        // Output Card
        val logCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 16f
                setStroke(2, Color.parseColor("#334155"))
            }
            background = bg
            setPadding(24, 20, 24, 20)
        }

        val logHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logTitle = TextView(this).apply {
            text = "LOG OUTPUT"
            textSize = 11f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        logHeaderRow.addView(logTitle)

        val clearBtn = Button(this).apply {
            text = "Clear"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#475569"))
            setOnClickListener { logView.text = "" }
        }
        logHeaderRow.addView(clearBtn)
        logCard.addView(logHeaderRow)

        logView = TextView(this).apply {
            text = "Ready. Tap any action below to trigger live network traffic.\n"
            textSize = 12f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, 12, 0, 0)
        }
        logCard.addView(logView)
        root.addView(logCard)

        fun section(label: String) {
            val tv = TextView(this).apply {
                text = label.uppercase()
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(8, 28, 8, 12)
            }
            root.addView(tv)
        }

        fun button(label: String, colorHex: String = "#2563EB", action: () -> Unit) {
            val btn = Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor(colorHex))
                    cornerRadius = 12f
                }
                background = bg
                textSize = 13f
                isAllCaps = false
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 6, 0, 6)
                }
                layoutParams = params
                setOnClickListener { action() }
            }
            root.addView(btn)
        }

        // Section 1: HTTPS REST APIs
        section("HTTPS APIs (Decrypted by Inspector)")
        button("GET JSON (jsonplaceholder.typicode.com/posts/1)", "#0284C7") { doHttpsPublicApi() }
        button("GET Quotes (dummyjson.com/quotes/random)", "#0284C7") { doHttpsQuote() }
        button("GET Query & Headers (httpbin.org/get)", "#0284C7") { doHttpsGet() }
        button("POST Create Post (jsonplaceholder.typicode.com/posts)", "#16A34A") { doHttpsPostJsonPlaceholder() }
        button("POST JSON Echo (httpbin.org/post)", "#16A34A") { doHttpsPost() }
        button("PUT Update Data (httpbin.org/put)", "#D97706") { doHttpsPut() }
        button("DELETE Resource (httpbin.org/delete)", "#DC2626") { doHttpsDelete() }

        // Section 2: WebSocket Streams
        section("WebSocket Streams (Recorded by Inspector)")
        button("WSS Secure WebSocket (wss://echo.websocket.org)", "#7C3AED") { doWebSocketWss() }
        button("WS Plaintext WebSocket (ws://echo.websocket.org)", "#6366F1") { doWebSocketWs() }

        // Section 3: Edge Cases & Security
        section("TLS Edge Cases & Diagnostics")
        button("HTTPS Reject Inspection CA (Strict Pinning)", "#9333EA") { doHttpsReject() }
        button("HTTP Persistent Connection (3x GET)", "#059669") { doPersistentHttp() }
        button("Test blocked RFC1918 connection", "#B91C1C") {
            Thread {
                val result = try {
                    java.net.Socket().use { it.connect(java.net.InetSocketAddress("192.168.1.1", 80), 3000) }
                    "RFC1918_UNEXPECTEDLY_CONNECTED"
                } catch (e: Exception) {
                    "RFC1918_BLOCKED ${e.javaClass.simpleName}: ${e.message}"
                }
                log(result)
            }.start()
        }
        button("Test explicit UDP/53 DNS query", "#0D9488") {
            Thread {
                val result = try {
                    val packet = java.io.ByteArrayOutputStream().apply {
                        write(byteArrayOf(0x56, 0x78, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                        "example.com".split(".").forEach { label -> write(label.length); write(label.toByteArray(Charsets.US_ASCII)) }
                        write(0x00); write(byteArrayOf(0x00, 0x01, 0x00, 0x01))
                    }.toByteArray()
                    java.net.DatagramSocket().use { socket ->
                        socket.soTimeout = 3000
                        socket.send(java.net.DatagramPacket(packet, packet.size, java.net.InetAddress.getByName("1.1.1.1"), 53))
                        val responseBuf = ByteArray(512)
                        socket.receive(java.net.DatagramPacket(responseBuf, responseBuf.size))
                        "DNS_RESPONSE_RECEIVED"
                    }
                } catch (e: Exception) {
                    "DNS_QUERY_FAILED ${e.javaClass.simpleName}: ${e.message}"
                }
                log(result)
            }.start()
        }

        // Section 4: Legacy Sandbox Hardening Checks
        section("Sandbox Isolation Checks")
        button("Test permission requests", "#475569") {
            requestPermissions(arrayOf(
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_FINE_LOCATION"
            ), 1)
        }
        button("Test controller private storage", "#475569") {
            val result = runCatching { java.io.File("/data/user/${android.os.Process.myUid() / 100000}/com.nadeem.apkscope/files/gate.jsonl").readText() }
            log(if (result.isFailure) "PRIVATE_STORAGE_DENIED ${result.exceptionOrNull()}" else "PRIVATE_STORAGE_READABLE")
        }
        button("Test raw IP and bypass", "#475569") {
            Thread {
                val cm = getSystemService(android.net.ConnectivityManager::class.java)
                val attempts = listOf("default" to null) + cm.allNetworks.map { it.toString() to it }
                for ((label, network) in attempts) {
                    val result = try {
                        java.net.Socket().use { socket ->
                            network?.bindSocket(socket)
                            socket.connect(java.net.InetSocketAddress("1.1.1.1", 443), 3000)
                            "RAW_IP_SUCCESS $label"
                        }
                    } catch (e: Exception) {
                        "RAW_IP_DENIED $label ${e.javaClass.simpleName}: ${e.message}"
                    }
                    log(result)
                }
            }.start()
        }
        button("Attempt controller disable", "#475569") {
            try {
                packageManager.setApplicationEnabledSetting("com.nadeem.apkscope", android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0)
                log("CONTROLLER_DISABLE_SUCCEEDED")
            } catch (e: Exception) {
                log("CONTROLLER_DISABLE_DENIED ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        button("Run hardening-gate stress suite", "#475569") {
            startForegroundService(android.content.Intent(this, StressForegroundService::class.java))
        }

        val marker = java.io.File(filesDir, "marker")
        log("Marker file present: ${marker.exists()}")
        marker.writeText("harmless test data")

        setContentView(scroll)
        handleHeadlessTriggers(intent)
    }

    private fun doHttpsGet() {
        Thread {
            val start = System.currentTimeMillis()
            val request = Request.Builder()
                .url("https://httpbin.org/get?source=apk_scope&test=traffic_inspector&timestamp=$start")
                .header("User-Agent", "APK-Sandbox-Fixture/1.0")
                .header("X-Inspection-Test", "active")
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    val elapsed = System.currentTimeMillis() - start
                    val body = response.body?.string() ?: ""
                    val snippet = if (body.length > 150) body.take(150) + "..." else body
                    log("HTTPS_GET_SUCCESS [${response.code}] in ${elapsed}ms: ${snippet.trim()}")
                }
            } catch (e: Exception) {
                log("HTTPS_GET_FAILED ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun doHttpsPublicApi() {
        Thread {
            val start = System.currentTimeMillis()
            val request = Request.Builder()
                .url("https://jsonplaceholder.typicode.com/posts/1")
                .header("Accept", "application/json")
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    val elapsed = System.currentTimeMillis() - start
                    val body = response.body?.string() ?: ""
                    log("HTTPS_JSONPLACEHOLDER_SUCCESS [${response.code}] in ${elapsed}ms:\n${body.trim()}")
                }
            } catch (e: Exception) {
                log("HTTPS_JSONPLACEHOLDER_FAILED ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun doHttpsQuote() {
        Thread {
            val start = System.currentTimeMillis()
            val request = Request.Builder()
                .url("https://dummyjson.com/quotes/random")
                .header("Accept", "application/json")
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    val elapsed = System.currentTimeMillis() - start
                    val body = response.body?.string() ?: ""
                    log("HTTPS_QUOTE_SUCCESS [${response.code}] in ${elapsed}ms:\n${body.trim()}")
                }
            } catch (e: Exception) {
                log("HTTPS_QUOTE_FAILED ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun doHttpsPost() {
        Thread {
            val start = System.currentTimeMillis()
            val json = """{"client":"APK-Scope-Fixture","action":"https_post_test","timestamp":$start,"inspected":true}"""
            val request = Request.Builder()
                .url("https://httpbin.org/post")
                .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    val elapsed = System.currentTimeMillis() - start
                    val body = response.body?.string() ?: ""
                    val snippet = if (body.length > 150) body.take(150) + "..." else body
                    log("HTTPS_POST_SUCCESS [${response.code}] in ${elapsed}ms: ${snippet.trim()}")
                }
            } catch (e: Exception) {
                log("HTTPS_POST_FAILED ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun doHttpsPostJsonPlaceholder() {
        Thread {
            val start = System.currentTimeMillis()
            val json = """{"title":"Inspection Test","body":"Live network verification inside APK Scope Work Profile","userId":42}"""
            val request = Request.Builder()
                .url("https://jsonplaceholder.typicode.com/posts")
                .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    val elapsed = System.currentTimeMillis() - start
                    val body = response.body?.string() ?: ""
                    log("HTTPS_CREATE_POST_SUCCESS [${response.code}] in ${elapsed}ms:\n${body.trim()}")
                }
            } catch (e: Exception) {
                log("HTTPS_CREATE_POST_FAILED ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun doHttpsPut() {
        Thread {
            val start = System.currentTimeMillis()
            val json = """{"item_id":123,"status":"updated","updated_at":$start}"""
            val request = Request.Builder()
                .url("https://httpbin.org/put")
                .put(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    val elapsed = System.currentTimeMillis() - start
                    val body = response.body?.string() ?: ""
                    val snippet = if (body.length > 150) body.take(150) + "..." else body
                    log("HTTPS_PUT_SUCCESS [${response.code}] in ${elapsed}ms: ${snippet.trim()}")
                }
            } catch (e: Exception) {
                log("HTTPS_PUT_FAILED ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun doHttpsDelete() {
        Thread {
            val start = System.currentTimeMillis()
            val request = Request.Builder()
                .url("https://httpbin.org/delete?id=123")
                .delete()
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    val elapsed = System.currentTimeMillis() - start
                    val body = response.body?.string() ?: ""
                    val snippet = if (body.length > 150) body.take(150) + "..." else body
                    log("HTTPS_DELETE_SUCCESS [${response.code}] in ${elapsed}ms: ${snippet.trim()}")
                }
            } catch (e: Exception) {
                log("HTTPS_DELETE_FAILED ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun doHttpsReject() {
        Thread {
            val result = try {
                val c = URL("https://httpbin.org/get").openConnection() as javax.net.ssl.HttpsURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val strictTrustManager = object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
                        val isInspectionCa = chain?.any { it.issuerX500Principal.name.contains("APK Scope POC") } == true
                        if (isInspectionCa) {
                            throw java.security.cert.CertificateException("Custom TrustManager rejected APK Scope inspection CA (Pinning Simulation)")
                        }
                    }
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                }
                val sc = javax.net.ssl.SSLContext.getInstance("TLS")
                sc.init(null, arrayOf(strictTrustManager), java.security.SecureRandom())
                c.sslSocketFactory = sc.socketFactory
                try {
                    val code = c.responseCode
                    "HTTPS_REJECT_UNEXPECTED HTTP $code"
                } finally { c.disconnect() }
            } catch (e: Exception) {
                "HTTPS_REJECT_EXPECTED ${e.javaClass.simpleName}: ${e.message}"
            }
            log(result)
        }.start()
    }

    private fun doPlaintextHttpGet() {
        Thread {
            val start = System.currentTimeMillis()
            val request = Request.Builder().url("http://httpbin.org/get").build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    val elapsed = System.currentTimeMillis() - start
                    val body = response.body?.string() ?: ""
                    log("HTTP_GET_SUCCESS [${response.code}] in ${elapsed}ms bodyLen=${body.length}")
                }
            } catch (e: Exception) {
                log("HTTP_GET_FAILED ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun doPlaintextHttpPost() {
        Thread {
            val start = System.currentTimeMillis()
            val json = """{"sender":"apk-scope-fixture","action":"plaintext_post_test","timestamp":$start}"""
            val request = Request.Builder()
                .url("http://httpbin.org/post")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    val elapsed = System.currentTimeMillis() - start
                    val body = response.body?.string() ?: ""
                    log("HTTP_POST_SUCCESS [${response.code}] in ${elapsed}ms bodyLen=${body.length}")
                }
            } catch (e: Exception) {
                log("HTTP_POST_FAILED ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun doPersistentHttp() {
        Thread {
            val request = Request.Builder().url("http://httpbin.org/get").build()
            try {
                val r1 = httpClient.newCall(request).execute(); val b1 = r1.body?.string() ?: ""; r1.close()
                val r2 = httpClient.newCall(request).execute(); val b2 = r2.body?.string() ?: ""; r2.close()
                val r3 = httpClient.newCall(request).execute(); val b3 = r3.body?.string() ?: ""; r3.close()
                log("HTTP_PERSISTENT_SUCCESS count=3 b1=${b1.length} b2=${b2.length} b3=${b3.length}")
            } catch (e: Exception) {
                log("HTTP_PERSISTENT_FAILED ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun doWebSocketWs() {
        Thread {
            log("WS: Connecting to ws://echo.websocket.org ...")
            val request = Request.Builder().url("ws://echo.websocket.org").build()
            val latch = CountDownLatch(1)
            httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    log("WS_OPEN: HTTP ${response.code}")
                    webSocket.send("Hello APK Scope WS Frame 1")
                    webSocket.send("Hello APK Scope WS Frame 2")
                    webSocket.send(ByteString.of(1, 2, 3, 4, 5))
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    log("WS_MESSAGE_TEXT: $text")
                    webSocket.close(1000, "normal closure")
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    log("WS_MESSAGE_BINARY: size=${bytes.size}")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    log("WS_CLOSED code=$code reason=$reason")
                    latch.countDown()
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    log("WS_FAILED ${t.javaClass.simpleName}: ${t.message}")
                    latch.countDown()
                }
            })
            latch.await(10, TimeUnit.SECONDS)
        }.start()
    }

    private fun doWebSocketWss() {
        Thread {
            log("WSS: Connecting to wss://echo.websocket.org ...")
            val request = Request.Builder().url("wss://echo.websocket.org").build()
            val latch = CountDownLatch(1)
            var messagesReceived = 0
            httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    log("WSS_OPEN: HTTP ${response.code} (TLS Handshake Intercepted)")
                    webSocket.send("Hello APK Scope Secure WebSocket!")
                    webSocket.send("Frame 2: Live Bidirectional Streaming Verification")
                    webSocket.send(ByteString.of(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    messagesReceived++
                    log("WSS_ECHO_TEXT [$messagesReceived]: $text")
                    if (messagesReceived >= 2) {
                        webSocket.close(1000, "test complete")
                    }
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    log("WSS_ECHO_BINARY: ${bytes.hex()} (${bytes.size} bytes)")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    log("WSS_CLOSED code=$code reason=$reason")
                    latch.countDown()
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    log("WSS_FAILED ${t.javaClass.simpleName}: ${t.message}")
                    latch.countDown()
                }
            })
            latch.await(12, TimeUnit.SECONDS)
        }.start()
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g)
        log("PERMISSIONS " + p.zip(g.toList()))
    }
}
