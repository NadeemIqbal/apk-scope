package com.nadeem.apkscope.core.model

import java.time.Instant

/**
 * Checkpoint 5, item 18: a factual, non-judgmental summary of one sandbox session's real network
 * activity — counts and byte totals only, never a risk score or a verdict. Computed once, at
 * session end, from the Work-local observation store (see `WorkNetworkObservationSink`/
 * `WorkNetworkObservationDao` in the `app`/`core:database` modules) and carried, unchanged, all the
 * way to Personal's own durable record via the runtime-observation artifact (item 19).
 *
 * [uniqueObservedDomains] counts distinct hostnames from real [NetworkObservation.DnsQuery] events
 * only (item 6 — a hostname is never derived from an IP address alone). [blockedConnectionCount]
 * counts [NetworkObservation.ConnectionFailed] events whose [NetworkObservation.FailureReason] is
 * [NetworkObservation.FailureReason.POLICY_DENIED] (item 31's decision: this engine's existing
 * `DestinationPolicy`-denied [NetworkObservation.ConnectionFailed] already carries everything a
 * separate `ConnectionBlocked` type would, so none was added — see this checkpoint's report).
 * [failedConnectionCount] counts every other [NetworkObservation.ConnectionFailed] reason.
 * [droppedObservationCount] is item 9's honest backpressure accounting — observations the bounded
 * pipeline could not keep up with, never silently absorbed into a lower total elsewhere.
 */
data class RuntimeObservationSummary(
 val sessionId: String,
 val startedAt: Instant,
 val endedAt: Instant,
 val connectionCount: Int,
 val dnsQueryCount: Int,
 val uniqueObservedDomains: Int,
 val uploadedBytes: Long,
 val downloadedBytes: Long,
 val blockedConnectionCount: Int,
 val failedConnectionCount: Int,
 val droppedObservationCount: Long,
)
