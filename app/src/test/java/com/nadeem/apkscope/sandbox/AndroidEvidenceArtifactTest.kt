package com.nadeem.apkscope.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidEvidenceArtifactTest {

 private fun dnsEntry(eventId: Long = 1L) = AndroidDnsEvidenceEntry(
  eventId = eventId,
  batchToken = 100L,
  packageName = "com.example.target",
  timestampEpochMs = 1000L + eventId,
  receivedAtEpochMs = 2000L + eventId,
  hostname = "api.example.com",
  resolvedAddressesCsv = "93.184.216.34",
  totalResolvedAddressCount = 1,
 )

 private fun connectEntry(eventId: Long = 2L) = AndroidConnectEvidenceEntry(
  eventId = eventId,
  batchToken = 100L,
  packageName = "com.example.target",
  timestampEpochMs = 1000L + eventId,
  receivedAtEpochMs = 2000L + eventId,
  destinationAddress = "93.184.216.34",
  destinationPort = 443,
 )

 private fun artifact(
  sessionId: String = "test-session",
  packageName: String = "com.example.target",
  schemaVersion: Int = AndroidEvidenceArtifact.CURRENT_SCHEMA_VERSION,
  dnsEvents: List<AndroidDnsEvidenceEntry> = listOf(dnsEntry()),
  connectEvents: List<AndroidConnectEvidenceEntry> = listOf(connectEntry()),
  firstTs: Long? = 1001L,
  lastTs: Long? = 1002L,
 ) = AndroidEvidenceArtifact(
  schemaVersion = schemaVersion,
  sessionId = sessionId,
  targetPackageName = packageName,
  status = "AVAILABLE",
  dnsEvents = dnsEvents,
  connectEvents = connectEvents,
  firstEventTimestampEpochMs = firstTs,
  lastEventTimestampEpochMs = lastTs,
  exportedAtEpochMs = 3000L,
 )

 @Test
 fun roundTripsThroughJsonExactly() {
  val original = artifact()
  val jsonString = original.toJson().toString()
  val restored = AndroidEvidenceArtifact.fromJson(jsonString)
  assertEquals(original.sessionId, restored.sessionId)
  assertEquals(original.targetPackageName, restored.targetPackageName)
  assertEquals(original.status, restored.status)
  assertEquals(original.dnsEvents.size, restored.dnsEvents.size)
  assertEquals(original.connectEvents.size, restored.connectEvents.size)
  assertEquals(original.firstEventTimestampEpochMs, restored.firstEventTimestampEpochMs)
  assertEquals(original.lastEventTimestampEpochMs, restored.lastEventTimestampEpochMs)
  assertEquals(original.dnsEvents[0].hostname, restored.dnsEvents[0].hostname)
  assertEquals(original.connectEvents[0].destinationPort, restored.connectEvents[0].destinationPort)
 }

 @Test
 fun validArtifactPassesValidation() {
  assertNull(
   AndroidEvidenceArtifact.validate(
    artifact(),
    expectedSessionId = "test-session",
    expectedPackageName = "com.example.target",
   )
  )
 }

 @Test
 fun wrongSessionIdIsRejected() {
  val rejection = AndroidEvidenceArtifact.validate(
   artifact(),
   expectedSessionId = "other-session",
   expectedPackageName = "com.example.target",
  )
  assertNotNull(rejection)
  assertTrue(rejection!!.contains("sessionId"))
 }

 @Test
 fun wrongPackageNameIsRejected() {
  val rejection = AndroidEvidenceArtifact.validate(
   artifact(),
   expectedSessionId = "test-session",
   expectedPackageName = "com.other.package",
  )
  assertNotNull(rejection)
  assertTrue(rejection!!.contains("packageName"))
 }

 @Test
 fun emptyExpectedPackageNameIsNotAHardMismatch() {
  assertNull(
   AndroidEvidenceArtifact.validate(
    artifact(),
    expectedSessionId = "test-session",
    expectedPackageName = "",
   )
  )
 }

 @Test
 fun unsupportedSchemaVersionIsRejected() {
  val bad = artifact(schemaVersion = 999)
  val rejection = AndroidEvidenceArtifact.validate(
   bad,
   expectedSessionId = "test-session",
   expectedPackageName = "com.example.target",
  )
  assertNotNull(rejection)
  assertTrue(rejection!!.contains("schemaVersion"))
 }

 @Test
 fun inconsistentTimestampsAreRejected() {
  val bad = artifact(firstTs = 2000L, lastTs = 1000L)
  val rejection = AndroidEvidenceArtifact.validate(
   bad,
   expectedSessionId = "test-session",
   expectedPackageName = "com.example.target",
  )
  assertNotNull(rejection)
  assertTrue(rejection!!.contains("precedes"))
 }

 @Test(expected = IllegalArgumentException::class)
 fun exceedsMaxArtifactBytesThrows() {
  val hugePayload = "A".repeat((AndroidEvidenceArtifact.MAX_ARTIFACT_BYTES + 10).toInt())
  AndroidEvidenceArtifact.fromJson(hugePayload)
 }
}
