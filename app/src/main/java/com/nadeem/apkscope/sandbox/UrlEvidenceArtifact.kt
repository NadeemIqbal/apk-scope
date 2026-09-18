package com.nadeem.apkscope.sandbox

import org.json.JSONArray
import org.json.JSONObject

/**
 * Checkpoint 8.9: Bounded URL evidence transport for exact-URL correlation.
 *
 * Carries only the minimum fields necessary to correlate embedded URLs with captured traffic:
 * - Analysis linking (sessionId, analysisTarget package name)
 * - Traffic linking (trafficTransactionId, sequence)
 * - Request URL (redacted of sensitive query values)
 * - Timing and capture status
 *
 * No headers, bodies, or full TrafficRecord data. Designed to fit under the 10MB artifact limit
 * and be imported into a persisted analysis for exact-URL-match correlation.
 *
 * Single-purpose: URL evidence only. DNS, connections, and other observations stay in
 * RuntimeObservationArtifact (Work profile local). This artifact bridges Work→Personal for
 * URL matching against DexUrlCandidate entries.
 */
data class UrlEvidenceEntry(
    val sequence: Long,                    // Sequence within this artifact
    val analysisSessionId: String,         // Links to the analysis session ID
    val analysisTargetPackage: String,     // Package being analyzed (for scope validation)
    val trafficTransactionId: String,      // ID of the actual captured TrafficRecord (in Work)
    val trafficSequence: Long,             // Sequence of the transaction in the capture
    val requestUrl: String,                // Redacted: no auth tokens, API keys, or sensitive query params
    val requestMethod: String?,            // GET, POST, etc. — optional but helpful
    val responseStatusCode: Int?,           // 200, 404, etc. — optional
    val timestampEpochMs: Long,            // When the request was captured
    val captureStatus: String,             // "COMPLETE" or "TRUNCATED" — if URL was redacted/truncated
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sequence", sequence)
        put("analysisSessionId", analysisSessionId)
        put("analysisTargetPackage", analysisTargetPackage)
        put("trafficTransactionId", trafficTransactionId)
        put("trafficSequence", trafficSequence)
        put("requestUrl", requestUrl)
        requestMethod?.let { put("requestMethod", it) }
        responseStatusCode?.let { put("responseStatusCode", it) }
        put("timestampEpochMs", timestampEpochMs)
        put("captureStatus", captureStatus)
    }

    companion object {
        fun fromJson(j: JSONObject): UrlEvidenceEntry = UrlEvidenceEntry(
            sequence = j.getLong("sequence"),
            analysisSessionId = j.getString("analysisSessionId"),
            analysisTargetPackage = j.getString("analysisTargetPackage"),
            trafficTransactionId = j.getString("trafficTransactionId"),
            trafficSequence = j.getLong("trafficSequence"),
            requestUrl = j.getString("requestUrl"),
            requestMethod = j.optString("requestMethod", null).takeIf { it != "null" && it.isNotEmpty() },
            responseStatusCode = j.optInt("responseStatusCode", -1).takeIf { it != -1 },
            timestampEpochMs = j.getLong("timestampEpochMs"),
            captureStatus = j.getString("captureStatus"),
        )
    }
}

data class UrlEvidenceArtifact(
    val schemaVersion: Int,
    val analysisSessionId: String,
    val analysisTargetPackage: String,
    val capturedAtEpochMs: Long,
    val exportedAtEpochMs: Long,
    val entries: List<UrlEvidenceEntry>,
    /** true when [entries] was truncated due to size limits — summary below covers the full set */
    val truncated: Boolean,
    val exportedEntryCount: Int,
    val totalEntryCount: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("analysisSessionId", analysisSessionId)
        put("analysisTargetPackage", analysisTargetPackage)
        put("capturedAtEpochMs", capturedAtEpochMs)
        put("exportedAtEpochMs", exportedAtEpochMs)
        put("truncated", truncated)
        put("exportedEntryCount", exportedEntryCount)
        put("totalEntryCount", totalEntryCount)
        put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } })
    }

    companion object {
        const val SCHEMA_VERSION = 1
        /** Bounded transport limit: much smaller than RuntimeObservationArtifact (10MB),
            focused on URL entries only. Set to 1MB to allow safe margin below transport. */
        const val MAX_ARTIFACT_BYTES = 1L * 1024 * 1024
        /** Maximum URL entries to export per artifact. Beyond this, truncate and report counts. */
        const val MAX_ENTRIES = 5000

        fun fromJson(json: JSONObject): UrlEvidenceArtifact {
            val analysisSessionId = json.getString("analysisSessionId")
            val analysisTargetPackage = json.getString("analysisTargetPackage")
            val capturedAtEpochMs = json.getLong("capturedAtEpochMs")
            val exportedAtEpochMs = json.getLong("exportedAtEpochMs")
            val entriesJson = json.getJSONArray("entries")
            val entries = (0 until entriesJson.length()).map { UrlEvidenceEntry.fromJson(entriesJson.getJSONObject(it)) }
            return UrlEvidenceArtifact(
                schemaVersion = json.getInt("schemaVersion"),
                analysisSessionId = analysisSessionId,
                analysisTargetPackage = analysisTargetPackage,
                capturedAtEpochMs = capturedAtEpochMs,
                exportedAtEpochMs = exportedAtEpochMs,
                entries = entries,
                truncated = json.getBoolean("truncated"),
                exportedEntryCount = json.getInt("exportedEntryCount"),
                totalEntryCount = json.getInt("totalEntryCount"),
            )
        }

        /**
         * Validate the artifact before trusting its contents.
         * Returns an error message if validation fails, null if valid.
         */
        fun validate(artifact: UrlEvidenceArtifact, expectedSessionId: String, expectedPackage: String): String? {
            if (artifact.schemaVersion != SCHEMA_VERSION) return "unsupported schemaVersion=${artifact.schemaVersion}"
            if (artifact.analysisSessionId.isEmpty() || artifact.analysisSessionId != expectedSessionId) {
                return "analysisSessionId mismatch: expected=$expectedSessionId actual=${artifact.analysisSessionId}"
            }
            if (artifact.analysisTargetPackage.isEmpty() || (expectedPackage.isNotEmpty() && artifact.analysisTargetPackage != expectedPackage)) {
                return "analysisTargetPackage mismatch: expected=$expectedPackage actual=${artifact.analysisTargetPackage}"
            }
            if (artifact.exportedAtEpochMs < artifact.capturedAtEpochMs) return "exportedAt precedes capturedAt"
            if (artifact.exportedEntryCount != artifact.entries.size) return "exportedEntryCount does not match entries.size"
            if (artifact.totalEntryCount < artifact.exportedEntryCount) return "totalEntryCount < exportedEntryCount"
            if (!artifact.truncated && artifact.totalEntryCount != artifact.exportedEntryCount) {
                return "declared not truncated but totalEntryCount != exportedEntryCount"
            }
            if (artifact.entries.isEmpty() && artifact.totalEntryCount > 0) return "no entries but totalEntryCount > 0"
            return null
        }
    }
}
