package com.nadeem.apkscope.domain.sandbox

import com.nadeem.apkscope.core.staticanalysis.DexUrlCandidate
import com.nadeem.apkscope.core.staticanalysis.HostCorrelationInfo
import com.nadeem.apkscope.core.staticanalysis.RuntimeEvidenceReference
import com.nadeem.apkscope.core.staticanalysis.UrlProvenance
import com.nadeem.apkscope.sandbox.UrlEvidenceEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 9 — direct, Robolectric-free unit coverage for [UrlEvidenceCorrelator], the pure
 * matching/merge logic extracted from [SandboxSessionCoordinator.correlateUrlEvidenceWithAnalysis]
 * specifically so it could be tested without Android/Log/disk dependencies or private-method
 * reflection. Covers every case explicitly required by this milestone's acceptance instructions.
 */
class UrlEvidenceCorrelatorTest {

    private fun candidate(
        url: String,
        host: String,
        provenance: UrlProvenance = UrlProvenance.REFERENCED_BY_CODE,
        runtimeEvidence: RuntimeEvidenceReference? = null,
        hostCorrelation: HostCorrelationInfo? = null,
    ) = DexUrlCandidate(
        originalString = url,
        normalizedUrl = url,
        host = host,
        scheme = "https",
        dexEntry = "classes.dex",
        provenance = provenance,
        runtimeEvidence = runtimeEvidence,
        hostCorrelation = hostCorrelation,
    )

    private fun entry(
        url: String,
        transactionId: String,
        timestampEpochMs: Long = 1_000L,
        method: String? = "GET",
        statusCode: Int? = 200,
        trafficSequence: Long = 0L,
    ) = UrlEvidenceEntry(
        sequence = 0L,
        analysisSessionId = "session-1",
        analysisTargetPackage = "com.apksandbox.fixture",
        trafficTransactionId = transactionId,
        trafficSequence = trafficSequence,
        requestUrl = url,
        requestMethod = method,
        responseStatusCode = statusCode,
        timestampEpochMs = timestampEpochMs,
        captureStatus = "COMPLETE",
    )

    // --- Exact URL matches ---------------------------------------------------------------

    @Test
    fun exactUrlMatch_setsRuntimeEvidenceAndUpgradesProvenance() {
        val c = candidate("https://jsonplaceholder.typicode.com/posts/1", "jsonplaceholder.typicode.com", provenance = UrlProvenance.PRESENT_IN_DEX)
        val e = entry("https://jsonplaceholder.typicode.com/posts/1", "txn-1")

        val result = UrlEvidenceCorrelator.correlate(listOf(c), listOf(e), "analysis-1")

        assertTrue(result.changed)
        val updated = result.candidates.single()
        assertEquals(UrlProvenance.RUNTIME_OBSERVED, updated.provenance)
        assertNotNull(updated.runtimeEvidence)
        assertEquals("txn-1", updated.runtimeEvidence!!.transactionId)
        assertEquals("analysis-1", updated.runtimeEvidence!!.sessionId)
        assertEquals("GET", updated.runtimeEvidence!!.method)
        assertEquals(200, updated.runtimeEvidence!!.statusCode)
        assertNull("An exact match must never also populate hostCorrelation", updated.hostCorrelation)
    }

    // --- Different paths on the same host: host correlation, not exact match ------------------

    @Test
    fun sameHostDifferentPath_producesHostCorrelationNotExactMatch() {
        val c = candidate("https://jsonplaceholder.typicode.com/posts", "jsonplaceholder.typicode.com", provenance = UrlProvenance.REFERENCED_BY_CODE)
        // The captured traffic requested a *different* path on the same host.
        val e = entry("https://jsonplaceholder.typicode.com/posts/1", "txn-2")

        val result = UrlEvidenceCorrelator.correlate(listOf(c), listOf(e), "analysis-1")

        assertTrue(result.changed)
        val updated = result.candidates.single()
        assertNull("A same-host, different-path request must never be reported as an exact match", updated.runtimeEvidence)
        assertEquals(UrlProvenance.REFERENCED_BY_CODE, updated.provenance) // static provenance preserved
        assertNotNull(updated.hostCorrelation)
        assertEquals("jsonplaceholder.typicode.com", updated.hostCorrelation!!.host)
        assertEquals(1, updated.hostCorrelation!!.observedTransactionCount)
        assertEquals("txn-2", updated.hostCorrelation!!.sampleTransactionId)
    }

