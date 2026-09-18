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
class AndroidEvidenceDaoTest {
 private lateinit var database: SandboxDatabase
 private lateinit var dao: AndroidEvidenceDao

 @Before
 fun createDatabase() {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  database = Room.inMemoryDatabaseBuilder(context, SandboxDatabase::class.java).allowMainThreadQueries().build()
  dao = database.androidEvidenceDao()
 }

 @After
 fun closeDatabase() {
  database.close()
 }

 @Test
 fun insertAndRetrievePersonalDnsEvidence() = runBlocking {
  val dns = AndroidDnsEvidenceEntity(
   sessionId = "s2",
   eventId = 301L,
   batchToken = 2L,
   packageName = "com.apksandbox.fixture",
   timestampEpochMs = 1500L,
   receivedAtEpochMs = 2500L,
   hostname = "google.com",
   resolvedAddressesCsv = "142.250.190.46",
   totalResolvedAddressCount = 1,
  )

  dao.insertDnsEvents(listOf(dns))

  val retrieved = dao.getDnsEventsForSession("s2")
  assertEquals(1, retrieved.size)
  assertEquals(301L, retrieved[0].eventId)
  assertEquals("google.com", retrieved[0].hostname)
 }

 @Test
 fun insertAndRetrievePersonalConnectEvidence() = runBlocking {
  val conn = AndroidConnectEvidenceEntity(
   sessionId = "s2",
   eventId = 401L,
   batchToken = 2L,
   packageName = "com.apksandbox.fixture",
   timestampEpochMs = 1600L,
   receivedAtEpochMs = 2500L,
   destinationAddress = "142.250.190.46",
   destinationPort = 443,
  )

  dao.insertConnectEvents(listOf(conn))

  val retrieved = dao.getConnectEventsForSession("s2")
  assertEquals(1, retrieved.size)
  assertEquals(401L, retrieved[0].eventId)
  assertEquals("142.250.190.46", retrieved[0].destinationAddress)
 }

 @Test
 fun personalSummaryLifecycle() = runBlocking {
  val summary = AndroidEvidenceSummaryEntity(
   sessionId = "s2",
   dnsCount = 1,
   connectCount = 1,
   status = "READY",
   firstEventTimestampEpochMs = 1500L,
   lastEventTimestampEpochMs = 1600L,
   importedAtEpochMs = 2600L,
  )

  dao.upsertSummary(summary)

  val retrieved = dao.getSummary("s2")
  assertNotNull(retrieved)
  assertEquals("READY", retrieved?.status)
  assertEquals(1, retrieved?.dnsCount)
  assertEquals(1, retrieved?.connectCount)
 }
}
