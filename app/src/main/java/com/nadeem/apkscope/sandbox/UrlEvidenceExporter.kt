package com.nadeem.apkscope.sandbox

import com.nadeem.apkscope.core.network.traffic.TrafficInspectionStore

/**
 * Milestone 9 (fourth acceptance rigor pass, item 1): the actual, pure export-boundary logic that
 * [SandboxWorkQueryActivity.exportUrlEvidence] delegates to — extracted specifically so the real
 * production export path (not a reimplementation of it, and not only
 * [TrafficInspectionStore.forSession] in isolation, which `TrafficInspectionStoreExportTest` already
 * covers) is directly testable without an Activity. The only thing the Activity still does itself
 * is resolve `targetPackage` from `WorkEvidenceStore` before calling [export] — everything else here
 * is byte-for-byte what `exportUrlEvidence` used to do inline.
 *
 * See `UrlEvidenceExporterTest` for the real-export-boundary assertion this was extracted to enable:
 * exported transaction identity (id, url, session, target), ownership-eligibility exclusion of an
 * unrelated (e.g. second-application) transaction, and the exported/total-count fields on the real
 * [com.nadeem.apkscope.sandbox.UrlEvidenceArtifact] this function returns — not a synthetic reimplementation.
 */
object UrlEvidenceExporter {
    fun export(sessionId: String, targetPackage: String, nowEpochMs: Long = System.currentTimeMillis()): UrlEvidenceArtifact {
        // Only export records from this specific session and target package — the one real
        // production call site of TrafficInspectionStore.forSession().
        val sessionRecords = TrafficInspectionStore.forSession(sessionId, targetPackage)

        val entries = ArrayList<UrlEvidenceEntry>()
        var truncated = false
        var sequence = 0L

        // Reserve headroom for envelope fields
        val budget = UrlEvidenceArtifact.MAX_ARTIFACT_BYTES - 2048
        var approxBytes = 0L

        for (record in sessionRecords) {
            if (entries.size >= UrlEvidenceArtifact.MAX_ENTRIES) {
                truncated = true
                break
            }

            // Create UrlEvidenceEntry with redacted URL
            val entry = UrlEvidenceEntry(
                sequence = sequence++,
                analysisSessionId = sessionId,
                analysisTargetPackage = targetPackage,
                trafficTransactionId = record.id,
                trafficSequence = entries.size.toLong(), // Sequential numbering within this artifact
                requestUrl = record.url, // Already sanitized/redacted by TrafficInspectionStore
                requestMethod = record.method?.takeIf { it.isNotEmpty() },
                responseStatusCode = record.statusCode,
                timestampEpochMs = record.timestamp.toEpochMilli(),
                captureStatus = if (record.isTruncated) "TRUNCATED" else "COMPLETE",
            )

            val entryBytes = entry.toJson().toString().toByteArray().size.toLong() + 1
            if (approxBytes + entryBytes > budget) {
                truncated = true
                break
            }

            entries.add(entry)
            approxBytes += entryBytes
        }

        return UrlEvidenceArtifact(
            schemaVersion = UrlEvidenceArtifact.SCHEMA_VERSION,
            analysisSessionId = sessionId,
            analysisTargetPackage = targetPackage,
            capturedAtEpochMs = sessionRecords.minOfOrNull { it.timestamp.toEpochMilli() } ?: nowEpochMs,
            exportedAtEpochMs = nowEpochMs,
            entries = entries,
            truncated = truncated,
            exportedEntryCount = entries.size,
            totalEntryCount = sessionRecords.size,
        )
    }
}