    // --- Host lookalikes must never satisfy host correlation, let alone exact match -----------

    @Test
    fun hostLookalike_doesNotCorrelateAtAll() {
        val c = candidate("https://jsonplaceholder.typicode.com/posts/1", "jsonplaceholder.typicode.com")
        // A different, real host that merely embeds the candidate's host as a text substring.
        val e = entry("https://jsonplaceholder.typicode.com.evil.example/posts/1", "txn-lookalike")

        val result = UrlEvidenceCorrelator.correlate(listOf(c), listOf(e), "analysis-1")

        assertFalseChanged(result)
        val updated = result.candidates.single()
        assertNull(updated.runtimeEvidence)
        assertNull("A lookalike host must not produce a false host correlation", updated.hostCorrelation)
    }

    @Test
    fun hostLookalike_subdomainPrefixDoesNotMatchParentHost() {
        // "evil-jsonplaceholder.typicode.com" is a distinct host, not the real one, even though it
        // ends with the same string.
        val c = candidate("https://jsonplaceholder.typicode.com/posts/1", "jsonplaceholder.typicode.com")
        val e = entry("https://evil-jsonplaceholder.typicode.com/posts/1", "txn-lookalike-2")

        val result = UrlEvidenceCorrelator.correlate(listOf(c), listOf(e), "analysis-1")

        assertFalseChanged(result)
        assertNull(result.candidates.single().hostCorrelation)
    }

    // --- Redacted query ambiguity must never create a false exact match -----------------------

    @Test
    fun redactedQueryParam_fallsBackToHostCorrelationNotExactMatch() {
        // The DEX-extracted literal includes a real token value...
        val c = candidate("https://httpbin.org/get?token=abc123", "httpbin.org")
        // ...but HttpsInspectionStore.sanitizeUrl redacts it before this entry was ever produced.
        val e = entry("https://httpbin.org/get?token=[REDACTED]", "txn-redacted")

        val result = UrlEvidenceCorrelator.correlate(listOf(c), listOf(e), "analysis-1")

        val updated = result.candidates.single()
        assertNull("A redacted query value must never be treated as proof of the exact original URL", updated.runtimeEvidence)
        assertNotNull("The redacted request is still real evidence the host was contacted", updated.hostCorrelation)
    }

    // --- Repeated artifacts: idempotent, no duplicate/second reference -------------------------

    @Test
    fun repeatedExactMatchImport_isIdempotentNoOp() {
        val c = candidate("https://jsonplaceholder.typicode.com/posts/1", "jsonplaceholder.typicode.com")
        val e = entry("https://jsonplaceholder.typicode.com/posts/1", "txn-1", timestampEpochMs = 5_000L)

        val first = UrlEvidenceCorrelator.correlate(listOf(c), listOf(e), "analysis-1")
        assertTrue(first.changed)

        // Re-import the *exact same* artifact (simulating a retry after interruption, or a
        // deliberate repeat import) against the already-updated candidate list.
        val second = UrlEvidenceCorrelator.correlate(first.candidates, listOf(e), "analysis-1")

        assertFalseChanged(second)
        assertEquals(first.candidates.single().runtimeEvidence, second.candidates.single().runtimeEvidence)
    }

    @Test
    fun repeatedHostCorrelationImport_isIdempotentNoOp() {
        val c = candidate("https://jsonplaceholder.typicode.com/posts", "jsonplaceholder.typicode.com")
        val e = entry("https://jsonplaceholder.typicode.com/posts/1", "txn-2")

        val first = UrlEvidenceCorrelator.correlate(listOf(c), listOf(e), "analysis-1")
        val second = UrlEvidenceCorrelator.correlate(first.candidates, listOf(e), "analysis-1")

        assertFalseChanged(second)
        assertEquals(first.candidates.single().hostCorrelation, second.candidates.single().hostCorrelation)
    }

