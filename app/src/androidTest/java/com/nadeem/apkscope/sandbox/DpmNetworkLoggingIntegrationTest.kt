package com.nadeem.apkscope.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nadeem.apkscope.core.database.AndroidConnectEvidenceEntity
import com.nadeem.apkscope.core.database.AndroidDnsEvidenceEntity
import com.nadeem.apkscope.core.database.AndroidEvidenceSummaryEntity
import com.nadeem.apkscope.core.database.SandboxDatabaseProvider
import com.nadeem.apkscope.core.database.WorkEvidenceDatabaseProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Checkpoint 6: End-to-end integration test for DPM Network Logging evidence collection.
 * Verifies:
 * 1. Work profile evidence sink records raw DPM network events into Work evidence tables.
 * 2. Serialization and validation of the [AndroidEvidenceArtifact].
 * 3. Ingestion into Personal profile evidence database tables without data corruption.
 * 4. Strict segregation between DPM evidence and VPN observations (no cross-table mutation).
 * 5. Lifecycle acknowledgement clearing Work profile event rows while retaining summary facts.
 */
@RunWith(AndroidJUnit4::class)
class DpmNetworkLoggingIntegrationTest {
 private val context = InstrumentationRegistry.getInstrumentation().targetContext
 private val workEvidenceDb by lazy { WorkEvidenceDatabaseProvider.get(context) }
 private val workDao by lazy { workEvidenceDb.workAndroidEvidenceDao() }
 private val personalDb by lazy { SandboxDatabaseProvider.get(context) }
 private val personalDao by lazy { personalDb.androidEvidenceDao() }

 @Before
 fun setup() {
  WorkAndroidEvidenceSink.currentSessionId = null
  WorkAndroidEvidenceSink.currentTargetPackage = null
 }

