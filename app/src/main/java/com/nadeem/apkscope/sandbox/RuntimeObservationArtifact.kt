package com.nadeem.apkscope.sandbox

import com.nadeem.apkscope.core.model.RuntimeObservationSummary
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Checkpoint 5, item 19/20/21: the wire/validation shape for the runtime-observation artifact —
 * transferred Work→Personal via the exact same `FileProvider` + temporary URI-grant pull mechanism
 * checkpoint 4.1 already validated for [WorkEvidenceEnvelope] (see
 * [SandboxWorkQueryActivity]'s doc comment), just with a separate, larger size bound (item 20 —
 * this checkpoint's own 64KB evidence limit is intentionally too small for a real multi-connection
 * session). Pure and Android-free, like [WorkEvidenceEnvelope], so its parsing/validation is
 * directly unit-testable without any Room/Activity plumbing around it.
 *
 * [observations] is the flattened, typed per-row shape — the same fields
 * `WorkNetworkObservationEntity`/`NetworkObservationEntity` carry (this class has no Room
 * dependency of its own; [SandboxWorkQueryActivity] does the entity->entry mapping on the way out,
 * `SandboxSessionCoordinator` does entry->entity on the way in).
 */
data class RuntimeObservationEntry(
 val sequence: Long,
 val timestampEpochMs: Long,
 val type: String,
 val protocol: String?,
 val destinationIp: String?,
 val destinationPort: Int?,
 val connectionId: Long?,
 val startTimeEpochMs: Long?,
 val endTimeEpochMs: Long?,
 val uploadedBytes: Long?,
 val downloadedBytes: Long?,
 val failureReason: String?,
 val failureDetail: String?,
 val hostname: String?,
 val resolvedAddressesCsv: String?,
 val transactionId: Int?,
 val sourcePort: Int?,
 val limitName: String?,
 val currentValue: Long?,
 val limitValue: Long?,
) {
 fun toJson(): JSONObject = JSONObject().apply {
  put("sequence", sequence); put("timestampEpochMs", timestampEpochMs); put("type", type)
  protocol?.let { put("protocol", it) }
  destinationIp?.let { put("destinationIp", it) }
  destinationPort?.let { put("destinationPort", it) }
  connectionId?.let { put("connectionId", it) }
  startTimeEpochMs?.let { put("startTimeEpochMs", it) }
  endTimeEpochMs?.let { put("endTimeEpochMs", it) }
  uploadedBytes?.let { put("uploadedBytes", it) }
  downloadedBytes?.let { put("downloadedBytes", it) }
  failureReason?.let { put("failureReason", it) }
  failureDetail?.let { put("failureDetail", it) }
  hostname?.let { put("hostname", it) }
  resolvedAddressesCsv?.let { put("resolvedAddressesCsv", it) }
  transactionId?.let { put("transactionId", it) }
  sourcePort?.let { put("sourcePort", it) }
  limitName?.let { put("limitName", it) }
  currentValue?.let { put("currentValue", it) }
  limitValue?.let { put("limitValue", it) }
 }

 companion object {
  fun fromJson(j: JSONObject): RuntimeObservationEntry = RuntimeObservationEntry(
   sequence = j.getLong("sequence"),
   timestampEpochMs = j.getLong("timestampEpochMs"),
   type = j.getString("type"),
   protocol = j.optNullableString("protocol"),
   destinationIp = j.optNullableString("destinationIp"),
   destinationPort = j.optNullableInt("destinationPort"),
   connectionId = j.optNullableLong("connectionId"),
   startTimeEpochMs = j.optNullableLong("startTimeEpochMs"),
   endTimeEpochMs = j.optNullableLong("endTimeEpochMs"),
   uploadedBytes = j.optNullableLong("uploadedBytes"),
   downloadedBytes = j.optNullableLong("downloadedBytes"),
   failureReason = j.optNullableString("failureReason"),
   failureDetail = j.optNullableString("failureDetail"),
   hostname = j.optNullableString("hostname"),
   resolvedAddressesCsv = j.optNullableString("resolvedAddressesCsv"),
   transactionId = j.optNullableInt("transactionId"),
   sourcePort = j.optNullableInt("sourcePort"),
   limitName = j.optNullableString("limitName"),
   currentValue = j.optNullableLong("currentValue"),
   limitValue = j.optNullableLong("limitValue"),
  )
 }
}

private fun JSONObject.optNullableString(key: String): String? = if (has(key)) getString(key) else null
private fun JSONObject.optNullableInt(key: String): Int? = if (has(key)) getInt(key) else null
private fun JSONObject.optNullableLong(key: String): Long? = if (has(key)) getLong(key) else null

