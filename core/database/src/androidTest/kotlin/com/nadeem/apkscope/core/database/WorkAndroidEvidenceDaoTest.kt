package com.nadeem.apkscope.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkAndroidEvidenceDaoTest {
 private lateinit var database: WorkEvidenceDatabase
 private lateinit var dao: WorkAndroidEvidenceDao

 @Before
 fun createDatabase() {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  database = Room.inMemoryDatabaseBuilder(context, WorkEvidenceDatabase::class.java).allowMainThreadQueries().build()
  dao = database.workAndroidEvidenceDao()
 }

 @After
 fun closeDatabase() {
  database.close()
 }

 @Test
 fun insertAndRetrieveDnsEvents() = runBlocking {
  val dns1 = WorkAndroidDnsEvidenceEntity(
   sessionId = "s1",
   eventId = 101L,
   batchToken = 1L,
   packageName = "com.apksandbox.fixture",
   timestampEpochMs = 1000L,
   receivedAtEpochMs = 2000L,
   hostname = "example.com",
   resolvedAddressesCsv = "93.184.216.34",
   totalResolvedAddressCount = 1,
  )
  val dns2 = WorkAndroidDnsEvidenceEntity(
   sessionId = "s1",
   eventId = 102L,
   batchToken = 1L,
   packageName = "com.apksandbox.fixture",
   timestampEpochMs = 1050L,
   receivedAtEpochMs = 2000L,
   hostname = "api.example.com",
   resolvedAddressesCsv = "93.184.216.35,2606:2800:220:1:248:1893:25c8:1946",
   totalResolvedAddressCount = 2,
  )

  dao.insertDnsEvents(listOf(dns1, dns2))

  val retrieved = dao.getDnsEventsForSession("s1")
  assertEquals(2, retrieved.size)
  assertEquals(101L, retrieved[0].eventId)
  assertEquals("example.com", retrieved[0].hostname)
  assertEquals(102L, retrieved[1].eventId)
  assertEquals("api.example.com", retrieved[1].hostname)
 }

 @Test
 fun idempotentDnsInsertIgnoresDuplicates() = runBlocking {
  val dns = WorkAndroidDnsEvidenceEntity(
   sessionId = "s1",
   eventId = 101L,
   batchToken = 1L,
   packageName = "com.apksandbox.fixture",
   timestampEpochMs = 1000L,
   receivedAtEpochMs = 2000L,
   hostname = "example.com",
   resolvedAddressesCsv = "93.184.216.34",
   totalResolvedAddressCount = 1,
  )

  dao.insertDnsEvents(listOf(dns))
  // Re-insert same sessionId and eventId
  dao.insertDnsEvents(listOf(dns))

  val retrieved = dao.getDnsEventsForSession("s1")
  assertEquals(1, retrieved.size)
 }

 @Test
 fun insertAndRetrieveConnectEvents() = runBlocking {
  val conn1 = WorkAndroidConnectEvidenceEntity(
   sessionId = "s1",
   eventId = 201L,
   batchToken = 1L,
   packageName = "com.apksandbox.fixture",
   timestampEpochMs = 1100L,
   receivedAtEpochMs = 2000L,
   destinationAddress = "93.184.216.34",
   destinationPort = 443,
  )
  val conn2 = WorkAndroidConnectEvidenceEntity(
   sessionId = "s1",
   eventId = 202L,
   batchToken = 1L,
   packageName = "com.apksandbox.fixture",
   timestampEpochMs = 1200L,
   receivedAtEpochMs = 2000L,
   destinationAddress = "1.1.1.1",
   destinationPort = 80,
  )

  dao.insertConnectEvents(listOf(conn1, conn2))

  val retrieved = dao.getConnectEventsForSession("s1")
  assertEquals(2, retrieved.size)
  assertEquals(201L, retrieved[0].eventId)
  assertEquals("93.184.216.34", retrieved[0].destinationAddress)
  assertEquals(443, retrieved[0].destinationPort)
  assertEquals(202L, retrieved[1].eventId)
 }

 @Test
 fun idempotentConnectInsertIgnoresDuplicates() = runBlocking {
  val conn = WorkAndroidConnectEvidenceEntity(
   sessionId = "s1",
   eventId = 201L,
   batchToken = 1L,
   packageName = "com.apksandbox.fixture",
   timestampEpochMs = 1100L,
   receivedAtEpochMs = 2000L,
   destinationAddress = "93.184.216.34",
   destinationPort = 443,
  )

  dao.insertConnectEvents(listOf(conn))
  dao.insertConnectEvents(listOf(conn))

  val retrieved = dao.getConnectEventsForSession("s1")
  assertEquals(1, retrieved.size)
 }

 @Test
 fun summaryUpsertAndAcknowledgmentLifecycle() = runBlocking {
  val summary = WorkAndroidEvidenceSummaryEntity(
   sessionId = "s1",
   dnsCount = 2,
   connectCount = 3,
   status = "READY",
   firstEventTimestampEpochMs = 1000L,
   lastEventTimestampEpochMs = 1200L,
   acknowledgedByPersonal = false,
   updatedAtEpochMs = 2000L,
  )

  dao.upsertSummary(summary)

  val retrieved = dao.getSummary("s1")
  assertNotNull(retrieved)
  assertEquals("READY", retrieved?.status)
  assertEquals(false, retrieved?.acknowledgedByPersonal)

  dao.markAcknowledged("s1", 3000L)
  val updated = dao.getSummary("s1")
  assertEquals(true, updated?.acknowledgedByPersonal)
  assertEquals(3000L, updated?.updatedAtEpochMs)
 }

 @Test
 fun sessionDeletionCleansAllEvidence() = runBlocking {
  val dns = WorkAndroidDnsEvidenceEntity(
   sessionId = "s1",
   eventId = 101L,
   batchToken = 1L,
   packageName = "com.apksandbox.fixture",
   timestampEpochMs = 1000L,
   receivedAtEpochMs = 2000L,
   hostname = "example.com",
   resolvedAddressesCsv = "93.184.216.34",
   totalResolvedAddressCount = 1,
  )
  val conn = WorkAndroidConnectEvidenceEntity(
   sessionId = "s1",
   eventId = 201L,
   batchToken = 1L,
   packageName = "com.apksandbox.fixture",
   timestampEpochMs = 1100L,
   receivedAtEpochMs = 2000L,
   destinationAddress = "93.184.216.34",
   destinationPort = 443,
  )
  val summary = WorkAndroidEvidenceSummaryEntity(
   sessionId = "s1",
   dnsCount = 1,
   connectCount = 1,
   status = "READY",
   firstEventTimestampEpochMs = 1000L,
   lastEventTimestampEpochMs = 1100L,
   acknowledgedByPersonal = true,
   updatedAtEpochMs = 2000L,
  )

  dao.insertDnsEvents(listOf(dns))
  dao.insertConnectEvents(listOf(conn))
  dao.upsertSummary(summary)

  dao.deleteDnsForSession("s1")
  dao.deleteConnectForSession("s1")
  dao.deleteSummaryForSession("s1")

  assertTrue(dao.getDnsEventsForSession("s1").isEmpty())
  assertTrue(dao.getConnectEventsForSession("s1").isEmpty())
  assertEquals(null, dao.getSummary("s1"))
 }
}
