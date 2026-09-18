package com.nadeem.apkscope.core.network

import android.util.Log

/**
 * Verbose per-connection lifecycle tracing built for the Forwarding Reliability Root Cause Gate
 * and the Upload Throughput Root Cause Gate (see `HARDENING_GATE_ROOT_CAUSE.md` and
 * `UPLOAD_THROUGHPUT_ROOT_CAUSE.md`) and kept as permanent, disabled-by-default diagnostic
 * instrumentation on promotion into `core:network` — real defects were found with it more than
 * once, and the engine's own hot paths already tolerate its presence (every call is cheap and
 * exception-safe), so deleting it would remove real debugging capability for a production sandbox
 * with real users' traffic flowing through it. Every timestamp is [System.nanoTime] — monotonic,
 * immune to wall-clock adjustment, and (unlike `SystemClock.elapsedRealtimeNanos`) usable from the
 * plain-JVM unit tests this module's [TcpProxy]/[UdpNat] tests run under without an Android
 * runtime — because every duration this investigation cares about is a difference between two of
 * these events for the same connection ID, never a wall-clock read. Uses only `Log.*` (the async
 * ring-buffer logger), never file I/O — this class must not itself introduce a hot-path stall.
 * Every call is wrapped so that a JVM without Android's `Log` (i.e. these unit tests) degrades to
 * a no-op instead of throwing "not mocked". [enabled] defaults to `false`: a v0.1 debug build (or
 * a support flow investigating a specific report) can flip it at runtime; a release build should
 * never pay its logging cost by default.
 */
object ConnDiag {
 @Volatile var enabled = false
 private const val TAG = "ConnDiag"

 /** One lifecycle event for connection [connId]. See the class-level list in the root-cause doc for the full event vocabulary. */
 fun event(connId: Long, event: String, detail: String = "") {
  if (!enabled) return
//  safeLog { Log.i(TAG, "EVENT|$connId|$event|${System.nanoTime()}|$detail") }
 }

 /** One row per selector-loop iteration: cheap enough to leave on for the whole gate, not just failures. */
 fun selectorTick(iterationNanos: Long, selectNanos: Long, selectedKeys: Int, activeTcp: Int, activeUdp: Int, globalBufferedBytes: Long, threadCount: Int) {
  if (!enabled) return
//  safeLog { Log.i(TAG, "TICK|${System.nanoTime()}|iterationNs=$iterationNanos|selectNs=$selectNanos|selectedKeys=$selectedKeys|activeTcp=$activeTcp|activeUdp=$activeUdp|globalBufferedBytes=$globalBufferedBytes|threads=$threadCount") }
 }

 /** [severityMs] is the threshold that fired (100/500/1000), not the actual duration — [iterationNanos] carries that. */
 fun selectorStall(severityMs: Int, iterationNanos: Long, detail: String) {
//  safeLog { Log.w(TAG, "STALL_${severityMs}ms|${System.nanoTime()}|iterationNs=$iterationNanos|$detail") }
  if (severityMs >= 500) {
   // Thread.getAllStackTraces() is a plain public JDK API, not a hidden/unsupported one — cheap
   // enough to call only on the rarer >=500ms stalls, not on every tick.
   val dump = try {
    Thread.getAllStackTraces().entries.joinToString(" || ") { (t, frames) ->
     "${t.name}(${t.state})=" + frames.take(6).joinToString(">") { "${it.className}.${it.methodName}:${it.lineNumber}" }
    }
   } catch (e: Throwable) { "unavailable: ${e.javaClass.simpleName}" }
//   safeLog { Log.w(TAG, "STALL_THREADS|${System.nanoTime()}|$dump") }
  }
 }

 fun invariantViolation(check: String, detail: String) { safeLog { Log.e(TAG, "INVARIANT_VIOLATION|${System.nanoTime()}|$check|$detail") } }

 // --- Upload Throughput Root Cause Gate additions below. Same rules as above: System.nanoTime()
 // only, every call wrapped in safeLog, aggregate-by-interval rather than per-byte/per-packet. ---

 /**
  * One row per connection per sampling interval (~100-250ms, driven by actual traffic rather than
  * a dedicated timer thread — see [TcpProxy]'s call sites) for the upload (client -> real
  * destination) direction. All byte counters are cumulative-since-connection-start, not deltas —
  * a post-hoc analysis script derives per-interval throughput from consecutive rows.
  */
 fun uploadSample(
  connId: Long, elapsedNanos: Long, bytesReceivedFromTun: Long, bytesQueuedForUpstream: Long,
  bytesWrittenToSocket: Long, pendingUpstreamBytes: Int, tunPacketsReceived: Long, socketWriteCalls: Long,
  partialWrites: Long, zeroByteWrites: Long, interestOps: Int, globalBufferedBytes: Long, tcbQueuedBytes: Int,
 ) {
  if (!enabled) return
//  safeLog {
//   Log.i(TAG, "UPLOAD_SAMPLE|$connId|${System.nanoTime()}|elapsedNs=$elapsedNanos|bytesReceivedFromTun=$bytesReceivedFromTun|" +
//    "bytesQueuedForUpstream=$bytesQueuedForUpstream|bytesWrittenToSocket=$bytesWrittenToSocket|pendingUpstreamBytes=$pendingUpstreamBytes|" +
//    "tunPacketsReceived=$tunPacketsReceived|socketWriteCalls=$socketWriteCalls|partialWrites=$partialWrites|zeroByteWrites=$zeroByteWrites|" +
//    "interestOps=$interestOps|globalBufferedBytes=$globalBufferedBytes|tcbQueuedBytes=$tcbQueuedBytes")
//  }
 }