data class RuntimeObservationArtifact(
 val schemaVersion: Int,
 val sessionId: String,
 val packageName: String,
 val startedAtEpochMs: Long,
 val endedAtEpochMs: Long,
 val summary: RuntimeObservationSummary,
 val observations: List<RuntimeObservationEntry>,
 /** Item 20: true when [observations] does not carry every row the Work-local store actually holds for this session — the summary above always covers the *whole* session regardless, computed from the full Work-local store, never re-derived from the (possibly truncated) exported rows. */
 val truncated: Boolean,
 val exportedObservationCount: Int,
 val totalObservationCount: Int,
) {
 fun toJson(): JSONObject = JSONObject().apply {
  put("schemaVersion", schemaVersion)
  put("sessionId", sessionId)
  put("packageName", packageName)
  put("startedAtEpochMs", startedAtEpochMs)
  put("endedAtEpochMs", endedAtEpochMs)
  put("truncated", truncated)
  put("exportedObservationCount", exportedObservationCount)
  put("totalObservationCount", totalObservationCount)
  put("summary", JSONObject().apply {
   put("connectionCount", summary.connectionCount)
   put("dnsQueryCount", summary.dnsQueryCount)
   put("uniqueObservedDomains", summary.uniqueObservedDomains)
   put("uploadedBytes", summary.uploadedBytes)
   put("downloadedBytes", summary.downloadedBytes)
   put("blockedConnectionCount", summary.blockedConnectionCount)
   put("failedConnectionCount", summary.failedConnectionCount)
   put("droppedObservationCount", summary.droppedObservationCount)
  })
  put("observations", JSONArray().apply { observations.forEach { put(it.toJson()) } })
 }

 companion object {
  const val SCHEMA_VERSION = 1

  /** Bytes — item 20's separate, larger runtime-artifact limit (deliberately distinct from checkpoint 4.1's 64KB evidence limit). 10MB: at roughly 150-300 bytes per typed JSON observation row, this comfortably covers tens of thousands of rows — well above item 16's 10,000-observation correctness bar — while staying a bounded, justified size for a foreground cross-profile FileProvider pull, not an arbitrary huge number. */
  const val MAX_ARTIFACT_BYTES = 10L * 1024 * 1024

  fun fromJson(json: JSONObject): RuntimeObservationArtifact {
   val sessionId = json.getString("sessionId")
   val startedAtEpochMs = json.getLong("startedAtEpochMs")
   val endedAtEpochMs = json.getLong("endedAtEpochMs")
   val summaryJson = json.getJSONObject("summary")
   val summary = RuntimeObservationSummary(
    sessionId = sessionId,
    startedAt = Instant.ofEpochMilli(startedAtEpochMs),
    endedAt = Instant.ofEpochMilli(endedAtEpochMs),
    connectionCount = summaryJson.getInt("connectionCount"),
    dnsQueryCount = summaryJson.getInt("dnsQueryCount"),
    uniqueObservedDomains = summaryJson.getInt("uniqueObservedDomains"),
    uploadedBytes = summaryJson.getLong("uploadedBytes"),
    downloadedBytes = summaryJson.getLong("downloadedBytes"),
    blockedConnectionCount = summaryJson.getInt("blockedConnectionCount"),
    failedConnectionCount = summaryJson.getInt("failedConnectionCount"),
    droppedObservationCount = summaryJson.getLong("droppedObservationCount"),
   )
   val observationsJson = json.getJSONArray("observations")
   val observations = (0 until observationsJson.length()).map { RuntimeObservationEntry.fromJson(observationsJson.getJSONObject(it)) }
   return RuntimeObservationArtifact(
    schemaVersion = json.getInt("schemaVersion"),
    sessionId = sessionId,
    packageName = json.optString("packageName", ""),
    startedAtEpochMs = startedAtEpochMs,
    endedAtEpochMs = endedAtEpochMs,
    summary = summary,
    observations = observations,
    truncated = json.getBoolean("truncated"),
    exportedObservationCount = json.getInt("exportedObservationCount"),
    totalObservationCount = json.getInt("totalObservationCount"),
   )
  }

  /**
   * Item 21: Personal must validate before trusting anything in [artifact] — never merely because
   * it arrived via the same package's cross-profile channel. Returns a rejection reason describing
   * exactly what failed, or null once every check passes. Mirrors [WorkEvidenceEnvelope.isValidFor]'s
   * "empty expected value means not-yet-known, not a hard mismatch" convention for [expectedPackageName].
   */
  fun validate(artifact: RuntimeObservationArtifact, expectedSessionId: String, expectedPackageName: String): String? {
   if (artifact.schemaVersion != SCHEMA_VERSION) return "unsupported schemaVersion=${artifact.schemaVersion}"
   if (artifact.sessionId.isEmpty() || artifact.sessionId != expectedSessionId) return "sessionId mismatch: expected=$expectedSessionId actual=${artifact.sessionId}"
   if (artifact.packageName.isNotEmpty() && expectedPackageName.isNotEmpty() && artifact.packageName != expectedPackageName) {
    return "packageName mismatch: expected=$expectedPackageName actual=${artifact.packageName}"
   }
   if (artifact.endedAtEpochMs < artifact.startedAtEpochMs) return "endedAt precedes startedAt"
   if (artifact.exportedObservationCount != artifact.observations.size) return "exportedObservationCount does not match observations.size"
   if (artifact.totalObservationCount < artifact.exportedObservationCount) return "totalObservationCount < exportedObservationCount"
   if (!artifact.truncated && artifact.totalObservationCount != artifact.exportedObservationCount) return "declared not truncated but totalObservationCount != exportedObservationCount"
   val s = artifact.summary
   if (s.connectionCount < 0 || s.dnsQueryCount < 0 || s.uniqueObservedDomains < 0 || s.uploadedBytes < 0 || s.downloadedBytes < 0 ||
    s.blockedConnectionCount < 0 || s.failedConnectionCount < 0 || s.droppedObservationCount < 0) return "negative count/byte field in summary"
   return null
  }
 }
}
