package com.nadeem.apkscope.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nadeem.apkscope.core.database.WorkEvidenceDatabaseProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkAndroidEvidenceSinkTest {
 private val context = InstrumentationRegistry.getInstrumentation().targetContext
 private val dao by lazy { WorkEvidenceDatabaseProvider.get(context).workAndroidEvidenceDao() }

 @Before
 fun setup() {
  WorkAndroidEvidenceSink.currentSessionId = null
  WorkAndroidEvidenceSink.currentTargetPackage = null
 }

 @Test
 fun recordEventsPersistsDnsAndConnectEvents() = runBlocking {
  val sessionId = UUID.randomUUID().toString()
  val targetPkg = "com.example.target"
  WorkAndroidEvidenceSink.currentSessionId = sessionId
  WorkAndroidEvidenceSink.currentTargetPackage = targetPkg

  val dnsEvent = TestNetworkEventFactory.createDnsEvent(
   id = 101L,
   hostname = "api.service.com",
   ipAddresses = arrayOf("104.244.42.1"),
   ipAddressesCount = 1,
   packageName = targetPkg,
   timestamp = 1000L,
  )
  val connectEvent = TestNetworkEventFactory.createConnectEvent(
   id = 102L,
   ipAddress = "104.244.42.1",
   port = 443,
   packageName = targetPkg,
   timestamp = 2000L,
  )

  val job = WorkAndroidEvidenceSink.recordEvents(
   context = context,
   batchToken = 999L,
   events = listOf(dnsEvent, connectEvent),
  )
  job?.join()

  val dnsRows = dao.getDnsEventsForSession(sessionId)
  assertEquals(1, dnsRows.size)
  assertEquals(101L, dnsRows[0].eventId)
  assertEquals("api.service.com", dnsRows[0].hostname)
  assertEquals("104.244.42.1", dnsRows[0].resolvedAddressesCsv)
  assertEquals(targetPkg, dnsRows[0].packageName)

  val connectRows = dao.getConnectEventsForSession(sessionId)
  assertEquals(1, connectRows.size)
  assertEquals(102L, connectRows[0].eventId)
  assertEquals("104.244.42.1", connectRows[0].destinationAddress)
  assertEquals(443, connectRows[0].destinationPort)

  val summary = dao.getSummary(sessionId)
  assertNotNull(summary)
  assertEquals(1, summary!!.dnsCount)
  assertEquals(1, summary.connectCount)
  assertEquals("READY", summary.status)
  assertEquals(1000L, summary.firstEventTimestampEpochMs)
  assertEquals(2000L, summary.lastEventTimestampEpochMs)
 }

 @Test
 fun foreignPackageEventsAreFilteredOut() = runBlocking {
  val sessionId = UUID.randomUUID().toString()
  val targetPkg = "com.example.target"
  WorkAndroidEvidenceSink.currentSessionId = sessionId
  WorkAndroidEvidenceSink.currentTargetPackage = targetPkg

  val foreignDns = TestNetworkEventFactory.createDnsEvent(
   id = 201L,
   hostname = "evil.com",
   packageName = "com.other.unrelated",
  )
  val targetConnect = TestNetworkEventFactory.createConnectEvent(
   id = 202L,
   ipAddress = "1.2.3.4",
   port = 80,
   packageName = targetPkg,
  )

  val job = WorkAndroidEvidenceSink.recordEvents(
   context = context,
   batchToken = 888L,
   events = listOf(foreignDns, targetConnect),
  )
  job?.join()

  val dnsRows = dao.getDnsEventsForSession(sessionId)
  assertTrue("Foreign DNS event must be filtered out", dnsRows.isEmpty())

  val connectRows = dao.getConnectEventsForSession(sessionId)
  assertEquals(1, connectRows.size)
  assertEquals(targetPkg, connectRows[0].packageName)
 }

 @Test
 fun nullSessionIdDoesNotRecord() = runBlocking {
  WorkAndroidEvidenceSink.currentSessionId = null
  WorkAndroidEvidenceSink.currentTargetPackage = "com.example.target"

  val dns = TestNetworkEventFactory.createDnsEvent()
  val job = WorkAndroidEvidenceSink.recordEvents(
   context = context,
   batchToken = 777L,
   events = listOf(dns),
  )
  assertEquals(null, job)
 }
}
