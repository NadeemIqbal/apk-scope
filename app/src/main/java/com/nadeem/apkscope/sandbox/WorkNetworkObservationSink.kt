package com.nadeem.apkscope.sandbox

import android.content.Context
import android.util.Log
import com.nadeem.apkscope.core.database.WorkEvidenceDatabaseProvider
import com.nadeem.apkscope.core.database.WorkNetworkObservationDao
import com.nadeem.apkscope.core.database.WorkNetworkObservationEntity
import com.nadeem.apkscope.core.database.WorkRuntimeSummaryEntity
import com.nadeem.apkscope.core.model.NetworkObservation
import com.nadeem.apkscope.core.model.NetworkObservationSink
import com.nadeem.apkscope.core.model.RuntimeObservationSummary
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Checkpoint 5, items 3/4/7/8/9/17: the real, production, Work-local [NetworkObservationSink] —
 * one instance per sandbox session (never reused across sessions, per item 4's anti-leakage
 * design: session B always gets a brand-new instance with its own [sessionId], so a stale/late
 * observation from an already-closed session A can never land in session B's rows). Composed
 * alongside (not replacing) [com.nadeem.apkscope.core.report.NetworkObservationLog] in
 * [SandboxVpnService] — this is the typed, queryable, Room-backed store the Live Monitor UI reads
 * from; the JSONL log remains the raw diagnostic trace it always was.
 *
 * Backpressure (item 8/9): `onObservation()` is called directly from the forwarding engine's
 * reader/selector threads, so it must never block on disk I/O. It does exactly one cheap,
 * non-blocking thing — [ArrayBlockingQueue.offer] — and returns. A dedicated writer thread (same
 * idiom as `NetworkObservationLog`'s own writer thread) drains the queue in batches and performs
 * the actual Room inserts. When the queue is full, `offer()` returns false and the observation is
 * dropped, with [droppedObservationCount] incremented — never silently absorbed, never blocking
 * the hot path to make room.
 *
 * This engine's observation model has no "per-packet incremental byte-update tick" event type —
 * every [NetworkObservation] subtype (Opened/Closed/Failed/DnsQuery/DnsResponse/
 * ResourceLimitExceeded) is already a discrete lifecycle/failure/resource event, not a
 * high-frequency progress tick. Item 9's "coalesce lower-value update events, retain
 * lifecycle/failure/resource-limit events" therefore has nothing to coalesce in this codebase —
 * there is no lower-value event class to distinguish from the rest. The policy actually
 * implemented is uniform bounded-drop-with-counting across all types, documented here rather than
 * forcing an artificial two-tier scheme onto a data model that doesn't have the distinction.
 */
class WorkNetworkObservationSink(
 context: Context,
 private val sessionId: String,
) : NetworkObservationSink {
 private val dao: WorkNetworkObservationDao = WorkEvidenceDatabaseProvider.get(context).workNetworkObservationDao()

 /** Item 8: bounded so the writer thread falling behind can never grow memory without limit. Sized well above any single batch this checkpoint's benchmarks (item 29) exercise. */
 private val queue = ArrayBlockingQueue<NetworkObservation>(4096)
 private val sequence = AtomicLong(0)
 private val dropped = AtomicLong(0)

 /** Item 4: once true, [onObservation] becomes a silent no-op — structurally prevents a race where a just-stopping engine's last few events land after this sink has already been asked to close. */
 @Volatile private var closed = false
 private val stopLatch = CountDownLatch(1)

 private val writer = Thread({
  val batch = ArrayList<NetworkObservation>(BATCH_SIZE)
  while (true) {
   val first = try { queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null }
   if (first == null) {
    if (closed && queue.isEmpty()) break
    continue
   }
   batch.add(first)
   queue.drainTo(batch, BATCH_SIZE - 1)
   writeBatch(batch)
   batch.clear()
   if (closed && queue.isEmpty()) break
  }
  stopLatch.countDown()
 }, "work-network-observation-writer").apply { isDaemon = true; start() }

 override fun onObservation(observation: NetworkObservation) {
  if (closed) return
  if (!queue.offer(observation)) {
   dropped.incrementAndGet()
   Log.w(TAG, "WorkNetworkObservationSink queue full for session $sessionId — observation dropped (total dropped=${dropped.get()})")
  }
 }

 private fun writeBatch(batch: List<NetworkObservation>) {
  val rows = batch.map { toEntity(sessionId, sequence.getAndIncrement(), it) }
  try { runBlocking { dao.insertAll(rows) } }
  catch (e: Exception) { Log.w(TAG, "WorkNetworkObservationSink batch write failed for session $sessionId: $e") }
 }

 /**
  * Item 17: stops accepting new observations, waits for the writer thread to drain and persist
  * everything already queued, then computes and persists the final [RuntimeObservationSummary] —
  * in that exact order, so no in-flight observation is lost to a summary computed too early.
  * Blocking (bounded by [FLUSH_TIMEOUT_MS]) is intentional: the caller (`SandboxVpnService.closeAll()`)
  * is expected to call this only after `ForwardingEngine.stop()` has already returned, i.e. off the
  * hot forwarding path entirely.
  */
 fun closeAndFinalize(startedAt: Instant, endedAt: Instant): RuntimeObservationSummary {
  closed = true
  stopLatch.await(FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  val summary = runBlocking { computeSummary(startedAt, endedAt) }
  runBlocking {
   dao.upsertSummary(
    WorkRuntimeSummaryEntity(
     sessionId = summary.sessionId,
     startedAtEpochMs = summary.startedAt.toEpochMilli(),
     endedAtEpochMs = summary.endedAt.toEpochMilli(),
     connectionCount = summary.connectionCount,
     dnsQueryCount = summary.dnsQueryCount,
     uniqueObservedDomains = summary.uniqueObservedDomains,
     uploadedBytes = summary.uploadedBytes,
     downloadedBytes = summary.downloadedBytes,
     blockedConnectionCount = summary.blockedConnectionCount,
     failedConnectionCount = summary.failedConnectionCount,
     droppedObservationCount = summary.droppedObservationCount,
    ),
   )
  }
  return summary
 }

 private suspend fun computeSummary(startedAt: Instant, endedAt: Instant): RuntimeObservationSummary {
  val rows = ArrayList<WorkNetworkObservationEntity>()
  var offset = 0
  while (true) {
   val page = dao.page(sessionId, PAGE_SIZE, offset)
   if (page.isEmpty()) break
   rows.addAll(page)
   offset += page.size
  }
  var connectionCount = 0
  var dnsQueryCount = 0
  var uploadedBytes = 0L
  var downloadedBytes = 0L
  var blockedConnectionCount = 0
  var failedConnectionCount = 0
  val domains = HashSet<String>()
  for (row in rows) {
   when (row.type) {
    "ConnectionOpened" -> connectionCount++
    "ConnectionClosed" -> { uploadedBytes += row.uploadedBytes ?: 0L; downloadedBytes += row.downloadedBytes ?: 0L }
    "ConnectionFailed" -> {
     if (row.failureReason == NetworkObservation.FailureReason.POLICY_DENIED.name) blockedConnectionCount++ else failedConnectionCount++
    }
    "DnsQuery" -> { dnsQueryCount++; row.hostname?.let { domains.add(it) } }
   }
  }
  return RuntimeObservationSummary(
   sessionId = sessionId,
   startedAt = startedAt,
   endedAt = endedAt,
   connectionCount = connectionCount,
   dnsQueryCount = dnsQueryCount,
   uniqueObservedDomains = domains.size,
   uploadedBytes = uploadedBytes,
   downloadedBytes = downloadedBytes,
   blockedConnectionCount = blockedConnectionCount,
   failedConnectionCount = failedConnectionCount,
   droppedObservationCount = dropped.get(),
  )
 }

 companion object {
  private const val TAG = "ApkScopeNet"
  private const val BATCH_SIZE = 200
  private const val POLL_TIMEOUT_MS = 200L
  private const val FLUSH_TIMEOUT_MS = 5000L
  private const val PAGE_SIZE = 500

  /** Mirrors `NetworkObservationLog.toJson`'s exact per-type field mapping — see that file for the parallel JSON encoding of the same sealed hierarchy. */
  fun toEntity(sessionId: String, sequence: Long, o: NetworkObservation): WorkNetworkObservationEntity = when (o) {
   is NetworkObservation.ConnectionOpened -> WorkNetworkObservationEntity(
    sessionId = sessionId, sequence = sequence, timestampEpochMs = o.timestamp.toEpochMilli(), type = "ConnectionOpened",
    protocol = o.protocol.name, destinationIp = o.destinationIp, destinationPort = o.destinationPort, connectionId = o.connectionId,
    startTimeEpochMs = null, endTimeEpochMs = null, uploadedBytes = null, downloadedBytes = null,
    failureReason = null, failureDetail = null, hostname = null, resolvedAddressesCsv = null,
    transactionId = null, sourcePort = null, limitName = null, currentValue = null, limitValue = null,
   )
   is NetworkObservation.ConnectionClosed -> WorkNetworkObservationEntity(
    sessionId = sessionId, sequence = sequence, timestampEpochMs = o.timestamp.toEpochMilli(), type = "ConnectionClosed",
    protocol = o.protocol.name, destinationIp = o.destinationIp, destinationPort = o.destinationPort, connectionId = o.connectionId,
    startTimeEpochMs = o.startTime.toEpochMilli(), endTimeEpochMs = o.endTime.toEpochMilli(),
    uploadedBytes = o.uploadedBytes, downloadedBytes = o.downloadedBytes,
    failureReason = null, failureDetail = null, hostname = null, resolvedAddressesCsv = null,
    transactionId = null, sourcePort = null, limitName = null, currentValue = null, limitValue = null,
   )
   is NetworkObservation.ConnectionFailed -> WorkNetworkObservationEntity(
    sessionId = sessionId, sequence = sequence, timestampEpochMs = o.timestamp.toEpochMilli(), type = "ConnectionFailed",
    protocol = o.protocol.name, destinationIp = o.destinationIp, destinationPort = o.destinationPort, connectionId = null,
    startTimeEpochMs = null, endTimeEpochMs = null, uploadedBytes = null, downloadedBytes = null,
    failureReason = o.reason.name, failureDetail = o.detail, hostname = null, resolvedAddressesCsv = null,
    transactionId = null, sourcePort = null, limitName = null, currentValue = null, limitValue = null,
   )
   is NetworkObservation.DnsQuery -> WorkNetworkObservationEntity(
    sessionId = sessionId, sequence = sequence, timestampEpochMs = o.timestamp.toEpochMilli(), type = "DnsQuery",
    protocol = null, destinationIp = null, destinationPort = null, connectionId = null,
    startTimeEpochMs = null, endTimeEpochMs = null, uploadedBytes = null, downloadedBytes = null,
    failureReason = null, failureDetail = null, hostname = o.hostname, resolvedAddressesCsv = null,
    transactionId = o.transactionId, sourcePort = o.sourcePort, limitName = null, currentValue = null, limitValue = null,
   )
   is NetworkObservation.DnsResponse -> WorkNetworkObservationEntity(
    sessionId = sessionId, sequence = sequence, timestampEpochMs = o.timestamp.toEpochMilli(), type = "DnsResponse",
    protocol = null, destinationIp = null, destinationPort = null, connectionId = null,
    startTimeEpochMs = null, endTimeEpochMs = null, uploadedBytes = null, downloadedBytes = null,
    failureReason = null, failureDetail = null, hostname = o.hostname, resolvedAddressesCsv = o.resolvedAddresses.joinToString(","),
    transactionId = o.transactionId, sourcePort = o.sourcePort, limitName = null, currentValue = null, limitValue = null,
   )
   is NetworkObservation.ResourceLimitExceeded -> WorkNetworkObservationEntity(
    sessionId = sessionId, sequence = sequence, timestampEpochMs = o.timestamp.toEpochMilli(), type = "ResourceLimitExceeded",
    protocol = null, destinationIp = null, destinationPort = null, connectionId = null,
    startTimeEpochMs = null, endTimeEpochMs = null, uploadedBytes = null, downloadedBytes = null,
    failureReason = null, failureDetail = null, hostname = null, resolvedAddressesCsv = null,
    transactionId = null, sourcePort = null, limitName = o.limitName, currentValue = o.currentValue, limitValue = o.limitValue,
   )
  }
 }
}
