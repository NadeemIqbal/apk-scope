package com.nadeem.apkscope.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinalReportDaoTest {
 private lateinit var database: SandboxDatabase
 private lateinit var dao: FinalReportDao

 @Before
 fun createDatabase() {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  database = Room.inMemoryDatabaseBuilder(context, SandboxDatabase::class.java).allowMainThreadQueries().build()
  dao = database.finalReportDao()
 }

 @After
 fun closeDatabase() {
  database.close()
 }

 private fun report(reportId: String, analysisId: String, sessionId: String?, epochMs: Long) = FinalReportEntity(
  reportId = reportId,
  analysisId = analysisId,
  sessionId = sessionId,
  appName = "Test App",
  packageName = "com.example.test",
  versionName = "1.0",
  versionCode = 100L,
  sha256 = "abc123sha256",
  generatedAtEpochMs = epochMs,
  overallScore = 45,
  overallLevel = "MODERATE",
  staticScore = 20,
  staticLevel = "LOW",
  runtimeScore = 15,
  runtimeLevel = "LOW",
  combinedScore = 10,
  staticEngineVersion = "static-v1",
  runtimeEngineVersion = "runtime-v1",
  combinedEngineVersion = "combined-v1",
  staticComplete = true,
  runtimeComplete = sessionId != null,
  androidEvidenceStatus = "READY",
 )

 private fun findings(reportId: String) = listOf(
  FinalReportFindingEntity(
   reportId = reportId,
   ruleId = "STATIC_READ_SMS",
   severity = "HIGH",
   title = "SMS declared",
   explanation = "Declares SMS",
   scoreContribution = 15,
   assessmentType = "STATIC",
   ruleEngineVersion = "static-v1",
   evidenceJson = "[]",
  ),
  FinalReportFindingEntity(
   reportId = reportId,
   ruleId = "RUNTIME_NETWORK_ACTIVITY",
   severity = "LOW",
   title = "Network observed",
   explanation = "Established TCP connection",
   scoreContribution = 5,
   assessmentType = "RUNTIME",
   ruleEngineVersion = "runtime-v1",
   evidenceJson = "[]",
  ),
 )

 @Test
 fun upsertAndRetrieveReportWithFindings() = runBlocking {
  val r = report("rep-1", "an-1", "sess-1", 1000L)
  val f = findings("rep-1")

  dao.upsertReport(r, f)

  val retrieved = dao.getReport("rep-1")
  assertNotNull(retrieved)
  assertEquals("rep-1", retrieved!!.report.reportId)
  assertEquals(45, retrieved.report.overallScore)
  assertEquals(2, retrieved.findings.size)
  assertTrue(retrieved.findings.any { it.ruleId == "STATIC_READ_SMS" })
  assertTrue(retrieved.findings.any { it.ruleId == "RUNTIME_NETWORK_ACTIVITY" })
 }

 @Test
 fun retrieveBySessionIdAndAnalysisId() = runBlocking {
  val r1 = report("rep-static-1", "an-1", null, 1000L)
  val r2 = report("rep-session-1", "an-1", "sess-1", 2000L)

  dao.upsertReport(r1, emptyList())
  dao.upsertReport(r2, emptyList())

  val bySession = dao.getReportForSession("sess-1")
  assertNotNull(bySession)
  assertEquals("rep-session-1", bySession!!.report.reportId)

  val latestForAnalysis = dao.getLatestReportForAnalysis("an-1")
  assertNotNull(latestForAnalysis)
  assertEquals("rep-session-1", latestForAnalysis!!.report.reportId)
 }

 @Test
 fun observeAllReportsOrderedByTimestampDescending() = runBlocking {
  val r1 = report("rep-1", "an-1", "sess-1", 1000L)
  val r2 = report("rep-2", "an-2", "sess-2", 3000L)
  val r3 = report("rep-3", "an-3", null, 2000L)

  dao.upsertReport(r1, emptyList())
  dao.upsertReport(r2, emptyList())
  dao.upsertReport(r3, emptyList())

  val all = dao.observeAllReports().first()
  assertEquals(3, all.size)
  assertEquals("rep-2", all[0].reportId) // 3000
  assertEquals("rep-3", all[1].reportId) // 2000
  assertEquals("rep-1", all[2].reportId) // 1000
 }

 @Test
 fun deleteReportCascadesToFindings() = runBlocking {
  val r = report("rep-1", "an-1", "sess-1", 1000L)
  dao.upsertReport(r, findings("rep-1"))

  dao.deleteReport("rep-1")

  val retrieved = dao.getReport("rep-1")
  assertNull(retrieved)
 }
}
