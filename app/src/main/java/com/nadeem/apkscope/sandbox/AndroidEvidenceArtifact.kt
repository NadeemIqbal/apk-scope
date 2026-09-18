package com.nadeem.apkscope.sandbox

import com.nadeem.apkscope.core.model.AndroidEvidenceStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * Checkpoint 6: Wire and validation shape for the dedicated Android-evidence artifact.
 * Transferred Work → Personal via FileProvider with temporary URI-grant pull mechanism.
 *
 * Captures independent Android-recorded network evidence via DPM (DnsEvent, ConnectEvent).
 * Pure and framework-minimal so its parsing/validation is directly unit-testable on JVM.
 */
data class AndroidDnsEvidenceEntry(
 val eventId: Long,
 val batchToken: Long,
 val packageName: String,
 val timestampEpochMs: Long,
 val receivedAtEpochMs: Long,
 val hostname: String,
 val resolvedAddressesCsv: String,
 val totalResolvedAddressCount: Int,
) {
 fun toJson(): JSONObject = JSONObject().apply {
  put("eventId", eventId)
  put("batchToken", batchToken)
  put("packageName", packageName)
  put("timestampEpochMs", timestampEpochMs)
  put("receivedAtEpochMs", receivedAtEpochMs)
  put("hostname", hostname)
  put("resolvedAddressesCsv", resolvedAddressesCsv)
  put("totalResolvedAddressCount", totalResolvedAddressCount)
 }

 companion object {
  fun fromJson(j: JSONObject): AndroidDnsEvidenceEntry = AndroidDnsEvidenceEntry(
   eventId = j.getLong("eventId"),
   batchToken = j.getLong("batchToken"),
   packageName = j.getString("packageName"),
   timestampEpochMs = j.getLong("timestampEpochMs"),
   receivedAtEpochMs = j.getLong("receivedAtEpochMs"),
   hostname = j.getString("hostname"),
   resolvedAddressesCsv = j.getString("resolvedAddressesCsv"),
   totalResolvedAddressCount = j.getInt("totalResolvedAddressCount"),
  )
 }
}

data class AndroidConnectEvidenceEntry(
 val eventId: Long,
 val batchToken: Long,
 val packageName: String,
 val timestampEpochMs: Long,
 val receivedAtEpochMs: Long,
 val destinationAddress: String,
 val destinationPort: Int,
) {
 fun toJson(): JSONObject = JSONObject().apply {
  put("eventId", eventId)
  put("batchToken", batchToken)
  put("packageName", packageName)
  put("timestampEpochMs", timestampEpochMs)
  put("receivedAtEpochMs", receivedAtEpochMs)
  put("destinationAddress", destinationAddress)
  put("destinationPort", destinationPort)
 }

 companion object {
  fun fromJson(j: JSONObject): AndroidConnectEvidenceEntry = AndroidConnectEvidenceEntry(
   eventId = j.getLong("eventId"),
   batchToken = j.getLong("batchToken"),
   packageName = j.getString("packageName"),
   timestampEpochMs = j.getLong("timestampEpochMs"),
   receivedAtEpochMs = j.getLong("receivedAtEpochMs"),
   destinationAddress = j.getString("destinationAddress"),
   destinationPort = j.getInt("destinationPort"),
  )
 }
}

data class AndroidEvidenceArtifact(
 val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
 val sessionId: String,
 val targetPackageName: String,
 val status: String,
 val dnsEvents: List<AndroidDnsEvidenceEntry>,
 val connectEvents: List<AndroidConnectEvidenceEntry>,
 val firstEventTimestampEpochMs: Long? = null,
 val lastEventTimestampEpochMs: Long? = null,
 val exportedAtEpochMs: Long = System.currentTimeMillis(),
) {
 fun toJson(): JSONObject = JSONObject().apply {
  put("schemaVersion", schemaVersion)
  put("sessionId", sessionId)
  put("targetPackageName", targetPackageName)
  put("status", status)
  firstEventTimestampEpochMs?.let { put("firstEventTimestampEpochMs", it) }
  lastEventTimestampEpochMs?.let { put("lastEventTimestampEpochMs", it) }
  put("exportedAtEpochMs", exportedAtEpochMs)

  val dnsArr = JSONArray()
  dnsEvents.forEach { dnsArr.put(it.toJson()) }
  put("dnsEvents", dnsArr)

  val connArr = JSONArray()
  connectEvents.forEach { connArr.put(it.toJson()) }
  put("connectEvents", connArr)
 }

 companion object {
  const val CURRENT_SCHEMA_VERSION = 1
  /** Bound the max artifact size to 256KB to guard cross-profile memory and file limits. */
  const val MAX_ARTIFACT_BYTES = 256L * 1024

  fun fromJson(jsonString: String): AndroidEvidenceArtifact {
   if (jsonString.toByteArray(Charsets.UTF_8).size > MAX_ARTIFACT_BYTES) {
    throw IllegalArgumentException("AndroidEvidenceArtifact exceeds MAX_ARTIFACT_BYTES ($MAX_ARTIFACT_BYTES)")
   }
   val j = JSONObject(jsonString)
   val version = j.getInt("schemaVersion")
   require(version == CURRENT_SCHEMA_VERSION) { "Unsupported schemaVersion: $version" }
   val sessionId = j.getString("sessionId")
   require(sessionId.isNotBlank()) { "sessionId must not be blank" }
   val targetPackageName = j.getString("targetPackageName")
   require(targetPackageName.isNotBlank()) { "targetPackageName must not be blank" }
   val status = j.getString("status")

   val dnsArr = j.getJSONArray("dnsEvents")
   val dnsEvents = (0 until dnsArr.length()).map {
    AndroidDnsEvidenceEntry.fromJson(dnsArr.getJSONObject(it))
   }

   val connArr = j.getJSONArray("connectEvents")
   val connectEvents = (0 until connArr.length()).map {
    AndroidConnectEvidenceEntry.fromJson(connArr.getJSONObject(it))
   }

   return AndroidEvidenceArtifact(
    schemaVersion = version,
    sessionId = sessionId,
    targetPackageName = targetPackageName,
    status = status,
    dnsEvents = dnsEvents,
    connectEvents = connectEvents,
    firstEventTimestampEpochMs = if (j.has("firstEventTimestampEpochMs")) j.getLong("firstEventTimestampEpochMs") else null,
    lastEventTimestampEpochMs = if (j.has("lastEventTimestampEpochMs")) j.getLong("lastEventTimestampEpochMs") else null,
    exportedAtEpochMs = j.getLong("exportedAtEpochMs"),
   )
  }

  fun validate(artifact: AndroidEvidenceArtifact, expectedSessionId: String, expectedPackageName: String): String? {
   if (artifact.schemaVersion != CURRENT_SCHEMA_VERSION) return "unsupported schemaVersion=${artifact.schemaVersion}"
   if (artifact.sessionId.isBlank() || artifact.sessionId != expectedSessionId) {
    return "sessionId mismatch: expected=$expectedSessionId actual=${artifact.sessionId}"
   }
   if (artifact.targetPackageName.isNotEmpty() && expectedPackageName.isNotEmpty() && artifact.targetPackageName != expectedPackageName) {
    return "packageName mismatch: expected=$expectedPackageName actual=${artifact.targetPackageName}"
   }
   val first = artifact.firstEventTimestampEpochMs
   val last = artifact.lastEventTimestampEpochMs
   if (first != null && last != null && last < first) {
    return "lastEventTimestamp precedes firstEventTimestamp"
   }
   return null
  }
 }
}
