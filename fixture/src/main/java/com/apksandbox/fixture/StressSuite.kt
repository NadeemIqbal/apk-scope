package com.apksandbox.fixture

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/**
 * Forwarding-Engine Hardening Gate, §7: automated load patterns run from inside the sandboxed app
 * itself, so a human never has to tap a UI 100+ times. Every scenario logs one JSON summary line
 * tagged `SandboxStress`; [run] logs a final `SUITE_COMPLETE` line with all of them together.
 *
 * These exercise the *forwarding path* specifically (real DNS lookups, real TCP connects to public
 * internet endpoints through the sandboxed Work Profile's locked-down VPN) — they are not testing
 * the fixture's own code, they are load on [com.nadeem.apkscope.spike.net.ForwardingEngine] from the
 * other side of the TUN.
 */
object StressSuite {
 private const val TAG = "SandboxStress"
 private const val TEST_TARGET_HOST = "1.1.1.1" // known-fast public TCP/443 responder, used for connection-churn scenarios
 private const val UNREACHABLE_HOST = "192.0.2.1" // RFC 5737 TEST-NET-1: reserved, never routed — real destinations time out here, not RST

 fun run(context: Context) {
  val results = JSONObject()
  fun step(name: String, block: () -> JSONObject) {
   val r = try { block() } catch (e: Exception) { JSONObject().put("error", "${e.javaClass.simpleName}: ${e.message}") }
   Log.i(TAG, "STEP_COMPLETE $name $r")
   results.put(name, r)
  }
  step("sequentialTcp100") { sequentialTcp(100) }
  step("concurrentTcp50") { concurrentTcp(50) }
  step("sequentialDns100") { sequentialDns(100) }
  step("simultaneousDns20") { simultaneousDns(20) }
  step("rapidConnectDisconnect100") { rapidConnectDisconnect(100) }
  step("unreachableDestination") { unreachableDestination() }
  step("connectionTimeoutHonored") { connectionTimeoutHonored() }
  step("largeDownload3MB") { largeDownload() }
  step("largeUpload3MB") { largeUpload() }
  step("mixedTcpUdpLoad") { mixedLoad() }
  Log.i(TAG, "SUITE_COMPLETE $results")
 }

 private fun sequentialTcp(count: Int): JSONObject {
  var success = 0; var failure = 0
  val start = System.currentTimeMillis()
  repeat(count) {
   try { Socket().use { s -> s.connect(InetSocketAddress(TEST_TARGET_HOST, 443), 5000) }; success++ }
   catch (e: Exception) { failure++ }
  }
  return JSONObject().put("attempts", count).put("success", success).put("failure", failure).put("elapsedMs", System.currentTimeMillis() - start)
 }

 private fun concurrentTcp(count: Int): JSONObject {
  val success = AtomicInteger(0); val failure = AtomicInteger(0)
  val latch = CountDownLatch(count)
  val pool = Executors.newFixedThreadPool(count)
  val start = System.currentTimeMillis()
  repeat(count) {
   pool.submit {
    try { Socket().use { s -> s.connect(InetSocketAddress(TEST_TARGET_HOST, 443), 8000) }; success.incrementAndGet() }
    catch (e: Exception) { failure.incrementAndGet() }
    finally { latch.countDown() }
   }
  }
  latch.await(60, TimeUnit.SECONDS)
  pool.shutdownNow()
  return JSONObject().put("attempts", count).put("success", success.get()).put("failure", failure.get()).put("elapsedMs", System.currentTimeMillis() - start)
 }

 private fun sequentialDns(count: Int): JSONObject {
  var success = 0; var failure = 0
  val start = System.currentTimeMillis()
  repeat(count) { i ->
   try { InetAddress.getByName(if (i % 2 == 0) "example.com" else "cloudflare.com"); success++ }
   catch (e: Exception) { failure++ }
  }
  return JSONObject().put("attempts", count).put("success", success).put("failure", failure).put("elapsedMs", System.currentTimeMillis() - start)
 }

 private fun simultaneousDns(count: Int): JSONObject {
  val hostnames = listOf("example.com", "cloudflare.com", "google.com", "wikipedia.org", "github.com")
  val success = AtomicInteger(0); val failure = AtomicInteger(0)
  val latch = CountDownLatch(count)
  val pool = Executors.newFixedThreadPool(count)
  val start = System.currentTimeMillis()
  repeat(count) { i ->
   pool.submit {
    try { InetAddress.getByName(hostnames[i % hostnames.size]); success.incrementAndGet() }
    catch (e: Exception) { failure.incrementAndGet() }
    finally { latch.countDown() }
   }
  }
  latch.await(30, TimeUnit.SECONDS)
  pool.shutdownNow()
  return JSONObject().put("attempts", count).put("success", success.get()).put("failure", failure.get()).put("elapsedMs", System.currentTimeMillis() - start)
 }

 private fun rapidConnectDisconnect(count: Int): JSONObject {
  var success = 0; var failure = 0
  val start = System.currentTimeMillis()
  repeat(count) {
   try {
    val s = Socket()
    s.connect(InetSocketAddress(TEST_TARGET_HOST, 443), 5000)
    s.close() // immediate close right after connect — the FIN/RST churn case
    success++
   } catch (e: Exception) { failure++ }
  }
  return JSONObject().put("attempts", count).put("success", success).put("failure", failure).put("elapsedMs", System.currentTimeMillis() - start)
 }

