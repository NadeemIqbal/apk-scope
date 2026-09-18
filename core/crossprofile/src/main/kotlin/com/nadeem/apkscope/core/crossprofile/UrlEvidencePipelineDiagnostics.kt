package com.nadeem.apkscope.core.crossprofile

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/**
 * Milestone 9 (Pixel 8 acceptance, fifth pass, item 2): narrowly-scoped, durable, cross-process
 * diagnostics for the URL-evidence import pipeline specifically — added after a genuinely completed
 * physical-device session left no durable trace of why its URL-evidence import never produced an
 * outcome, and `logcat` alone (on a real, actively-used device) proved too unreliable to reconstruct
 * what happened after the fact.
 *
 * Each stage below is recorded on whichever side (Personal or Work) it actually runs on, into that
 * process's own `files/url_evidence_pipeline_diagnostics.jsonl` — this never attempts to write
 * across the profile boundary itself, only into the calling process's own storage, the same as every
 * other durable store in this codebase. A single [operationId] (a fresh id minted once per import
 * attempt) is threaded through every stage on both sides via the existing cross-profile query
 * extras, so the two independently-collected files can be correlated afterward even though they
 * live in separate processes/profiles with separate `logcat` buffers.
 *
 * Deliberately narrow: records only stage names, counts, and short boolean/string outcomes — never a
 * captured URL, header, body, token, or any other payload content (matching this codebase's own
 * `HandoffDiagnostics` discipline of "never the APK's own contents"). Bounded: each write truncates
 * the file to the most recent [MAX_LINES] entries, so it never grows unbounded across a long-lived
 * process — the same shape [Evidence] already uses for its own durable log, extended with a size cap
 * since this one is expected to accumulate across many more operations over a session's lifetime.
 */
object UrlEvidencePipelineDiagnostics {
    private const val TAG = "UrlEvidencePipeline"
    private const val FILE_NAME = "url_evidence_pipeline_diagnostics.jsonl"
    private const val MAX_LINES = 500

    private data class Row(val context: Context, val line: String)
    private val queue = LinkedBlockingQueue<Row>()
    private val writer = Thread({
        while (true) {
            val row = queue.take()
            try {
                val file = File(row.context.filesDir, FILE_NAME)
                val existing = if (file.exists()) file.readLines() else emptyList()
                val updated = (existing + row.line).takeLast(MAX_LINES)
                file.writeText(updated.joinToString(separator = "\n", postfix = "\n"))
            } catch (_: Throwable) {
                // Best-effort durability, matching every other store in this codebase's own
                // tolerance (Evidence, StaticAnalysisResultStore) — a failure to persist a
                // diagnostic line must never throw back into the real import/export flow it is
                // only ever observing.
            }
        }
    }, "url-evidence-pipeline-diagnostics-writer").apply { isDaemon = true; start() }

    /**
     * [profile] is `"personal"` or `"work"`, recorded explicitly rather than inferred from
     * `DevicePolicyManager.isProfileOwnerApp` at the call site — every caller already knows which
     * side it runs on, and inferring it wrong here would silently mislabel every line from that side.
     */
    fun record(context: Context, operationId: String, profile: String, sessionId: String, stage: String, detail: String = "") {
        val line = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("op", operationId)
            .put("profile", profile)
            .put("sessionId", sessionId)
            .put("stage", stage)
            .put("detail", detail)
            .toString()
        android.util.Log.i(TAG, line) // cheap, immediate — but never the only copy; see the durable write below.
        queue.put(Row(context.applicationContext, line))
    }

    /**
     * Reads back this process's own recorded lines, optionally filtered to one [operationId] — used
     * by verification and by any future in-app diagnostics surface, never shown to an end user as
     * raw text. Returns lines oldest-first.
     */
    fun read(context: Context, operationId: String? = null): List<String> {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        val lines = try { file.readLines() } catch (_: Throwable) { return emptyList() }
        return if (operationId == null) lines else lines.filter { it.contains("\"op\":\"$operationId\"") }
    }
}
