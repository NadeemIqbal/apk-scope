package com.nadeem.apkscope.sandbox

import com.nadeem.apkscope.core.model.RuntimeObservationSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Checkpoint 5, item 36: artifact serialization/validation/size/truncation-metadata coverage for the pure [RuntimeObservationArtifact] — independent of any cross-profile transport or Room wiring. */
class RuntimeObservationArtifactTest {
 private fun summary(sessionId: String = "s1") = RuntimeObservationSummary(
  sessionId = sessionId, startedAt = Instant.ofEpochMilli(0), endedAt = Instant.ofEpochMilli(1000),
  connectionCount = 3, dnsQueryCount = 2, uniqueObservedDomains = 2, uploadedBytes = 100L, downloadedBytes = 200L,
  blockedConnectionCount = 1, failedConnectionCount = 0, droppedObservationCount = 0L,
 )

 private fun entry(sequence: Long) = RuntimeObservationEntry(
  sequence = sequence, timestampEpochMs = sequence, type = "ConnectionOpened",
  protocol = "TCP", destinationIp = "1.2.3.4", destinationPort = 443, connectionId = sequence,
  startTimeEpochMs = null, endTimeEpochMs = null, uploadedBytes = null, downloadedBytes = null,
  failureReason = null, failureDetail = null, hostname = null, resolvedAddressesCsv = null,
  transactionId = null, sourcePort = null, limitName = null, currentValue = null, limitValue = null,
 )

 private fun artifact(observations: List<RuntimeObservationEntry> = listOf(entry(1), entry(2)), truncated: Boolean = false, totalCount: Int = observations.size, packageName: String = "com.example.fixture") =
  RuntimeObservationArtifact(
   schemaVersion = RuntimeObservationArtifact.SCHEMA_VERSION, sessionId = "s1", packageName = packageName,
   startedAtEpochMs = 0, endedAtEpochMs = 1000, summary = summary(), observations = observations,
   truncated = truncated, exportedObservationCount = observations.size, totalObservationCount = totalCount,
  )

 @Test fun roundTripsThroughJsonExactly() {
  val original = artifact()
  val restored = RuntimeObservationArtifact.fromJson(original.toJson())
  assertEquals(original, restored)
 }

 @Test fun validAritfactPassesValidation() {
  assertNull(RuntimeObservationArtifact.validate(artifact(), expectedSessionId = "s1", expectedPackageName = "com.example.fixture"))
 }

 @Test fun wrongSessionIdIsRejected() {
  val rejection = RuntimeObservationArtifact.validate(artifact(), expectedSessionId = "different-session", expectedPackageName = "com.example.fixture")
  assertNotNull(rejection)
  assertTrue(rejection!!.contains("sessionId"))
 }

 @Test fun wrongPackageNameIsRejected() {
  val rejection = RuntimeObservationArtifact.validate(artifact(packageName = "com.evil.impersonator"), expectedSessionId = "s1", expectedPackageName = "com.example.fixture")
  assertNotNull(rejection)
  assertTrue(rejection!!.contains("packageName"))
 }

 /** Item 21: an empty expected package name (not yet known) is not treated as a hard mismatch — mirrors WorkEvidenceEnvelope.isValidFor's convention. */
 @Test fun emptyExpectedPackageNameIsNotAHardMismatch() {
  assertNull(RuntimeObservationArtifact.validate(artifact(), expectedSessionId = "s1", expectedPackageName = ""))
 }

 @Test fun unsupportedSchemaVersionIsRejected() {
  val bad = artifact().copy(schemaVersion = 999)
  val rejection = RuntimeObservationArtifact.validate(bad, expectedSessionId = "s1", expectedPackageName = "com.example.fixture")
  assertNotNull(rejection)
  assertTrue(rejection!!.contains("schemaVersion"))
 }

 @Test fun endBeforeStartIsRejected() {
  val bad = artifact().copy(endedAtEpochMs = 0, startedAtEpochMs = 1000)
  assertNotNull(RuntimeObservationArtifact.validate(bad, "s1", "com.example.fixture"))
 }

 /** Item 20: a truncated artifact whose declared counts are internally consistent is still valid — truncation itself is not a rejection reason. */
 @Test fun truncatedArtifactWithConsistentCountsIsValid() {
  val truncatedArtifact = artifact(observations = listOf(entry(1)), truncated = true, totalCount = 100)
  assertNull(RuntimeObservationArtifact.validate(truncatedArtifact, "s1", "com.example.fixture"))
  assertEquals(1, truncatedArtifact.exportedObservationCount)
  assertEquals(100, truncatedArtifact.totalObservationCount)
 }

 /** Item 20/21: claiming "not truncated" while total != exported is an internally inconsistent artifact — must be rejected, not silently trusted. */
 @Test fun inconsistentTruncationMetadataIsRejected() {
  val bad = artifact(observations = listOf(entry(1)), truncated = false, totalCount = 100)
  assertNotNull(RuntimeObservationArtifact.validate(bad, "s1", "com.example.fixture"))
 }

 @Test fun negativeSummaryFieldIsRejected() {
  val bad = artifact().let { it.copy(summary = it.summary.copy(uploadedBytes = -1L)) }
  assertNotNull(RuntimeObservationArtifact.validate(bad, "s1", "com.example.fixture"))
 }

 @Test fun exportedCountMustMatchActualObservationListSize() {
  val bad = artifact().copy(exportedObservationCount = 999)
  assertNotNull(RuntimeObservationArtifact.validate(bad, "s1", "com.example.fixture"))
 }
}
