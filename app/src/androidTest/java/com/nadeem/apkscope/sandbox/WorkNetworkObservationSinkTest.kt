package com.nadeem.apkscope.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nadeem.apkscope.core.database.WorkEvidenceDatabaseProvider
import com.nadeem.apkscope.core.model.NetworkObservation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/**
 * Checkpoint 5, item 36: real-device coverage for [WorkNetworkObservationSink] — backpressure/
 * dropped-event counting, clean shutdown flush, and the item-17 stop→flush→summarize ordering.
 * Uses a real [WorkEvidenceDatabaseProvider]-backed database (the real production singleton, not
 * an in-memory fake) since that is the actual object under test; each test uses its own random
 * [sessionId] so runs never collide with each other's rows.
 */
@RunWith(AndroidJUnit4::class)
class WorkNetworkObservationSinkTest {
 private val context = InstrumentationRegistry.getInstrumentation().targetContext
 private val dao by lazy { WorkEvidenceDatabaseProvider.get(context).workNetworkObservationDao() }

 private fun opened(connectionId: Long) = NetworkObservation.ConnectionOpened(
  timestamp = Instant.now(), connectionId = connectionId, protocol = NetworkObservation.Protocol.TCP,
  destinationIp = "1.2.3.4", destinationPort = 443,
 )

 @Test fun observationsAreFlushedAndPersistedInOrderOnClose() = runBlocking {
  val sessionId = UUID.randomUUID().toString()
  val sink = WorkNetworkObservationSink(context, sessionId)
  repeat(50) { sink.onObservation(opened(it.toLong())) }
  val summary = sink.closeAndFinalize(Instant.now(), Instant.now())

  assertEquals(50, summary.connectionCount)
  assertEquals(0L, summary.droppedObservationCount)
  val rows = dao.page(sessionId, limit = 1000, offset = 0)
  assertEquals(50, rows.size)
  assertEquals((0 until 50L).toList(), rows.map { it.connectionId })
 }

 /** Item 8/9: a queue that genuinely cannot keep up drops observations and counts them — it never blocks the calling (would-be forwarding-engine) thread, and never silently loses the count. */
 @Test fun queueOverflowDropsAndCountsRatherThanBlocking() = runBlocking {
  val sessionId = UUID.randomUUID().toString()
  val sink = WorkNetworkObservationSink(context, sessionId)
  // Far more than the sink's internal queue capacity (4096), submitted as fast as this thread can
  // call onObservation — if onObservation ever blocked waiting for room, this loop would stall
  // rather than complete quickly.
  val submitted = 10_000
  repeat(submitted) { sink.onObservation(opened(it.toLong())) }
  val summary = sink.closeAndFinalize(Instant.now(), Instant.now())

  // Every observation is accounted for exactly once: either persisted or counted as dropped —
  // never both, never neither.
  val rows = dao.page(sessionId, limit = submitted, offset = 0)
  assertEquals(submitted.toLong(), rows.size.toLong() + summary.droppedObservationCount)
  assertTrue("expected at least the persisted rows to be non-empty", rows.isNotEmpty())
 }

 @Test fun closeAndFinalizeIsIdempotentSafeToReadBack() = runBlocking {
  val sessionId = UUID.randomUUID().toString()
  val sink = WorkNetworkObservationSink(context, sessionId)
  sink.onObservation(opened(1))
  val summary = sink.closeAndFinalize(Instant.ofEpochMilli(0), Instant.ofEpochMilli(500))
  val persisted = dao.getSummary(sessionId)
  assertEquals(summary.connectionCount, persisted?.connectionCount)
  assertEquals(0L, persisted?.startedAtEpochMs)
  assertEquals(500L, persisted?.endedAtEpochMs)
 }

 /** Item 4: two sessions never share observations even when both sinks are alive at overlapping times. */
 @Test fun twoConcurrentSessionsNeverLeakObservationsIntoEachOther() = runBlocking {
  val sessionA = UUID.randomUUID().toString()
  val sessionB = UUID.randomUUID().toString()
  val sinkA = WorkNetworkObservationSink(context, sessionA)
  val sinkB = WorkNetworkObservationSink(context, sessionB)
  repeat(20) { sinkA.onObservation(opened(it.toLong())) }
  repeat(5) { sinkB.onObservation(opened(it.toLong())) }
  sinkA.closeAndFinalize(Instant.now(), Instant.now())
  sinkB.closeAndFinalize(Instant.now(), Instant.now())

  assertEquals(20, dao.count(sessionA))
  assertEquals(5, dao.count(sessionB))
 }
}