    // --- Distinct later observations must not be silently suppressed --------------------------

    @Test
    fun laterDistinctTransaction_replacesEarlierExactMatch() {
        val existing = RuntimeEvidenceReference(
            sessionId = "analysis-1", transactionId = "txn-old", url = "https://jsonplaceholder.typicode.com/posts/1",
            timestamp = 1_000L, method = "GET", statusCode = 404,
        )
        val c = candidate(
            "https://jsonplaceholder.typicode.com/posts/1", "jsonplaceholder.typicode.com",
            provenance = UrlProvenance.RUNTIME_OBSERVED, runtimeEvidence = existing,
        )
        // A later session's genuinely new transaction for the same URL.
        val newer = entry("https://jsonplaceholder.typicode.com/posts/1", "txn-new", timestampEpochMs = 2_000L, statusCode = 200)

        val result = UrlEvidenceCorrelator.correlate(listOf(c), listOf(newer), "analysis-1")

        assertTrue("A later, distinct, newer transaction must not be silently suppressed by the earlier one", result.changed)
        val updated = result.candidates.single().runtimeEvidence!!
        assertEquals("txn-new", updated.transactionId)
        assertEquals(200, updated.statusCode)
    }

    @Test
    fun olderTransactionInBatch_neverRegressesAnAlreadyNewerStoredObservation() {
        val existing = RuntimeEvidenceReference(
            sessionId = "analysis-1", transactionId = "txn-new", url = "https://jsonplaceholder.typicode.com/posts/1",
            timestamp = 5_000L, method = "GET", statusCode = 200,
        )
        val c = candidate(
            "https://jsonplaceholder.typicode.com/posts/1", "jsonplaceholder.typicode.com",
            provenance = UrlProvenance.RUNTIME_OBSERVED, runtimeEvidence = existing,
        )
        // A batch containing only an *older* transaction than what's already stored (e.g. a
        // reordered/out-of-sequence re-import) must not regress the stored evidence.
        val older = entry("https://jsonplaceholder.typicode.com/posts/1", "txn-ancient", timestampEpochMs = 1_000L)

        val result = UrlEvidenceCorrelator.correlate(listOf(c), listOf(older), "analysis-1")

        assertFalseChanged(result)
        assertEquals("txn-new", result.candidates.single().runtimeEvidence!!.transactionId)
    }

    @Test
    fun laterImportWithNoHostMatches_preservesEarlierHostCorrelation() {
        val existing = HostCorrelationInfo(host = "jsonplaceholder.typicode.com", observedTransactionCount = 1, sampleTransactionId = "txn-old", sampleSessionId = "analysis-1")
        val c = candidate("https://jsonplaceholder.typicode.com/posts", "jsonplaceholder.typicode.com", hostCorrelation = existing)
        // A later, unrelated session's import with nothing for this host at all.
        val unrelated = entry("https://httpbin.org/get", "txn-unrelated")

        val result = UrlEvidenceCorrelator.correlate(listOf(c), listOf(unrelated), "analysis-1")

        assertFalseChanged(result)
        assertEquals("Absence of a match in a later, unrelated import must not erase earlier legitimate evidence", existing, result.candidates.single().hostCorrelation)
    }

    // --- Static provenance preservation ---------------------------------------------------------

    @Test
    fun hostCorrelation_neverUpgradesProvenanceToRuntimeObserved() {
        val c = candidate("https://jsonplaceholder.typicode.com/posts", "jsonplaceholder.typicode.com", provenance = UrlProvenance.PRESENT_IN_DEX)
        val e = entry("https://jsonplaceholder.typicode.com/posts/1", "txn-2")

        val result = UrlEvidenceCorrelator.correlate(listOf(c), listOf(e), "analysis-1")

        assertEquals("A host-only correlation must never upgrade static extraction provenance", UrlProvenance.PRESENT_IN_DEX, result.candidates.single().provenance)
    }

