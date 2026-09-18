package com.nadeem.apkscope.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checkpoint 5, item 23/26: real Room persistence coverage for the Personal-side
 * [NetworkObservationDao] — the artifact-import idempotency guarantee ("pulling the same artifact
 * twice must not create duplicate observations") lives entirely at this persistence layer, so this
 * is where it is actually proven, independent of `SandboxSessionCoordinator`'s pull/validate plumbing.
 */
@RunWith(AndroidJUnit4::class)
class NetworkObservationDaoTest {
 private lateinit var database: SandboxDatabase
 private lateinit var dao: NetworkObservationDao

 @Before fun createDatabase() {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  database = Room.inMemoryDatabaseBuilder(context, SandboxDatabase::class.java).allowMainThreadQueries().build()
  dao = database.observationDao()
 }

 @After fun closeDatabase() { database.close() }

 private fun row(sessionId: String, sequence: Long, type: String = "ConnectionOpened") = NetworkObservationEntity(
  sessionId = sessionId, sequence = sequence, timestampEpochMs = sequence, type = type,
  protocol = "TCP", destinationIp = "1.2.3.4", destinationPort = 443, connectionId = sequence,
  startTimeEpochMs = null, endTimeEpochMs = null, uploadedBytes = null, downloadedBytes = null,
  failureReason = null, failureDetail = null, hostname = null, resolvedAddressesCsv = null,
  transactionId = null, sourcePort = null, limitName = null, currentValue = null, limitValue = null,
 )

 /** Item 23: re-importing the exact same artifact (same rows, same (sessionId, sequence) pairs) a second time must leave the row count unchanged. */
 @Test fun reimportingTheSameArtifactNeverDuplicatesRows() = runBlocking {
  val rows = listOf(row("s1", 1), row("s1", 2), row("s1", 3))
  dao.insertAll(rows)
  dao.insertAll(rows) // simulates a duplicate artifact pull/import (process-death retry, item 27C)
  assertEquals(3, dao.count("s1"))
 }

 @Test fun observationsForDifferentSessionsNeverCollideOnSequenceAlone() = runBlocking {
  dao.insertAll(listOf(row("s1", 1)))
  dao.insertAll(listOf(row("s2", 1)))
  assertEquals(1, dao.count("s1"))
  assertEquals(1, dao.count("s2"))
 }

 @Test fun observeForSessionReturnsAscendingSequenceOrder() = runBlocking {
  dao.insertAll(listOf(row("s1", 3), row("s1", 1), row("s1", 2)))
  val rows = dao.observeForSession("s1").first()
  assertEquals(listOf(1L, 2L, 3L), rows.map { it.sequence })
 }

 @Test fun summaryUpsertReplacesRatherThanDuplicating() = runBlocking {
  val first = RuntimeObservationSummaryEntity(
   sessionId = "s1", startedAtEpochMs = 0, endedAtEpochMs = 100, connectionCount = 1, dnsQueryCount = 0,
   uniqueObservedDomains = 0, uploadedBytes = 10, downloadedBytes = 20, blockedConnectionCount = 0, failedConnectionCount = 0,
   droppedObservationCount = 0, schemaVersion = 1, truncated = false, exportedObservationCount = 1, totalObservationCount = 1,
   importedAtEpochMs = 1000,
  )
  dao.upsertSummary(first)
  dao.upsertSummary(first.copy(connectionCount = 2, importedAtEpochMs = 2000)) // a re-import (e.g. after a retry) updates, not duplicates
  val loaded = dao.getSummary("s1")
  assertEquals(2, loaded?.connectionCount)
  assertEquals(2000L, loaded?.importedAtEpochMs)
 }
}