 @Test
 fun fullDpmEvidencePipeline_record_export_validate_persist_and_ack() = runBlocking {
  val sessionId = UUID.randomUUID().toString()
  val targetPkg = "com.apksandbox.testtarget"
  val batchToken = 42001L

  WorkAndroidEvidenceSink.currentSessionId = sessionId
  WorkAndroidEvidenceSink.currentTargetPackage = targetPkg

  // 1. Simulate DPM network events
  val dns1 = TestNetworkEventFactory.createDnsEvent(
   id = 1001L,
   hostname = "auth.example.org",
   ipAddresses = arrayOf("198.51.100.1", "198.51.100.2"),
   ipAddressesCount = 2,
   packageName = targetPkg,
   timestamp = 10_000L,
  )
  val dns2 = TestNetworkEventFactory.createDnsEvent(
   id = 1002L,
   hostname = "telemetry.example.org",
   ipAddresses = arrayOf("198.51.100.3"),
   ipAddressesCount = 1,
   packageName = targetPkg,
   timestamp = 11_000L,
  )
  val connect1 = TestNetworkEventFactory.createConnectEvent(
   id = 2001L,
   ipAddress = "198.51.100.1",
   port = 443,
   packageName = targetPkg,
   timestamp = 10_500L,
  )
  val connect2 = TestNetworkEventFactory.createConnectEvent(
   id = 2002L,
   ipAddress = "198.51.100.3",
   port = 8443,
   packageName = targetPkg,
   timestamp = 11_200L,
  )

  // 2. Ingest events into Work profile evidence sink
  val recordJob = WorkAndroidEvidenceSink.recordEvents(
   context = context,
   batchToken = batchToken,
   events = listOf(dns1, dns2, connect1, connect2),
  )
  recordJob?.join()

  // Verify Work evidence DB state
  val workDnsRows = workDao.getDnsEventsForSession(sessionId)
  val workConnectRows = workDao.getConnectEventsForSession(sessionId)
  val workSummary = workDao.getSummary(sessionId)

  assertEquals(2, workDnsRows.size)
  assertEquals(2, workConnectRows.size)
  assertNotNull(workSummary)
  assertEquals(2, workSummary!!.dnsCount)
  assertEquals(2, workSummary.connectCount)
  assertEquals("READY", workSummary.status)
  assertEquals(10_000L, workSummary.firstEventTimestampEpochMs)
  assertEquals(11_200L, workSummary.lastEventTimestampEpochMs)
  assertFalse(workSummary.acknowledgedByPersonal)

  // 3. Construct export artifact
  val artifact = AndroidEvidenceArtifact(
   schemaVersion = AndroidEvidenceArtifact.CURRENT_SCHEMA_VERSION,
   sessionId = sessionId,
   targetPackageName = targetPkg,
   status = workSummary.status,
   dnsEvents = workDnsRows.map { r ->
    AndroidDnsEvidenceEntry(
     eventId = r.eventId,
     batchToken = r.batchToken,
     packageName = r.packageName,
     timestampEpochMs = r.timestampEpochMs,
     receivedAtEpochMs = r.receivedAtEpochMs,
     hostname = r.hostname,
     resolvedAddressesCsv = r.resolvedAddressesCsv,
     totalResolvedAddressCount = r.totalResolvedAddressCount,
    )
   },
   connectEvents = workConnectRows.map { r ->
    AndroidConnectEvidenceEntry(
     eventId = r.eventId,
     batchToken = r.batchToken,
     packageName = r.packageName,
     timestampEpochMs = r.timestampEpochMs,
     receivedAtEpochMs = r.receivedAtEpochMs,
     destinationAddress = r.destinationAddress,
     destinationPort = r.destinationPort,
    )
   },
   firstEventTimestampEpochMs = workSummary.firstEventTimestampEpochMs,
   lastEventTimestampEpochMs = workSummary.lastEventTimestampEpochMs,
  )

  // 4. Verify serialization round-trip and security validation
  val jsonString = artifact.toJson().toString()
  val parsedArtifact = AndroidEvidenceArtifact.fromJson(jsonString)
  val validationRejection = AndroidEvidenceArtifact.validate(parsedArtifact, sessionId, targetPkg)
  assertNull("Artifact validation should succeed", validationRejection)

  // 5. Ingest into Personal Profile Database
  val personalDnsEntities = parsedArtifact.dnsEvents.map { e ->
   AndroidDnsEvidenceEntity(
    sessionId = sessionId,
    eventId = e.eventId,
    batchToken = e.batchToken,
    packageName = e.packageName,
    timestampEpochMs = e.timestampEpochMs,
    receivedAtEpochMs = e.receivedAtEpochMs,
    hostname = e.hostname,
    resolvedAddressesCsv = e.resolvedAddressesCsv,
    totalResolvedAddressCount = e.totalResolvedAddressCount,
   )
  }
  val personalConnectEntities = parsedArtifact.connectEvents.map { e ->
   AndroidConnectEvidenceEntity(
    sessionId = sessionId,
    eventId = e.eventId,
    batchToken = e.batchToken,
    packageName = e.packageName,
    timestampEpochMs = e.timestampEpochMs,
    receivedAtEpochMs = e.receivedAtEpochMs,
    destinationAddress = e.destinationAddress,
    destinationPort = e.destinationPort,
   )
  }
  personalDao.insertDnsEvents(personalDnsEntities)
  personalDao.insertConnectEvents(personalConnectEntities)
  personalDao.upsertSummary(
   AndroidEvidenceSummaryEntity(
    sessionId = sessionId,
    dnsCount = personalDnsEntities.size,
    connectCount = personalConnectEntities.size,
    status = parsedArtifact.status,
    firstEventTimestampEpochMs = parsedArtifact.firstEventTimestampEpochMs,
    lastEventTimestampEpochMs = parsedArtifact.lastEventTimestampEpochMs,
    importedAtEpochMs = System.currentTimeMillis(),
   )
  )

  // 6. Verify Personal Database state
  val personalDns = personalDao.getDnsEventsForSession(sessionId)
  val personalConnect = personalDao.getConnectEventsForSession(sessionId)
  val personalSummary = personalDao.getSummary(sessionId)

  assertEquals(2, personalDns.size)
  assertEquals(2, personalConnect.size)
  assertNotNull(personalSummary)
  assertEquals(2, personalSummary!!.dnsCount)
  assertEquals(2, personalSummary.connectCount)
  assertEquals("READY", personalSummary.status)
  assertEquals(10_000L, personalSummary.firstEventTimestampEpochMs)
  assertEquals(11_200L, personalSummary.lastEventTimestampEpochMs)

  // Verify DNS contents
  assertEquals("auth.example.org", personalDns[0].hostname)
  assertEquals("198.51.100.1,198.51.100.2", personalDns[0].resolvedAddressesCsv)
  assertEquals(2, personalDns[0].totalResolvedAddressCount)
  assertEquals("telemetry.example.org", personalDns[1].hostname)

  // Verify Connect contents
  assertEquals("198.51.100.1", personalConnect[0].destinationAddress)
  assertEquals(443, personalConnect[0].destinationPort)
  assertEquals("198.51.100.3", personalConnect[1].destinationAddress)
  assertEquals(8443, personalConnect[1].destinationPort)

  // 7. Verify segregation: VPN observation tables must remain unaffected
  val vpnDao = workEvidenceDb.workNetworkObservationDao()
  assertEquals(0, vpnDao.count(sessionId))

  // 8. Simulate Personal profile ACK receipt on Work side
  workDao.markAcknowledged(sessionId, System.currentTimeMillis())
  workDao.deleteDnsForSession(sessionId)
  workDao.deleteConnectForSession(sessionId)

  val postAckSummary = workDao.getSummary(sessionId)
  assertNotNull(postAckSummary)
  assertTrue(postAckSummary!!.acknowledgedByPersonal)
  assertTrue("Work DNS rows cleared after ACK", workDao.getDnsEventsForSession(sessionId).isEmpty())
  assertTrue("Work Connect rows cleared after ACK", workDao.getConnectEventsForSession(sessionId).isEmpty())

  // Personal profile data must remain intact and persistent
  assertEquals(2, personalDao.getDnsEventsForSession(sessionId).size)
  assertEquals(2, personalDao.getConnectEventsForSession(sessionId).size)
 }
}
