package com.apksandbox.fixture

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
 * Forwarding Reliability Root Cause Gate, item 3: composable named workloads so scenarios A–F can
 * each run "some prior load, then a measured 20-concurrent-TCP batch" against a *fresh* engine,
 * triggered headlessly (`adb shell am start ... --es runWorkloads a,b,c`) without UI automation.
 * The last workload in the list is always the one whose result is logged as `MEASURED_BATCH` —
 * every scenario's list ends with `concurrentTcp20` per the gate's design.
 */
object RootCauseSuite {
 private const val TAG = "SandboxRootCause"
 private const val TARGET_HOST = "1.1.1.1"

 fun run(workloadsCsv: String) {
  val names = workloadsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
  Log.i(TAG, "BATCH_STARTED workloads=$names")
  var last: JSONObject? = null
  for (name in names) {
   val result = try { runOne(name) } catch (e: Exception) { JSONObject().put("error", "${e.javaClass.simpleName}: ${e.message}") }
   Log.i(TAG, "WORKLOAD_COMPLETE $name $result")
   last = result
  }
  Log.i(TAG, "MEASURED_BATCH ${names.lastOrNull()} $last")
  Log.i(TAG, "BATCH_COMPLETE")
 }

 private fun runOne(name: String): JSONObject = when (name) {
  "multiUpload" -> multiEndpointUpload()
  "concurrentTcp20" -> concurrentTcp(20, 8000)
  "sequentialTcp100" -> sequentialTcp(100)
  "sequentialDns100" -> sequentialDns(100)
  "rapidConnectDisconnect100" -> rapidConnectDisconnect(100)
  "download3MB" -> download()
  // Upload Throughput Root Cause Gate item 10's required regression matrix:
  "upload3MBx10" -> repeatedUpload3MB(10)
  "download3MBx5" -> repeatedDownload3MB(5)
  "concurrentTcp20x5" -> repeatedConcurrentTcp20(5)
  "mixedTcpDnsx5" -> repeatedMixedTcpDns(5)
  else -> JSONObject().put("error", "unknown workload: $name")
 }

