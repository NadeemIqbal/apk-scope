package com.apksandbox.pinnedfixture

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
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * POC Pinned Activity for the pinned fixture.
 *
 * This activity demonstrates a real, enforced certificate pinning scenario:
 * - Makes HTTPS requests to httpbin.org with an explicit SPKI pin check.
 * - When run against the real endpoint (unmodified fixture), the request succeeds.
 * - When run with inspection enabled but Gadget unloaded, the pin check fails (rejected by OkHttp).
 * - When run with Gadget loaded and hooked, the pin check can be bypassed by the Gadget instrumentation.
 *
 * The actual pin value is a real SPKI hash for httpbin.org's certificate chain. If the endpoint
 * or its certificate changes, the pin will need to be updated — but for a POC fixture under our
 * control, we can keep it stable.
 */
class PinnedActivity : Activity() {

    private lateinit var logView: TextView

    /**
     * OkHttp client configured with certificate pinning for httpbin.org.
     *
     * The pin value is a real SPKI SHA-256 hash. When the Frida Gadget is loaded and hooked,
     * it can intercept and suppress the pin check; without it, the check is enforced.
     */
    private val pinnedHttpClient: OkHttpClient by lazy {
        val pinner = CertificatePinner.Builder()
            // httpbin.org certificate pin (SPKI sha256)
            // Note: This is a real pin for the current httpbin.org certificate.
            // If the backend changes, this will need to be updated.
            .add("httpbin.org", "sha256/gcKshULiJF4eKGblyPx4h+y2Yg5O6HYb6N5jRsz0u9c=")
            .build()

        OkHttpClient.Builder()
            .certificatePinner(pinner)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun log(message: String) {
        Log.i("PinnedFixture", message)
        runOnUiThread {
            logView.append("$message\n")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            text = "POC Pinned Fixture"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Certificate Pinning Test"
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

        val logTitle = TextView(this).apply {
            text = "LOG OUTPUT"
            textSize = 11f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 0, 0, 12)
        }
        logCard.addView(logTitle)

        logView = TextView(this).apply {
            text = "Ready. Tap actions below.\n"
            textSize = 12f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.parseColor("#E2E8F0"))
        }
        logCard.addView(logView)
        root.addView(logCard)

        // Buttons section
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
                    setMargins(0, 8, 0, 8)
                }
                layoutParams = params
                setOnClickListener { action() }
            }
            root.addView(btn)
        }

        button("HTTPS GET (Pinned) — httpbin.org/get", "#0284C7") {
            makeHttpsGetRequest()
        }

        button("Check Gadget Status", "#9333EA") {
            checkGadgetStatus()
        }

        setContentView(scroll)
    }

    private fun makeHttpsGetRequest() {
        Thread {
            val start = System.currentTimeMillis()
            val request = Request.Builder()
                .url("https://httpbin.org/get?source=poc_pinned_fixture&timestamp=$start")
                .header("X-Poc-Test", "pinning-enforcement")
                .build()

            try {
                val response = pinnedHttpClient.newCall(request).execute()
                val elapsed = System.currentTimeMillis() - start
                val body = response.body?.string() ?: ""
                val snippet = if (body.length > 200) body.take(200) + "..." else body
                response.close()

                log("✓ PINNED_REQUEST_SUCCESS [${response.code}] in ${elapsed}ms\n$snippet")
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - start
                log("✗ PINNED_REQUEST_FAILED [${elapsed}ms] ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun checkGadgetStatus() {
        Thread {
            try {
                // Attempt to call a Gadget-provided function; if it exists, Gadget is loaded
                val scriptFile = getFileStreamPath("poc_gadget_hook.js")
                val gadgetLoaded = try {
                    // Try to access Frida API if loaded
                    @Suppress("UNCHECKED_CAST")
                    val fridaClass = Class.forName("frida.Frida")
                    true
                } catch (e: Exception) {
                    false
                }

                val gadgetLibLoaded = try {
                    Class.forName("com.frida.Frida")
                    true
                } catch (e: Exception) {
                    false
                }

                log("Gadget Status: loaded=$gadgetLoaded, script=${scriptFile.exists()}, lib=$gadgetLibLoaded")
            } catch (e: Exception) {
                log("Error checking Gadget: ${e.message}")
            }
        }.start()
    }
}
