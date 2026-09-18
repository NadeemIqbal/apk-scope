package com.nadeem.apkscope.domain.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nadeem.apkscope.core.staticanalysis.ApkMetadata
import com.nadeem.apkscope.core.staticanalysis.DexUrlCandidate
import com.nadeem.apkscope.core.staticanalysis.StaticAnalysisCoverage
import com.nadeem.apkscope.core.staticanalysis.UrlProvenance
import com.nadeem.apkscope.domain.StaticAnalysisFileStore
import com.nadeem.apkscope.domain.StaticAnalysisResultStore
import com.nadeem.apkscope.sandbox.UrlEvidenceArtifact
import com.nadeem.apkscope.sandbox.UrlEvidenceEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Milestone 9 (fourth acceptance rigor pass, item 3): exercises the actual production
 * read-modify-write import sequence — [UrlEvidenceImporter.correlate], the exact function
 * [SandboxSessionCoordinator.correlateUrlEvidenceWithAnalysis] delegates to — against real Android
 * storage ([StaticAnalysisResultStore]), invoked twice with clearly-labeled synthetic (not
 * real-captured) artifact content, to prove: the two imports reference the *same* transaction
 * identity, the second is a genuine idempotent no-op (not a duplicate candidate/reference), and the
 * result survives a simulated process restart (reading the real on-disk envelope directly, bypassing
 * this process's own in-memory cache — the same honest proxy `StaticAnalysisPersistenceInstrumentedTest`
 * already establishes: a genuinely fresh process's cache starts empty too, so a direct disk read
 * proves the same thing a cold start's first read would).
 */
@RunWith(AndroidJUnit4::class)
class RepeatedUrlEvidenceImportInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun rawFileFor(analysisId: String): File =
        File(File(context.applicationContext.filesDir, "analysis_store"), "$analysisId.bin")

    private fun metadataWithOneCandidate(url: String): ApkMetadata = ApkMetadata(
        sha256 = "", packageName = "com.example.target", versionName = "1.0", versionCode = 1,
        minSdkVersion = 21, targetSdkVersion = 34, requestedPermissions = emptyList(), components = emptyList(),
        debuggable = false, nativeLibraryAbis = emptyList(), networkSecurityConfigPresent = null,
        usesCleartextTraffic = false, intentFilters = emptyList(),
        embeddedUrls = listOf(
            DexUrlCandidate(
                originalString = url, normalizedUrl = url, host = "jsonplaceholder.typicode.com",
                scheme = "https", dexEntry = "classes.dex", provenance = UrlProvenance.PRESENT_IN_DEX,
            )
        ),
        detectedSdks = emptyList(), apiFindings = emptyList(), staticCoverage = StaticAnalysisCoverage(),
    )

    /** A clearly-labeled synthetic artifact — shaped like a real single-transaction export, never presented as replayed real capture. */
    private fun syntheticArtifact(analysisId: String, url: String, transactionId: String, timestampMs: Long, sequence: Long = 0L): UrlEvidenceArtifact {
        val entry = UrlEvidenceEntry(
            sequence = 0, analysisSessionId = analysisId, analysisTargetPackage = "com.example.target",
            trafficTransactionId = transactionId, trafficSequence = sequence, requestUrl = url,
            requestMethod = "GET", responseStatusCode = 200, timestampEpochMs = timestampMs, captureStatus = "COMPLETE",
        )
        return UrlEvidenceArtifact(
            schemaVersion = UrlEvidenceArtifact.SCHEMA_VERSION, analysisSessionId = analysisId,
            analysisTargetPackage = "com.example.target", capturedAtEpochMs = timestampMs, exportedAtEpochMs = timestampMs,
            entries = listOf(entry), truncated = false, exportedEntryCount = 1, totalEntryCount = 1,
        )
    }

    /** Two static candidates on the same host — [exactUrl] is expected to receive an exact match; [hostOnlyUrl] shares the host but is never itself requested, so it can only ever earn a host-only correlation. */
    private fun metadataWithExactAndHostOnlyCandidates(exactUrl: String, hostOnlyUrl: String, host: String): ApkMetadata = ApkMetadata(
        sha256 = "", packageName = "com.example.target", versionName = "1.0", versionCode = 1,
        minSdkVersion = 21, targetSdkVersion = 34, requestedPermissions = emptyList(), components = emptyList(),
        debuggable = false, nativeLibraryAbis = emptyList(), networkSecurityConfigPresent = null,
        usesCleartextTraffic = false, intentFilters = emptyList(),
        embeddedUrls = listOf(
            DexUrlCandidate(originalString = exactUrl, normalizedUrl = exactUrl, host = host, scheme = "https", dexEntry = "classes.dex", provenance = UrlProvenance.PRESENT_IN_DEX),
            DexUrlCandidate(originalString = hostOnlyUrl, normalizedUrl = hostOnlyUrl, host = host, scheme = "https", dexEntry = "classes.dex", provenance = UrlProvenance.REFERENCED_BY_CODE),
        ),
        detectedSdks = emptyList(), apiFindings = emptyList(), staticCoverage = StaticAnalysisCoverage(),
    )

    @Test
    fun repeatedImport_sameArtifactContentTwice_referencesTheSameTransaction_noDuplication_andSurvivesASimulatedProcessRestart() {
        val analysisId = "repeat-import-test-${UUID.randomUUID()}"
        val url = "https://jsonplaceholder.typicode.com/posts/1"
        val transactionId = "synthetic-txn-A-${UUID.randomUUID()}"
        val timestampMs = System.currentTimeMillis()

        StaticAnalysisResultStore.put(context, analysisId, metadataWithOneCandidate(url))

        // First import: a genuine change (RUNTIME_OBSERVED not yet present).
        val first = UrlEvidenceImporter.correlate(context, analysisId, syntheticArtifact(analysisId, url, transactionId, timestampMs))
        assertNotNull("expected a real correlation result against a persisted analysis", first)
        assertTrue("the first import of new evidence must be a real change", first!!.changed)

        val afterFirst = StaticAnalysisResultStore.get(context, analysisId)!!.embeddedUrls.single()
        assertEquals(transactionId, afterFirst.runtimeEvidence?.transactionId)
        assertEquals(url, afterFirst.runtimeEvidence?.url)

        // Second import: the exact same artifact content (same transaction id, same timestamp) —
        // a genuine repeat of the identical evidence, not a different observation.
        val second = UrlEvidenceImporter.correlate(context, analysisId, syntheticArtifact(analysisId, url, transactionId, timestampMs))
        assertNotNull(second)
        assertFalse("repeating identical artifact content must be an idempotent no-op", second!!.changed)

        val afterSecond = StaticAnalysisResultStore.get(context, analysisId)!!
        assertEquals("no duplicate candidate must appear — still exactly one embedded URL", 1, afterSecond.embeddedUrls.size)
        val afterSecondCandidate = afterSecond.embeddedUrls.single()
        assertEquals("the retained reference must be the *same* transaction after the repeat, not a look-alike replacement", transactionId, afterSecondCandidate.runtimeEvidence?.transactionId)
        assertEquals(url, afterSecondCandidate.runtimeEvidence?.url)

        // Simulated process restart: read the real on-disk envelope directly, bypassing this
        // process's own StaticAnalysisResultStore in-memory cache entirely.
        val onDisk = StaticAnalysisFileStore.read(rawFileFor(analysisId))
        assertNotNull("persisted evidence must survive a process restart", onDisk)
        val onDiskCandidate = onDisk!!.embeddedUrls.single()
        assertEquals(transactionId, onDiskCandidate.runtimeEvidence?.transactionId)
        assertEquals(url, onDiskCandidate.runtimeEvidence?.url)
    }

    @Test
    fun repeatedImport_forAnAnalysisThatWasNeverPersisted_returnsNullBothTimes_neverFabricatesAResult() {
        val analysisId = "repeat-import-missing-${UUID.randomUUID()}"
        val artifact = syntheticArtifact(analysisId, "https://example.com/x", "synthetic-txn-missing", System.currentTimeMillis())

        assertNull(UrlEvidenceImporter.correlate(context, analysisId, artifact))
        assertNull(UrlEvidenceImporter.correlate(context, analysisId, artifact))
    }

    /**
     * Milestone 9 (fourth acceptance rigor pass, item 4a): "a distinct later observation replaces an
     * earlier retained reference" — already verified as pure logic by
     * `UrlEvidenceCorrelatorTest.laterDistinctTransaction_replacesEarlierExactMatch`. This is the
     * Android persistence integration counterpart: the real [UrlEvidenceImporter.correlate], real
     * [StaticAnalysisResultStore], two genuinely separate import calls (mirroring two real session
     * ends for the same analysis, not one batch).
     */
    @Test
    fun laterDistinctObservation_replacesEarlierRetainedReference_throughTheRealImportPath() {
        val analysisId = "repeat-import-later-obs-${UUID.randomUUID()}"
        val url = "https://jsonplaceholder.typicode.com/posts/1"
        StaticAnalysisResultStore.put(context, analysisId, metadataWithOneCandidate(url))

        val earlier = UrlEvidenceImporter.correlate(context, analysisId, syntheticArtifact(analysisId, url, "synthetic-txn-earlier", 1_000_000L))
        assertTrue(earlier!!.changed)
        assertEquals("synthetic-txn-earlier", StaticAnalysisResultStore.get(context, analysisId)!!.embeddedUrls.single().runtimeEvidence?.transactionId)

        val later = UrlEvidenceImporter.correlate(context, analysisId, syntheticArtifact(analysisId, url, "synthetic-txn-later", 2_000_000L))
        assertTrue("a genuinely later, distinct transaction must be recognized as a real change", later!!.changed)
        val retained = StaticAnalysisResultStore.get(context, analysisId)!!.embeddedUrls.single()
        assertEquals("the later observation must replace the earlier one", "synthetic-txn-later", retained.runtimeEvidence?.transactionId)
    }

    /**
     * Item 4b: "an older artifact replay does not regress retained evidence" — pure-logic coverage in
     * `UrlEvidenceCorrelatorTest.olderTransactionInBatch_neverRegressesAnAlreadyNewerStoredObservation`.
     * Android integration counterpart, real import path, two separate calls.
     */
    @Test
    fun olderArtifactReplayedAfterward_doesNotRegressRetainedEvidence_throughTheRealImportPath() {
        val analysisId = "repeat-import-no-regress-${UUID.randomUUID()}"
        val url = "https://jsonplaceholder.typicode.com/posts/1"
        StaticAnalysisResultStore.put(context, analysisId, metadataWithOneCandidate(url))

        UrlEvidenceImporter.correlate(context, analysisId, syntheticArtifact(analysisId, url, "synthetic-txn-newer", 2_000_000L))
        assertEquals("synthetic-txn-newer", StaticAnalysisResultStore.get(context, analysisId)!!.embeddedUrls.single().runtimeEvidence?.transactionId)

        // Replay an older artifact afterward (e.g. a retried/re-delivered earlier export).
        val replay = UrlEvidenceImporter.correlate(context, analysisId, syntheticArtifact(analysisId, url, "synthetic-txn-older", 1_000_000L))
        assertFalse("replaying an older observation must never be treated as a change", replay!!.changed)
        val retained = StaticAnalysisResultStore.get(context, analysisId)!!.embeddedUrls.single()
        assertEquals("retained evidence must remain the newer transaction, not regress to the replayed older one", "synthetic-txn-newer", retained.runtimeEvidence?.transactionId)
    }

    /**
     * Item 4c: deterministic equal-timestamp behavior — pure-logic coverage in
     * `UrlEvidenceCorrelatorTest.equalTimestampAcrossBatches_existingStoredObservationWinsTheTie`.
     * Android integration counterpart: two real, separate import calls sharing an identical
     * timestamp, through the real storage/import path.
     */
    @Test
    fun equalTimestampAcrossTwoRealImports_existingStoredObservationDeterministicallyWinsTheTie() {
        val analysisId = "repeat-import-equal-ts-${UUID.randomUUID()}"
        val url = "https://jsonplaceholder.typicode.com/posts/1"
        val sharedTimestamp = 1_500_000L
        StaticAnalysisResultStore.put(context, analysisId, metadataWithOneCandidate(url))

        UrlEvidenceImporter.correlate(context, analysisId, syntheticArtifact(analysisId, url, "synthetic-txn-first-stored", sharedTimestamp))
        assertEquals("synthetic-txn-first-stored", StaticAnalysisResultStore.get(context, analysisId)!!.embeddedUrls.single().runtimeEvidence?.transactionId)

        // A different transaction id lands at the exact same millisecond in a later, separate import.
        val tieResult = UrlEvidenceImporter.correlate(context, analysisId, syntheticArtifact(analysisId, url, "synthetic-txn-equal-timestamp-newcomer", sharedTimestamp))
        assertFalse("an exact-timestamp tie must deterministically favor the already-stored observation, never treated as a change", tieResult!!.changed)
        val retained = StaticAnalysisResultStore.get(context, analysisId)!!.embeddedUrls.single()
        assertEquals("synthetic-txn-first-stored", retained.runtimeEvidence?.transactionId)
    }

    /**
     * Item 4d: "preservation of static provenance and host correlation precision" — pure-logic
     * coverage in `UrlEvidenceCorrelatorTest.hostCorrelation_neverUpgradesProvenanceToRuntimeObserved`.
     * Android integration counterpart: a real persisted analysis with two candidates on the same
     * host — one gets a real exact match, the other only ever shares the host — verified through the
     * real import path that the host-only candidate's static provenance is never altered and never
     * promoted, while genuinely receiving host-correlation metadata.
     */
    @Test
    fun hostOnlyCandidate_keepsItsStaticProvenanceAndNeverGetsRuntimeEvidence_whileAnUnrelatedExactMatchIsUnaffected_throughTheRealImportPath() {
        val analysisId = "repeat-import-host-precision-${UUID.randomUUID()}"
        val host = "jsonplaceholder.typicode.com"
        val exactUrl = "https://$host/posts/1"
        val hostOnlyUrl = "https://$host/comments/9999" // never itself requested
        StaticAnalysisResultStore.put(context, analysisId, metadataWithExactAndHostOnlyCandidates(exactUrl, hostOnlyUrl, host))

        val result = UrlEvidenceImporter.correlate(context, analysisId, syntheticArtifact(analysisId, exactUrl, "synthetic-txn-exact-only", System.currentTimeMillis()))
        assertTrue(result!!.changed)

        val candidates = StaticAnalysisResultStore.get(context, analysisId)!!.embeddedUrls.associateBy { it.originalString }
        val exactCandidate = candidates.getValue(exactUrl)
        val hostOnlyCandidate = candidates.getValue(hostOnlyUrl)

        assertEquals("the genuinely requested URL must be promoted to RUNTIME_OBSERVED", UrlProvenance.RUNTIME_OBSERVED, exactCandidate.provenance)
        assertEquals("synthetic-txn-exact-only", exactCandidate.runtimeEvidence?.transactionId)

        assertEquals("a candidate that only shares a host, and was never itself requested, must keep its original static provenance", UrlProvenance.REFERENCED_BY_CODE, hostOnlyCandidate.provenance)
        assertNull("a host-only match must never be given fabricated exact runtime evidence", hostOnlyCandidate.runtimeEvidence)
        assertNotNull("a host-only match must still genuinely receive host-correlation metadata", hostOnlyCandidate.hostCorrelation)
        assertEquals("synthetic-txn-exact-only", hostOnlyCandidate.hostCorrelation?.sampleTransactionId)
    }
}
