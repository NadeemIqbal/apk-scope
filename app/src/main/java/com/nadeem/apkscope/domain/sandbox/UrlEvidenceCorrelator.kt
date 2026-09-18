package com.nadeem.apkscope.domain.sandbox

import com.nadeem.apkscope.core.staticanalysis.DexUrlCandidate
import com.nadeem.apkscope.core.staticanalysis.HostCorrelationInfo
import com.nadeem.apkscope.core.staticanalysis.RuntimeEvidenceReference
import com.nadeem.apkscope.core.staticanalysis.UrlProvenance
import com.nadeem.apkscope.sandbox.UrlEvidenceEntry

/**
 * Milestone 9 (URL evidence wiring / host-correlation fix, extracted 2026-09-13) — the actual
 * matching/merge decision between a persisted analysis's [DexUrlCandidate]s and a freshly-imported
 * batch of [UrlEvidenceEntry] captured traffic. Pulled out of
 * [SandboxSessionCoordinator.correlateUrlEvidenceWithAnalysis] into a pure function with no
 * Android/Log/disk dependency specifically so it can be unit-tested directly (no Robolectric, no
 * private-method reflection) — see `UrlEvidenceCorrelatorTest` for the full case matrix (exact
 * matches, same-host-different-path, lookalike hosts, redacted-query ambiguity, repeated imports,
 * distinct later observations, session/package mismatch handling upstream of this function,
 * provenance preservation).
 *
 * See [RuntimeEvidenceReference] and [HostCorrelationInfo]'s own doc comments for the evidence
 * identity and retention policy this function implements — it does not repeat that reasoning here.
 */
object UrlEvidenceCorrelator {

    /** [candidates] with matches applied; [changed] is true iff at least one candidate's [DexUrlCandidate.runtimeEvidence] or [DexUrlCandidate.hostCorrelation] was actually modified — callers should skip persistence entirely when false, rather than writing back an identical value. */
    data class Result(val candidates: List<DexUrlCandidate>, val changed: Boolean)