    @Test
    fun noMatchAtAll_leavesCandidateEntirelyUntouched() {
        val c = candidate("https://example.com/unused", "example.com")
        val e = entry("https://httpbin.org/get", "txn-3")

        val result = UrlEvidenceCorrelator.correlate(listOf(c), listOf(e), "analysis-1")

        assertFalseChanged(result)
        assertSame("An unrelated candidate must come back as the exact same instance, not a needless copy", c, result.candidates.single())
    }

    // --- Explicit, deterministic tie rules for equal timestamps -------------------------------

    @Test
    fun equalTimestampAcrossBatches_existingStoredObservationWinsTheTie() {
        // A different transaction, from a different session, landing at the *exact same*
        // millisecond as what's already stored. Neither is objectively "newer" by timestamp alone —
        // the documented, deterministic rule is: the already-stored observation wins the tie.
        val existing = RuntimeEvidenceReference(
            sessionId = "analysis-1", transactionId = "txn-stored", url = "https://jsonplaceholder.typicode.com/posts/1",
            timestamp = 5_000L, method = "GET", statusCode = 200,
        )
        val c = candidate(
            "https://jsonplaceholder.typicode.com/posts/1", "jsonplaceholder.typicode.com",
            provenance = UrlProvenance.RUNTIME_OBSERVED, runtimeEvidence = existing,
        )
        val tiedIncoming = entry("https://jsonplaceholder.typicode.com/posts/1", "txn-tied", timestampEpochMs = 5_000L)

        val result = UrlEvidenceCorrelator.correlate(listOf(c), listOf(tiedIncoming), "analysis-1")

        assertFalseChanged(result)
        assertEquals("txn-stored", result.candidates.single().runtimeEvidence!!.transactionId)
    }

    @Test
    fun equalTimestampWithinBatch_higherTrafficSequenceWinsTheTie() {
        val c = candidate("https://jsonplaceholder.typicode.com/posts/1", "jsonplaceholder.typicode.com")
        // Two distinct entries in the *same* batch, identical timestamp, different trafficSequence.
        val earlierInSequence = entry("https://jsonplaceholder.typicode.com/posts/1", "txn-seq-1", timestampEpochMs = 5_000L, trafficSequence = 1L)
        val laterInSequence = entry("https://jsonplaceholder.typicode.com/posts/1", "txn-seq-2", timestampEpochMs = 5_000L, trafficSequence = 2L)

        // Deliberately listed with the higher-sequence entry FIRST, to prove the pick is not just
        // "whichever the input list happens to encounter first."
        val result = UrlEvidenceCorrelator.correlate(listOf(c), listOf(laterInSequence, earlierInSequence), "analysis-1")

        assertTrue(result.changed)
        assertEquals("txn-seq-2", result.candidates.single().runtimeEvidence!!.transactionId)
    }

    @Test
    fun equalTimestampAndSequence_lexicographicTransactionIdIsTheLastResortTiebreak() {
        val c = candidate("https://jsonplaceholder.typicode.com/posts/1", "jsonplaceholder.typicode.com")
        val a = entry("https://jsonplaceholder.typicode.com/posts/1", "txn-aaa", timestampEpochMs = 5_000L, trafficSequence = 1L)
        val b = entry("https://jsonplaceholder.typicode.com/posts/1", "txn-bbb", timestampEpochMs = 5_000L, trafficSequence = 1L)

        // Listed with the lexicographically-smaller id first, to prove the pick isn't input order.
        val result = UrlEvidenceCorrelator.correlate(listOf(c), listOf(a, b), "analysis-1")

        assertTrue(result.changed)
        assertEquals("txn-bbb", result.candidates.single().runtimeEvidence!!.transactionId)
    }

    /** [Result.changed] must be false whenever nothing about any candidate actually differs. */
    private fun assertFalseChanged(result: UrlEvidenceCorrelator.Result) {
        assertEquals(false, result.changed)
    }
}