 private fun repeatedUpload3MB(times: Int): JSONObject {
  val trials = org.json.JSONArray()
  var successCount = 0
  repeat(times) { i ->
   val start = System.currentTimeMillis()
   val trial = JSONObject().put("trial", i + 1)
   try {
    val payload = ByteArray(3_000_000) { (it % 256).toByte() }
    val c = URL("https://speed.cloudflare.com/__up").openConnection() as HttpsURLConnection
    c.connectTimeout = 15000; c.readTimeout = 30000
    c.doOutput = true; c.requestMethod = "POST"
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 17) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
    c.setRequestProperty("Content-Type", "application/octet-stream")
    c.setFixedLengthStreamingMode(payload.size)
    c.outputStream.use { it.write(payload) }
    val code = c.responseCode
    c.disconnect()
    trial.put("outcome", "SUCCESS").put("responseCode", code).put("elapsedMs", System.currentTimeMillis() - start)
    if (code in 200..299) successCount++
   } catch (e: Exception) { trial.put("outcome", "${e.javaClass.simpleName}: ${e.message}").put("elapsedMs", System.currentTimeMillis() - start) }
   Log.i(TAG, "UPLOAD3MB_TRIAL $trial")
   trials.put(trial)
  }
  return JSONObject().put("attempts", times).put("success", successCount).put("trials", trials)
 }

 private fun repeatedDownload3MB(times: Int): JSONObject {
  val trials = org.json.JSONArray()
  var successCount = 0
  repeat(times) { i ->
   val result = download()
   if (result.optString("outcome") == "SUCCESS") successCount++
   Log.i(TAG, "DOWNLOAD3MB_TRIAL trial=${i + 1} $result")
   trials.put(result)
  }
  return JSONObject().put("attempts", times).put("success", successCount).put("trials", trials)
 }

 private fun repeatedConcurrentTcp20(times: Int): JSONObject {
  val trials = org.json.JSONArray()
  repeat(times) { i ->
   val result = concurrentTcp(20, 8000)
   Log.i(TAG, "CONCURRENT_TCP20_TRIAL trial=${i + 1} $result")
   trials.put(result)
  }
  return JSONObject().put("rounds", times).put("trials", trials)
 }

 /** One round: 20 concurrent TCP connects running at the same time as 20 DNS lookups, on independent threads — exercises both flows through the engine simultaneously rather than sequentially. */
 private fun repeatedMixedTcpDns(times: Int): JSONObject {
  val trials = org.json.JSONArray()
  repeat(times) { i ->
   var tcpResult: JSONObject? = null
   var dnsResult: JSONObject? = null
   val tcpThread = Thread { tcpResult = concurrentTcp(20, 8000) }
   val dnsThread = Thread { dnsResult = sequentialDns(20) }
   tcpThread.start(); dnsThread.start()
   tcpThread.join(65_000); dnsThread.join(65_000)
   val round = JSONObject().put("trial", i + 1).put("tcp", tcpResult).put("dns", dnsResult)
   Log.i(TAG, "MIXED_TCP_DNS_TRIAL $round")
   trials.put(round)
  }
  return JSONObject().put("rounds", times).put("trials", trials)
 }

 private fun sequentialTcp(count: Int): JSONObject {
  var success = 0; var failure = 0
  val start = System.currentTimeMillis()
  repeat(count) {
   try { Socket().use { s -> s.connect(InetSocketAddress(TARGET_HOST, 443), 5000) }; success++ } catch (e: Exception) { failure++ }
  }
  return JSONObject().put("attempts", count).put("success", success).put("failure", failure).put("elapsedMs", System.currentTimeMillis() - start)
 }

 private fun concurrentTcp(count: Int, timeoutMs: Int): JSONObject {
  val success = AtomicInteger(0); val failure = AtomicInteger(0)
  val latch = CountDownLatch(count)
  val pool = Executors.newFixedThreadPool(count)
  val start = System.currentTimeMillis()
  repeat(count) {
   pool.submit {
    try { Socket().use { s -> s.connect(InetSocketAddress(TARGET_HOST, 443), timeoutMs) }; success.incrementAndGet() }
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
   try { InetAddress.getByName(if (i % 2 == 0) "example.com" else "cloudflare.com"); success++ } catch (e: Exception) { failure++ }
  }
  return JSONObject().put("attempts", count).put("success", success).put("failure", failure).put("elapsedMs", System.currentTimeMillis() - start)
 }

 private fun rapidConnectDisconnect(count: Int): JSONObject {
  var success = 0; var failure = 0
  val start = System.currentTimeMillis()
  repeat(count) {
   try { val s = Socket(); s.connect(InetSocketAddress(TARGET_HOST, 443), 5000); s.close(); success++ } catch (e: Exception) { failure++ }
  }
  return JSONObject().put("attempts", count).put("success", success).put("failure", failure).put("elapsedMs", System.currentTimeMillis() - start)
 }

 /**
  * Root-cause gate item 6: don't blame Cloudflare just because the failing IP belongs to
  * Cloudflare. Uploads the same 3MB payload to two independent providers, three times each in
  * sequence, recording the resolved IP and outcome of every single trial — enough to see whether
  * failure correlates with one specific provider/IP, or shows up everywhere regardless of which
  * "specific destination" is involved.
  */
 private fun multiEndpointUpload(): JSONObject {
  val endpoints = listOf(
   "https://speed.cloudflare.com/__up" to "speed.cloudflare.com",
   "https://httpbin.org/post" to "httpbin.org",
  )
  val trials = org.json.JSONArray()
  for ((url, host) in endpoints) {
   repeat(3) { attempt ->
    val start = System.currentTimeMillis()
    val resolvedIp = try { InetAddress.getByName(host).hostAddress } catch (e: Exception) { "resolve-failed: ${e.javaClass.simpleName}" }
    val trial = JSONObject().put("url", url).put("attempt", attempt + 1).put("resolvedIp", resolvedIp)
    try {
     val payload = ByteArray(3_000_000) { (it % 256).toByte() }
     val c = URL(url).openConnection() as HttpsURLConnection
     c.connectTimeout = 15000; c.readTimeout = 30000
     c.doOutput = true; c.requestMethod = "POST"
     c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 17) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
     c.setRequestProperty("Content-Type", "application/octet-stream")
     c.setFixedLengthStreamingMode(payload.size)
     c.outputStream.use { it.write(payload) }
     val code = c.responseCode
     c.disconnect()
     trial.put("outcome", "SUCCESS").put("responseCode", code).put("elapsedMs", System.currentTimeMillis() - start)
    } catch (e: Exception) {
     trial.put("outcome", "${e.javaClass.simpleName}: ${e.message}").put("elapsedMs", System.currentTimeMillis() - start)
    }
    Log.i(TAG, "MULTI_UPLOAD_TRIAL $trial")
    trials.put(trial)
   }
  }
  return JSONObject().put("trials", trials)
 }

 private fun download(): JSONObject {
  val start = System.currentTimeMillis()
  return try {
   val c = URL("https://speed.cloudflare.com/__down?bytes=3000000").openConnection() as HttpsURLConnection
   c.connectTimeout = 10000; c.readTimeout = 30000
   var total = 0L
   c.inputStream.use { input -> val buf = ByteArray(65536); while (true) { val n = input.read(buf); if (n < 0) break; total += n } }
   c.disconnect()
   JSONObject().put("bytesReceived", total).put("elapsedMs", System.currentTimeMillis() - start).put("outcome", "SUCCESS")
  } catch (e: Exception) { JSONObject().put("outcome", "${e.javaClass.simpleName}: ${e.message}").put("elapsedMs", System.currentTimeMillis() - start) }
 }
}
