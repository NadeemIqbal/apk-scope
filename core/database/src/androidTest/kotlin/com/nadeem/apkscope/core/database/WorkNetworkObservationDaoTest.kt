package com.nadeem.apkscope.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checkpoint 5, item 36: real Room persistence coverage for [WorkNetworkObservationDao] —
 * observation persistence/ordering, idempotent insert (item 23's dedup guarantee applies equally
 * to the Work-local table, not just the Personal-side import path), the aggregate `SUM`/`COUNT`
 * queries the Live Monitor's metrics grid reads (item 15/16), and the ack/retention behavior
 * (item 24/25).
 */
@RunWith(AndroidJUnit4::class)
class WorkNetworkObservationDaoTest {
 private lateinit var database: WorkEvidenceDatabase
 private lateinit var dao: WorkNetworkObservationDao

 @Before fun createDatabase() {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  database = Room.inMemoryDatabaseBuilder(context, WorkEvidenceDatabase::class.java).allowMainThreadQueries().build()
  dao = database.workNetworkObservationDao()
 }

 @After fun closeDatabase() { database.close() }

 private fun opened(sessionId: String, sequence: Long, connectionId: Long, destinationIp: String = "1.2.3.4", destinationPort: Int = 443, tsMs: Long = sequence) = WorkNetworkObservationEntity(
  sessionId = sessionId, sequence = sequence, timestampEpochMs = tsMs, type = "ConnectionOpened",
  protocol = "TCP", destinationIp = destinationIp, destinationPort = destinationPort, connectionId = connectionId,
  startTimeEpochMs = null, endTimeEpochMs = null, uploadedBytes = null, downloadedBytes = null,
  failureReason = null, failureDetail = null, hostname = null, resolvedAddressesCsv = null,
  transactionId = null, sourcePort = null, limitName = null, currentValue = null, limitValue = null,
 )

 private fun closed(sessionId: String, sequence: Long, connectionId: Long, uploadedBytes: Long, downloadedBytes: Long, tsMs: Long = sequence) = WorkNetworkObservationEntity(
  sessionId = sessionId, sequence = sequence, timestampEpochMs = tsMs, type = "ConnectionClosed",
  protocol = "TCP", destinationIp = "1.2.3.4", destinationPort = 443, connectionId = connectionId,
  startTimeEpochMs = tsMs - 10, endTimeEpochMs = tsMs, uploadedBytes = uploadedBytes, downloadedBytes = downloadedBytes,
  failureReason = null, failureDetail = null, hostname = null, resolvedAddressesCsv = null,
  transactionId = null, sourcePort = null, limitName = null, currentValue = null, limitValue = null,
 )

 private fun blocked(sessionId: String, sequence: Long, tsMs: Long = sequence) = WorkNetworkObservationEntity(
  sessionId = sessionId, sequence = sequence, timestampEpochMs = tsMs, type = "ConnectionFailed",
  protocol = "TCP", destinationIp = "192.168.1.1", destinationPort = 80, connectionId = null,
  startTimeEpochMs = null, endTimeEpochMs = null, uploadedBytes = null, downloadedBytes = null,
  failureReason = "POLICY_DENIED", failureDetail = "blocked", hostname = null, resolvedAddressesCsv = null,
  transactionId = null, sourcePort = null, limitName = null, currentValue = null, limitValue = null,
 )

 private fun dnsQuery(sessionId: String, sequence: Long, hostname: String, tsMs: Long = sequence) = WorkNetworkObservationEntity(
  sessionId = sessionId, sequence = sequence, timestampEpochMs = tsMs, type = "DnsQuery",
  protocol = null, destinationIp = null, destinationPort = null, connectionId = null,
  startTimeEpochMs = null, endTimeEpochMs = null, uploadedBytes = null, downloadedBytes = null,
  failureReason = null, failureDetail = null, hostname = hostname, resolvedAddressesCsv = null,
  transactionId = 1, sourcePort = 5000, limitName = null, currentValue = null, limitValue = null,
 )

 @Test fun observationsPersistAndPreserveSequenceOrdering() = runBlocking {
  dao.insertAll(listOf(opened("s1", 3, 1), opened("s1", 1, 2), opened("s1", 2, 3)))
  val rows = dao.observeForSession("s1").first()
  assertEquals(listOf(1L, 2L, 3L), rows.map { it.sequence })
 }

 /** Item 4/23: session A's rows never leak into session B's query, and duplicate (sessionId, sequence) is silently ignored, never duplicated. */
 @Test fun sessionAttributionAndIdempotentInsert() = runBlocking {
  dao.insertAll(listOf(opened("s1", 1, 1)))
  dao.insertAll(listOf(opened("s2", 1, 1))) // same sequence number, different session — must not collide
  dao.insertAll(listOf(opened("s1", 1, 1))) // exact duplicate — must be ignored, not duplicated

  assertEquals(1, dao.count("s1"))
  assertEquals(1, dao.count("s2"))
  assertTrue(dao.observeForSession("s1").first().none { it.sessionId != "s1" })
 }

 @Test fun aggregateQueriesReflectWholeSessionRegardlessOfFeedWindow() = runBlocking {
  dao.insertAll(listOf(
   opened("s1", 1, 1), closed("s1", 2, 1, uploadedBytes = 1000L, downloadedBytes = 2000L),
   opened("s1", 3, 2), closed("s1", 4, 2, uploadedBytes = 500L, downloadedBytes = 700L),
   blocked("s1", 5),
   dnsQuery("s1", 6, "api.example.com"), dnsQuery("s1", 7, "api.example.com"), dnsQuery("s1", 8, "other.example.com"),
  ))
  assertEquals(2, dao.observeConnectionCount("s1").first())
  assertEquals(2, dao.observeDomainCount("s1").first()) // distinct hostnames only
  assertEquals(1500L, dao.observeUploadedBytes("s1").first())
  assertEquals(2700L, dao.observeDownloadedBytes("s1").first())
  assertEquals(1, dao.observeBlockedCount("s1").first())
 }

 /** Item 16: observeLatestForSession never returns more than [limit] rows even when the session holds many more, and it is always the *most recent* ones. */
 @Test fun observeLatestForSessionIsBoundedToNewestRows() = runBlocking {
  dao.insertAll((1..50).map { opened("s1", it.toLong(), it.toLong()) })
  val latest = dao.observeLatestForSession("s1", 10).first()
  assertEquals(10, latest.size)
  assertEquals((41L..50L).toList().reversed(), latest.map { it.sequence })
 }

 /** Item 24/25: acknowledgement and the resulting retention deletion are two independent, explicit steps — never conflated. */
 @Test fun acknowledgementMarksSummaryWithoutDeletingRowsByItself() = runBlocking {
  dao.insertAll(listOf(opened("s1", 1, 1)))
  dao.upsertSummary(WorkRuntimeSummaryEntity(sessionId = "s1", startedAtEpochMs = 0, endedAtEpochMs = 100, connectionCount = 1, dnsQueryCount = 0, uniqueObservedDomains = 0, uploadedBytes = 0, downloadedBytes = 0, blockedConnectionCount = 0, failedConnectionCount = 0, droppedObservationCount = 0))
  assertEquals(false, dao.getSummary("s1")?.acknowledgedByPersonal)

  dao.markSummaryAcknowledged("s1")
  assertEquals(true, dao.getSummary("s1")?.acknowledgedByPersonal)
  assertEquals(1, dao.count("s1")) // rows untouched by ack alone

  dao.deleteForSession("s1") // the retention step SandboxWorkerService.runAckRuntimeArtifact performs separately
  assertEquals(0, dao.count("s1"))
 }
}