 /** Same idea as [uploadSample], for the download (real destination -> client) direction. */
 fun downloadSample(connId: Long, elapsedNanos: Long, bytesReadFromSocket: Long, bytesWrittenToTun: Long, pendingDownstreamBytes: Int, clientWindow: Int, outstanding: Long) {
  if (!enabled) return
//  safeLog {
//   Log.i(TAG, "DOWNLOAD_SAMPLE|$connId|${System.nanoTime()}|elapsedNs=$elapsedNanos|bytesReadFromSocket=$bytesReadFromSocket|" +
//    "bytesWrittenToTun=$bytesWrittenToTun|pendingDownstreamBytes=$pendingDownstreamBytes|clientWindow=$clientWindow|outstanding=$outstanding")
//  }
 }

 /**
  * Item 4 of the upload gate: periodic snapshot of the [ForwardingEngine]-wide selector-thread task
  * queue — [queuedTotal]/[executedTotal] are cumulative counts (a delta over the sampling period is
  * derived post-hoc), [currentDepth] is the queue length right now, [maxDepthObserved] is the
  * high-water mark since the engine started, and the two wait-time sums let a post-hoc script divide
  * by the executed-count delta for an average without this hot path doing any division itself.
  */
 fun taskQueueSample(queuedTotal: Long, executedTotal: Long, currentDepth: Int, maxDepthObserved: Int, waitNanosSum: Long, waitNanosMax: Long) {
  if (!enabled) return
//  safeLog {
//   Log.i(TAG, "TASKQUEUE_SAMPLE|${System.nanoTime()}|queuedTotal=$queuedTotal|executedTotal=$executedTotal|currentDepth=$currentDepth|" +
//    "maxDepthObserved=$maxDepthObserved|waitNanosSum=$waitNanosSum|waitNanosMax=$waitNanosMax")
//  }
 }

 /**
  * A single enqueued selector-thread task's requested interestOps, tagged by connection — lets a
  * post-hoc analysis see runs of identical consecutive values (item 4: "if thousands of identical
  * enable OP_WRITE tasks accumulate"). Cheap enough to log every enqueue since it's one int, not a
  * buffer copy.
  */
 fun interestOpsRequested(connId: Long, ops: Int, coalesced: Boolean) {
  if (!enabled) return
//  safeLog { Log.i(TAG, "INTERESTOPS_REQUESTED|$connId|${System.nanoTime()}|ops=$ops|coalesced=$coalesced") }
 }

 /** Item 5: aggregate TCB lock contention snapshot — see [LockDiag]. Global across all connections; this gate cares about the worst case, not which specific connection caused it. */
 fun lockStatsSample(acquireCount: Long, waitNanosSum: Long, waitNanosMax: Long, holdNanosSum: Long, holdNanosMax: Long) {
  if (!enabled) return
//  safeLog {
//   Log.i(TAG, "LOCK_STATS_SAMPLE|${System.nanoTime()}|acquireCount=$acquireCount|waitNanosSum=$waitNanosSum|waitNanosMax=$waitNanosMax|holdNanosSum=$holdNanosSum|holdNanosMax=$holdNanosMax")
//  }
 }

 private inline fun safeLog(block: () -> Unit) { try { block() } catch (_: Throwable) {} }
}

/**
 * Item 5 of the Upload Throughput Root Cause Gate: aggregate, process-wide TCB lock wait/hold
 * timing. Deliberately global rather than per-connection — a single mixed-load run can have dozens
 * of TCBs, and what this gate needs is "was any connection ever blocked waiting for a TCB lock for
 * a suspicious amount of time", not a per-connection breakdown. Temporary, same lifecycle as
 * [ConnDiag] — delete alongside it once root causes are understood.
 */
object LockDiag {
 private val acquireCount = java.util.concurrent.atomic.AtomicLong(0)
 private val waitNanosSum = java.util.concurrent.atomic.AtomicLong(0)
 private val waitNanosMax = java.util.concurrent.atomic.AtomicLong(0)
 private val holdNanosSum = java.util.concurrent.atomic.AtomicLong(0)
 private val holdNanosMax = java.util.concurrent.atomic.AtomicLong(0)

 fun recordWait(nanos: Long) {
  waitNanosSum.addAndGet(nanos)
  waitNanosMax.updateAndGet { maxOf(it, nanos) }
 }

 fun recordHold(nanos: Long) {
  acquireCount.incrementAndGet()
  holdNanosSum.addAndGet(nanos)
  holdNanosMax.updateAndGet { maxOf(it, nanos) }
 }

 fun emitSample() {
  ConnDiag.lockStatsSample(acquireCount.get(), waitNanosSum.get(), waitNanosMax.get(), holdNanosSum.get(), holdNanosMax.get())
 }
}
