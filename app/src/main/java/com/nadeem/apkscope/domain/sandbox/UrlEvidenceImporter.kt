package com.nadeem.apkscope.domain.sandbox

import android.content.Context
import com.nadeem.apkscope.domain.StaticAnalysisResultStore
import com.nadeem.apkscope.sandbox.UrlEvidenceArtifact

/**
 * Milestone 9 (fourth acceptance rigor pass, item 3): the actual, real production read-modify-write
 * sequence [SandboxSessionCoordinator.correlateUrlEvidenceWithAnalysis] delegates to — extracted
 * (same pattern as [com.nadeem.apkscope.sandbox.UrlEvidenceExporter]) specifically so *repeated import*
 * behavior (same-content idempotency, no duplicate references, correct persisted state across a
 * simulated process restart) is directly testable against real Android storage
 * (`StaticAnalysisResultStore`) and the real [UrlEvidenceCorrelator], not a reimplementation of
 * either. The coordinator's own logging/[UrlEvidenceImportStatusStore] recording stays in the
 * coordinator — this function is exactly the `get → correlate → put`-inside-the-lock core, nothing
 * more.
 *
 * Returns `null` when no persisted analysis exists for [analysisId] — the same "nothing to
 * correlate against" case the coordinator already handles as a non-error outcome.
 */
object UrlEvidenceImporter {
    fun correlate(context: Context, analysisId: String, artifact: UrlEvidenceArtifact): UrlEvidenceCorrelator.Result? =
        StaticAnalysisResultStore.withLock(analysisId) {
            val metadata = StaticAnalysisResultStore.get(context, analysisId) ?: return@withLock null
            val correlated = UrlEvidenceCorrelator.correlate(metadata.embeddedUrls, artifact.entries, analysisId)
            if (correlated.changed) {
                StaticAnalysisResultStore.put(context, analysisId, metadata.copy(embeddedUrls = correlated.candidates))
            }
            correlated
        }
}