    /**
     * [analysisId] is stamped into any new/updated [RuntimeEvidenceReference.sessionId] /
     * [HostCorrelationInfo.sampleSessionId] — it identifies the persisted *analysis*, matching this
     * codebase's existing (pre-existing, not introduced by this pass) convention of using the
     * analysis id as the "sessionId" field on evidence records, not the sandbox session id that
     * produced [entries].
     */
    fun correlate(
        candidates: List<DexUrlCandidate>,
        entries: List<UrlEvidenceEntry>,
        analysisId: String,
    ): Result {
        var changed = false
        val updated = candidates.map { candidate ->
            val exactMatches = entries.filter { it.requestUrl == candidate.originalString }
            // Deterministic even if entries arrive out of capture order, and even if multiple
            // entries in this batch share the exact same timestamp: [mostRecent]'s tie-break order
            // is (timestamp, then trafficSequence, then transactionId) — never input-list position.
            val latestExact = mostRecent(exactMatches)

            if (latestExact != null) {
                val existing = candidate.runtimeEvidence
                when {
                    existing != null && existing.transactionId == latestExact.trafficTransactionId ->
                        // Same evidence re-imported (repeat artifact, retry after interruption,
                        // etc.) — idempotent no-op, not a duplicate and not a change.
                        candidate
                    existing != null && existing.timestamp >= latestExact.timestampEpochMs ->
                        // An already-stored observation is the same age or newer — never regress to
                        // an older one just because it appears in this batch too. **Explicit,
                        // deterministic tie rule for an exact timestamp match** (existing.timestamp
                        // == latestExact.timestampEpochMs, a different transaction id from a
                        // different session/request landing at the identical millisecond): the
                        // *already-stored* observation always wins — this branch's `>=` makes that
                        // the rule, not an accident of comparison order. See
                        // `equalTimestampAcrossBatches_existingStoredObservationWinsTheTie` for a
                        // test proving this exact case.
                        candidate
                    else -> {
                        changed = true
                        candidate.copy(
                            runtimeEvidence = RuntimeEvidenceReference(
                                sessionId = analysisId,
                                transactionId = latestExact.trafficTransactionId,
                                url = latestExact.requestUrl,
                                timestamp = latestExact.timestampEpochMs,
                                method = latestExact.requestMethod,
                                statusCode = latestExact.responseStatusCode,
                            ),
                            provenance = UrlProvenance.RUNTIME_OBSERVED,
                        )
                    }
                }
            } else {
                // No exact match for this candidate in this batch — a same-host, different-path
                // (or different-query) entry is a strictly weaker, non-exact signal. Never touches
                // runtimeEvidence/provenance (static extraction provenance is preserved).
                //
                // Matched by the entry's actual parsed host component, not a raw substring check —
                // a naive `requestUrl.contains(candidate.host)` would also match a lookalike host
                // that merely embeds candidate.host as a substring (e.g. an attacker-controlled
                // "jsonplaceholder.typicode.com.evil.example" would satisfy `contains` without
                // sharing a real host at all). [entryHost] below extracts the real host and compares
                // it for exact, case-insensitive equality against [candidate.host].
                val hostMatches = entries.filter { entryHost(it.requestUrl)?.equals(candidate.host, ignoreCase = true) == true }
                if (hostMatches.isEmpty()) {
                    // Nothing in *this* batch correlates with this host — leave any previously
                    // recorded hostCorrelation from an earlier, unrelated import untouched; absence
                    // of a match this time is not evidence the earlier match never happened.
                    candidate
                } else {
                    val sample = mostRecent(hostMatches)!!
                    val existing = candidate.hostCorrelation
                    if (existing != null && existing.observedTransactionCount == hostMatches.size &&
                        existing.sampleTransactionId == sample.trafficTransactionId
                    ) {
                        // Identical batch re-imported — idempotent no-op.
                        candidate
                    } else {
                        changed = true
                        candidate.copy(
                            hostCorrelation = HostCorrelationInfo(
                                host = candidate.host,
                                observedTransactionCount = hostMatches.size,
                                sampleTransactionId = sample.trafficTransactionId,
                                sampleSessionId = analysisId,
                            )
                        )
                    }
                }
            }
        }
        return Result(updated, changed)
    }

    /**
     * Picks the single most-recent entry from a batch of candidate matches, with an explicit,
     * fully deterministic tie-break order — never dependent on the input list's own order (which
     * `entries.maxByOrNull { it.timestampEpochMs }` alone would be, since [maxByOrNull] returns the
     * *first* maximal element it encounters when several tie). The order is:
     * 1. [UrlEvidenceEntry.timestampEpochMs] — later wins.
     * 2. [UrlEvidenceEntry.trafficSequence] — a same-millisecond tie is broken by this batch's own
     *    declared capture order (higher sequence = observed later within the same capture).
     * 3. [UrlEvidenceEntry.trafficTransactionId] (lexicographic) — the last-resort tiebreaker if
     *    both of the above are somehow also equal (two genuinely distinct transactions sharing both
     *    a timestamp and a sequence number should not occur in practice, but "pick whichever the
     *    list happened to have first" is not an acceptable fallback for a function whose whole
     *    purpose is deterministic evidence identity).
     */
    private fun mostRecent(entries: List<UrlEvidenceEntry>): UrlEvidenceEntry? =
        entries.maxWithOrNull(
            compareBy(
                { it.timestampEpochMs },
                { it.trafficSequence },
                { it.trafficTransactionId },
            )
        )

    /**
     * Extracts a URL's host component using real URI parsing, not a substring/regex heuristic —
     * so a lookalike like `https://jsonplaceholder.typicode.com.evil.example/x` (host is actually
     * `jsonplaceholder.typicode.com.evil.example`) is never mistaken for the real
     * `jsonplaceholder.typicode.com`. Returns null for a URL this parser cannot make sense of
     * (captured evidence is expected to always be a well-formed absolute URL by construction of
     * `TrafficRecord.url`, but a malformed/redacted-into-invalidity string must fail closed —
     * never match — rather than being coerced into a guess.)
     */
    private fun entryHost(url: String): String? = try {
        java.net.URI(url).host
    } catch (_: Exception) {
        null
    }
}
