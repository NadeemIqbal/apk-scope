package com.apksandbox.riskfixture

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Purely a static-analysis test fixture (checkpoint 3, item 24) — every *dangerous* permission this
 * app declares (SMS, location, contacts, accessibility, etc.) is inert; nothing here reads them. It
 * exists only to be sideloaded and statically analyzed.
 *
 * Milestone 9 (acceptance rigor pass, 2026-09-13) addition: the one button below is a deliberate,
 * narrowly-scoped exception — it exercises the already-declared, non-dangerous `INTERNET` permission
 * to make this a genuine **second controlled application** for the ownership-verification acceptance
 * check (MS9-CAP02): a real app, distinct from the sandboxed target, generating its own real,
 * distinguishable HTTPS traffic when installed directly into the Work Profile (`adb install --user
 * <id>`, never through the sandbox's own APK-handoff flow — this app is never itself "the target").
 * Does not touch, weaken, or bypass any production VPN/capture/routing code — it is simply a second
 * real source of traffic for that code to observe. The request target
 * (`https://api.github.com/zen`) is deliberately a host the actual sandboxed fixture (`:fixture`
 * module) never requests, so a captured transaction is unambiguously attributable to *this* app,
 * not a coincidental overlap.
 */
class MainActivity : Activity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val status = TextView(this).apply { text = "Risk Signal Fixture — static analysis test data only, does nothing." }
  val button = Button(this).apply {
   text = "MS9 second-app traffic: GET api.github.com/zen"
   setOnClickListener {
    status.text = "Requesting…"
    Thread {
     val result = try {
      val conn = java.net.URL("https://api.github.com/zen").openConnection() as javax.net.ssl.HttpsURLConnection
      conn.connectTimeout = 10_000
      conn.readTimeout = 10_000
      val code = conn.responseCode
      val body = conn.inputStream.bufferedReader().readText()
      conn.disconnect()
      "GET https://api.github.com/zen -> $code: $body"
     } catch (e: Exception) {
      "GET https://api.github.com/zen FAILED: ${e.javaClass.simpleName}: ${e.message}"
     }
     runOnUiThread { status.text = result }
    }.start()
   }
  }
  setContentView(LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL
   addView(button)
   addView(status)
  })
 }
}
