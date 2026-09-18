package com.nadeem.apkscope.sandbox

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Milestone 9 — [UrlEvidenceArtifact.validate] is the actual enforcement point that keeps a
 * cross-profile-imported artifact scoped to the session/target it claims to belong to; the pure
 * [com.nadeem.apkscope.domain.sandbox.UrlEvidenceCorrelator] trusts its caller already did this check
 * (see that class's own doc comment), so this file covers the "session/package mismatches" case
 * this milestone's acceptance instructions require, at the layer that actually enforces it.
 */
class UrlEvidenceArtifactTest {

    private fun entry(url: String = "https://httpbin.org/get") = UrlEvidenceEntry(
        sequence = 0L,
        analysisSessionId = "session-1",
        analysisTargetPackage = "com.apksandbox.fixture",
        trafficTransactionId = "txn-1",
        trafficSequence = 0L,
        requestUrl = url,
        requestMethod = "GET",
        responseStatusCode = 200,
        timestampEpochMs = 1_000L,
        captureStatus = "COMPLETE",
    )

    private fun artifact(
        sessionId: String = "session-1",
        targetPackage: String = "com.apksandbox.fixture",
        entries: List<UrlEvidenceEntry> = listOf(entry()),
        exportedAt: Long = 2_000L,
        capturedAt: Long = 1_000L,
        truncated: Boolean = false,
        totalCount: Int = entries.size,
    ) = UrlEvidenceArtifact(
        schemaVersion = UrlEvidenceArtifact.SCHEMA_VERSION,
        analysisSessionId = sessionId,
        analysisTargetPackage = targetPackage,
        capturedAtEpochMs = capturedAt,
        exportedAtEpochMs = exportedAt,
        entries = entries,
        truncated = truncated,
        exportedEntryCount = entries.size,
        totalEntryCount = totalCount,
    )

    @Test
    fun validArtifact_passesValidation() {
        assertNull(UrlEvidenceArtifact.validate(artifact(), expectedSessionId = "session-1", expectedPackage = "com.apksandbox.fixture"))
    }

    @Test
    fun sessionIdMismatch_isRejected() {
        // An artifact genuinely built for a *different* session must never be accepted into this
        // session's analysis, even if everything else about it looks well-formed.
        val a = artifact(sessionId = "session-OTHER")
        assertNotNull(UrlEvidenceArtifact.validate(a, expectedSessionId = "session-1", expectedPackage = "com.apksandbox.fixture"))
    }

    @Test
    fun targetPackageMismatch_isRejected() {
        // Evidence for a different target package (e.g. a stale/reused artifact from an earlier
        // analysis of a different APK) must never be imported into this analysis.
        val a = artifact(targetPackage = "com.apksandbox.riskfixture")
        assertNotNull(UrlEvidenceArtifact.validate(a, expectedSessionId = "session-1", expectedPackage = "com.apksandbox.fixture"))
    }

    @Test
    fun unsupportedSchemaVersion_isRejected() {
        val a = artifact().copy(schemaVersion = UrlEvidenceArtifact.SCHEMA_VERSION + 1)
        assertNotNull(UrlEvidenceArtifact.validate(a, expectedSessionId = "session-1", expectedPackage = "com.apksandbox.fixture"))
    }

    @Test
    fun exportedAtBeforeCapturedAt_isRejected() {
        val a = artifact(capturedAt = 5_000L, exportedAt = 1_000L)
        assertNotNull(UrlEvidenceArtifact.validate(a, expectedSessionId = "session-1", expectedPackage = "com.apksandbox.fixture"))
    }

    @Test
    fun inconsistentTruncationCounts_areRejected() {
        // Declares not truncated but total doesn't match exported — internally inconsistent.
        val a = artifact(truncated = false, totalCount = 99)
        assertNotNull(UrlEvidenceArtifact.validate(a, expectedSessionId = "session-1", expectedPackage = "com.apksandbox.fixture"))
    }
}