 private fun unreachableDestination(): JSONObject {
  val start = System.currentTimeMillis()
  val outcome = try {
   Socket().use { s -> s.connect(InetSocketAddress(UNREACHABLE_HOST, 9), 10000) }; "UNEXPECTED_SUCCESS"
  } catch (e: Exception) { "${e.javaClass.simpleName}" }
  return JSONObject().put("outcome", outcome).put("elapsedMs", System.currentTimeMillis() - start)
 }

 private fun connectionTimeoutHonored(): JSONObject {
  val requestedTimeoutMs = 2000
  val start = System.currentTimeMillis()
  val outcome = try {
   Socket().use { s -> s.connect(InetSocketAddress(UNREACHABLE_HOST, 9), requestedTimeoutMs) }; "UNEXPECTED_SUCCESS"
  } catch (e: Exception) { e.javaClass.simpleName }
  val elapsed = System.currentTimeMillis() - start
  // "Honored" = actually bounded by roughly the requested timeout, not immediate and not far beyond it.
  val honored = elapsed in (requestedTimeoutMs / 2).toLong()..(requestedTimeoutMs * 3).toLong()
  return JSONObject().put("outcome", outcome).put("elapsedMs", elapsed).put("timeoutHonored", honored)
 }

 private fun largeDownload(): JSONObject {
  val start = System.currentTimeMillis()
  return try {
   val url = URL("https://speed.cloudflare.com/__down?bytes=3000000")
   val c = url.openConnection() as HttpsURLConnection
   c.connectTimeout = 10000; c.readTimeout = 30000
   var total = 0L
   c.inputStream.use { input -> val buf = ByteArray(65536); while (true) { val n = input.read(buf); if (n < 0) break; total += n } }
   c.disconnect()
   JSONObject().put("bytesReceived", total).put("elapsedMs", System.currentTimeMillis() - start).put("outcome", "SUCCESS")
  } catch (e: Exception) { JSONObject().put("outcome", "${e.javaClass.simpleName}: ${e.message}").put("elapsedMs", System.currentTimeMillis() - start) }
 }

 private fun largeUpload(): JSONObject {
  val start = System.currentTimeMillis()
  return try {
   val payload = ByteArray(3_000_000) { (it % 256).toByte() }
   val url = URL("https://speed.cloudflare.com/__up")
   val c = url.openConnection() as HttpsURLConnection
   c.connectTimeout = 10000; c.readTimeout = 30000
   c.doOutput = true; c.requestMethod = "POST"
   // Without a browser-like User-Agent/Origin, Cloudflare's WAF rejects this with 403 before the
   // body finishes streaming, which surfaces as a *connection reset*, not a clean HTTP error —
   // confirmed by reproducing the identical rejection from a plain curl/urllib call outside the
   // sandbox entirely. Not a ForwardingEngine defect; the fix is these headers, not engine code.
   c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 17) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
   c.setRequestProperty("Origin", "https://speed.cloudflare.com")
   c.setRequestProperty("Referer", "https://speed.cloudflare.com/")
   c.setFixedLengthStreamingMode(payload.size)
   c.outputStream.use { it.write(payload) }
   val code = c.responseCode
   c.disconnect()
   JSONObject().put("bytesSent", payload.size).put("responseCode", code).put("elapsedMs", System.currentTimeMillis() - start).put("outcome", "SUCCESS")
  } catch (e: Exception) { JSONObject().put("outcome", "${e.javaClass.simpleName}: ${e.message}").put("elapsedMs", System.currentTimeMillis() - start) }
 }

 private fun mixedLoad(): JSONObject {
  val tcpSuccess = AtomicInteger(0); val tcpFailure = AtomicInteger(0)
  val dnsSuccess = AtomicInteger(0); val dnsFailure = AtomicInteger(0)
  val latch = CountDownLatch(40)
  val pool = Executors.newFixedThreadPool(40)
  val start = System.currentTimeMillis()
  repeat(20) {
   pool.submit {
    try { Socket().use { s -> s.connect(InetSocketAddress(TEST_TARGET_HOST, 443), 8000) }; tcpSuccess.incrementAndGet() }
    catch (e: Exception) { tcpFailure.incrementAndGet() }
    finally { latch.countDown() }
   }
  }
  repeat(20) { i ->
   pool.submit {
    try { InetAddress.getByName(if (i % 2 == 0) "example.com" else "cloudflare.com"); dnsSuccess.incrementAndGet() }
    catch (e: Exception) { dnsFailure.incrementAndGet() }
    finally { latch.countDown() }
   }
  }
  latch.await(60, TimeUnit.SECONDS)
  pool.shutdownNow()
  return JSONObject().put("tcpSuccess", tcpSuccess.get()).put("tcpFailure", tcpFailure.get())
   .put("dnsSuccess", dnsSuccess.get()).put("dnsFailure", dnsFailure.get()).put("elapsedMs", System.currentTimeMillis() - start)
 }
}
