package com.nadeem.apkscope.core.report

import android.content.Context
import android.util.Log
import com.nadeem.apkscope.core.model.NetworkObservation
import com.nadeem.apkscope.core.model.NetworkObservationSink
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue

/**
 * The sandbox's actual network-audit feed — every [NetworkObservation] the forwarding engine
 * emits, appended as one JSON line to `network.jsonl`. Promoted unchanged from the spike's
 * `com.nadeem.apkscope.spike.NetworkObservationLog`.
 *
 * `onObservation()` used to open/write/close the file synchronously on whichever thread called
 * it — including the engine's selector thread (every `ConnectionClosed` from `sweepIdle()`) and
 * reader thread (every `ConnectionOpened` from a fresh SYN). Root-caused as a likely contributor
 * to the Forwarding Reliability Root Cause Gate's selector-stall findings — see
 * `HARDENING_GATE_ROOT_CAUSE.md`. Fixed with a dedicated writer thread that owns the actual file
 * I/O; `onObservation()` only enqueues and returns.
 */
class NetworkObservationLog(context: Context) : NetworkObservationSink {
 private val appContext = context.applicationContext
 private val queue = LinkedBlockingQueue<String>()
 private val writer = Thread({
  while (true) {
   val row = queue.take()
   try { appContext.openFileOutput("network.jsonl", Context.MODE_APPEND).bufferedWriter().use { it.appendLine(row) } }
   catch (e: Exception) { Log.w("ApkScopeNet", "NetworkObservationLog write failed: $e") }
  }
 }, "network-observation-writer").apply { isDaemon = true; start() }

 override fun onObservation(observation: NetworkObservation) {
  val row = toJson(observation).toString()
  Log.i("ApkScopeNet", row) // async ring-buffer write, cheap enough for the hot path directly
  queue.put(row)
 }

 private fun toJson(o: NetworkObservation): JSONObject {
  val j = JSONObject().put("timestamp", o.timestamp.toString()).put("type", o::class.simpleName)
  when (o) {
   is NetworkObservation.ConnectionOpened -> j.put("connectionId", o.connectionId).put("protocol", o.protocol.name)
    .put("destinationIp", o.destinationIp).put("destinationPort", o.destinationPort)
   is NetworkObservation.ConnectionClosed -> j.put("connectionId", o.connectionId).put("protocol", o.protocol.name)
    .put("destinationIp", o.destinationIp).put("destinationPort", o.destinationPort)
    .put("startTime", o.startTime.toString()).put("endTime", o.endTime.toString())
    .put("uploadedBytes", o.uploadedBytes).put("downloadedBytes", o.downloadedBytes)
   is NetworkObservation.ConnectionFailed -> j.put("protocol", o.protocol.name).put("destinationIp", o.destinationIp)
    .put("destinationPort", o.destinationPort).put("reason", o.reason.name).put("detail", o.detail)
   is NetworkObservation.DnsQuery -> j.put("transactionId", o.transactionId).put("hostname", o.hostname).put("sourcePort", o.sourcePort)
   is NetworkObservation.DnsResponse -> j.put("transactionId", o.transactionId).put("hostname", o.hostname)
    .put("resolvedAddresses", JSONArray(o.resolvedAddresses)).put("sourcePort", o.sourcePort)
   is NetworkObservation.ResourceLimitExceeded -> j.put("limitName", o.limitName).put("currentValue", o.currentValue).put("limitValue", o.limitValue)
  }
  return j
 }
}
