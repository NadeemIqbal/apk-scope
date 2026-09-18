package com.nadeem.apkscope.domain.monitor

import com.nadeem.apkscope.core.database.WorkNetworkObservationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checkpoint 5, item 36: connection aggregation / DNS association / no-fabricated-hostname / byte-accounting coverage for the pure [LiveMonitorAggregator], independent of any Room or Compose wiring. */
class LiveMonitorAggregatorTest {
 private fun row(
  sequence: Long, type: String, timestampEpochMs: Long = sequence,
  protocol: String? = null, destinationIp: String? = null, destinationPort: Int? = null, connectionId: Long? = null,
  startTimeEpochMs: Long? = null, endTimeEpochMs: Long? = null, uploadedBytes: Long? = null, downloadedBytes: Long? = null,
  failureReason: String? = null, failureDetail: String? = null, hostname: String? = null, resolvedAddressesCsv: String? = null,
  transactionId: Int? = null, sourcePort: Int? = null, limitName: String? = null, currentValue: Long? = null, limitValue: Long? = null,
 ) = WorkNetworkObservationEntity(
  sessionId = "s1", sequence = sequence, timestampEpochMs = timestampEpochMs, type = type,
  protocol = protocol, destinationIp = destinationIp, destinationPort = destinationPort, connectionId = connectionId,
  startTimeEpochMs = startTimeEpochMs, endTimeEpochMs = endTimeEpochMs, uploadedBytes = uploadedBytes, downloadedBytes = downloadedBytes,
  failureReason = failureReason, failureDetail = failureDetail, hostname = hostname, resolvedAddressesCsv = resolvedAddressesCsv,
  transactionId = transactionId, sourcePort = sourcePort, limitName = limitName, currentValue = currentValue, limitValue = limitValue,
 )

 @Test fun dnsResponseBecomesOneFeedEntryWithResolvedAddress() {
  val rows = listOf(
   row(1, "DnsQuery", hostname = "api.example.com", transactionId = 7, sourcePort = 5000),
   row(2, "DnsResponse", hostname = "api.example.com", resolvedAddressesCsv = "104.20.23.154", transactionId = 7, sourcePort = 5000),
  )
  val feed = LiveMonitorAggregator.feed(rows)
  // The query has a matching response, so only the response's merged entry is emitted — never both.
  assertEquals(1, feed.size)
  assertEquals(LiveMonitorAggregator.FeedCategory.DNS, feed[0].category)
  assertEquals("api.example.com", feed[0].title)
  assertEquals("resolved to 104.20.23.154", feed[0].subtitle)
 }

 @Test fun pendingDnsQueryWithNoResponseStillAppearsInFeed() {
  val rows = listOf(row(1, "DnsQuery", hostname = "slow.example.com", transactionId = 9))
  val feed = LiveMonitorAggregator.feed(rows)
  assertEquals(1, feed.size)
  assertEquals("query sent, awaiting response", feed[0].subtitle)
 }

 @Test fun connectionWithPrecedingDnsGetsRealAssociatedHostname() {
  val rows = listOf(
   row(1, "DnsResponse", timestampEpochMs = 100, hostname = "api.example.com", resolvedAddressesCsv = "104.20.23.154"),
   row(2, "ConnectionOpened", timestampEpochMs = 200, protocol = "TCP", destinationIp = "104.20.23.154", destinationPort = 443, connectionId = 1L),
   row(3, "ConnectionClosed", timestampEpochMs = 300, protocol = "TCP", destinationIp = "104.20.23.154", destinationPort = 443, connectionId = 1L,
    startTimeEpochMs = 200, endTimeEpochMs = 300, uploadedBytes = 42_000L, downloadedBytes = 318_000L),
  )
  val feed = LiveMonitorAggregator.feed(rows)
  val tcpEntry = feed.single { it.category == LiveMonitorAggregator.FeedCategory.TCP }
  assertEquals("Associated via DNS: api.example.com", tcpEntry.subtitle)
  assertTrue(!tcpEntry.suspicious)
  assertEquals(42_000L, tcpEntry.uploadedBytes)
  assertEquals(318_000L, tcpEntry.downloadedBytes)

  val connections = LiveMonitorAggregator.connectionRows(rows)
  assertEquals(1, connections.size)
  assertEquals("api.example.com", connections[0].associatedHostname)
  assertEquals(false, connections[0].open)
  assertEquals(100L, connections[0].durationMs)
 }

 /** Item 6's non-negotiable rule: a raw-IP connection with no preceding DNS evidence must never acquire a fabricated hostname, even when some unrelated hostname was resolved elsewhere in the same session. */
 @Test fun rawIpConnectionNeverAcquiresFabricatedHostname() {
  val rows = listOf(
   row(1, "DnsResponse", timestampEpochMs = 100, hostname = "unrelated.example.com", resolvedAddressesCsv = "8.8.8.8"),
   row(2, "ConnectionOpened", timestampEpochMs = 200, protocol = "TCP", destinationIp = "1.1.1.1", destinationPort = 443, connectionId = 2L),
   row(3, "ConnectionClosed", timestampEpochMs = 300, protocol = "TCP", destinationIp = "1.1.1.1", destinationPort = 443, connectionId = 2L,
    startTimeEpochMs = 200, endTimeEpochMs = 300, uploadedBytes = 100L, downloadedBytes = 200L),
  )
  val tcpEntry = LiveMonitorAggregator.feed(rows).single { it.category == LiveMonitorAggregator.FeedCategory.TCP }
  assertNull(tcpEntry.subtitle)
  assertTrue(tcpEntry.suspicious)

  val connection = LiveMonitorAggregator.connectionRows(rows).single()
  assertNull(connection.associatedHostname)
 }

 @Test fun policyDeniedFailureIsBlockedNeverCalledMalicious() {
  val rows = listOf(row(1, "ConnectionFailed", protocol = "TCP", destinationIp = "192.168.1.1", destinationPort = 80,
   failureReason = "POLICY_DENIED", failureDetail = "Private/local destination blocked"))
  val entry = LiveMonitorAggregator.feed(rows).single()
  assertEquals(LiveMonitorAggregator.FeedCategory.BLOCKED, entry.category)
  assertEquals("Private/local destination blocked", entry.subtitle)
  assertTrue(!entry.subtitle!!.contains("malicious", ignoreCase = true))
 }

 @Test fun resourceLimitExceededIsAFactualWarning() {
  val rows = listOf(row(1, "ResourceLimitExceeded", limitName = "TCP connection limit", currentValue = 128L, limitValue = 128L))
  val entry = LiveMonitorAggregator.feed(rows).single()
  assertEquals(LiveMonitorAggregator.FeedCategory.WARNING, entry.category)
  assertEquals("128 / 128 reached", entry.subtitle)
  assertTrue(entry.suspicious)
 }

 @Test fun openConnectionWithNoCloseYetReportsZeroBytesNotOpen() {
  val rows = listOf(row(1, "ConnectionOpened", protocol = "TCP", destinationIp = "1.2.3.4", destinationPort = 443, connectionId = 5L))
  val connection = LiveMonitorAggregator.connectionRows(rows).single()
  assertEquals(true, connection.open)
  assertNull(connection.durationMs)
  assertEquals(0L, connection.uploadedBytes)
  assertEquals(0L, connection.downloadedBytes)
 }
}
