package com.nadeem.apkscope.spike

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue

/**
 * `record()` used to open, write, and close `gate.jsonl` synchronously on whatever thread called
 * it — including, transitively, [com.nadeem.apkscope.core.network.ForwardingEngine]'s selector thread
 * via `TcpProxy.slowConnect`/`connectTimeout` evidence during `sweepIdle()`. That is a full file-open
 * syscall sequence on the same thread responsible for servicing every connection's readiness
 * events. Root-caused as a likely contributor to the Forwarding Reliability Root Cause Gate's
 * selector-stall findings — see `HARDENING_GATE_ROOT_CAUSE.md`. Fixed by moving the actual file
 * write to a single dedicated background thread; `record()` itself now only enqueues and returns.
 */
object Evidence {
    private data class Row(val context: Context, val json: String)
    private val queue = LinkedBlockingQueue<Row>()
    private val writer = Thread({
        while (true) {
            val row = queue.take()
            try { row.context.openFileOutput("gate.jsonl", Context.MODE_APPEND).bufferedWriter().use { it.appendLine(row.json) } }
            catch (e: Exception) { Log.w("ApkScopeSpike", "Evidence write failed: $e") }
        }
    }, "evidence-writer").apply { isDaemon = true; start() }

    fun record(context: Context, test: String, result: String, detail: String = "") {
        val row = JSONObject().put("timestamp", java.time.Instant.now().toString())
            .put("api", android.os.Build.VERSION.SDK_INT).put("test", test)
            .put("result", result).put("detail", detail).toString()
        Log.i("ApkScopeSpike", row) // Log.i is an async ring-buffer write, cheap enough for the hot path directly
        queue.put(Row(context.applicationContext, row))
    }
}
