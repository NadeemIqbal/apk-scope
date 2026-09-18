package com.nadeem.apkscope.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Item 23: real Room persistence coverage for [AnalysisSessionDao], run against an in-memory
 * database on a real Android runtime (instrumented, not a plain JVM unit test — Room's generated
 * DAO implementation needs a real SQLite driver, same constraint `ApkAnalyzerTest`'s doc comment
 * already notes for `PackageManager`).
 */
@RunWith(AndroidJUnit4::class)
class AnalysisSessionDaoTest {
 private lateinit var database: SandboxDatabase
 private lateinit var dao: AnalysisSessionDao

 @Before fun createDatabase() {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  database = Room.inMemoryDatabaseBuilder(context, SandboxDatabase::class.java).allowMainThreadQueries().build()
  dao = database.analysisSessionDao()
 }

 @After fun closeDatabase() { database.close() }

 private fun session(id: String, epochMs: Long) = AnalysisSessionEntity(
  sessionId = id, packageName = "com.example.$id", appName = "App $id", versionName = "1.0", versionCode = 1,
  sha256 = "deadbeef", analyzedAtEpochMs = epochMs, minSdkVersion = 24, targetSdkVersion = 34, debuggable = false,
  signatureVerified = true, signatureDetail = "abc123", nativeLibraryAbis = listOf("arm64-v8a", "x86_64"),
  permissionCount = 2, componentTotalCount = 3, componentExportedCount = 1, riskScore = 42, riskLevel = "MODERATE",
  riskEngineVersion = "static-v1",
 )

 private fun permissions(id: String) = listOf(
  AnalysisPermissionEntity(sessionId = id, permission = "android.permission.CAMERA"),
  AnalysisPermissionEntity(sessionId = id, permission = "android.permission.INTERNET"),
 )

 private fun components(id: String) = listOf(
  AnalysisComponentEntity(sessionId = id, name = ".MainActivity", type = "ACTIVITY", exported = true),
  AnalysisComponentEntity(sessionId = id, name = ".BgService", type = "SERVICE", exported = false),
 )

 private fun findings(id: String) = listOf(
  RiskFindingEntity(
   sessionId = id, ruleId = "STATIC_OVERLAY_PERMISSION", severity = "MEDIUM", title = "Overlay capability declared",
   explanation = "Declares SYSTEM_ALERT_WINDOW.", scoreContribution = 10, evidence = listOf("Declares android.permission.SYSTEM_ALERT_WINDOW"),
  ),
 )

 @Test fun insertAndReadCompleteAnalysis() = runBlocking {
  val id = "session-1"
  dao.insertCompleteAnalysis(session(id, 1000L), permissions(id), components(id), findings(id))

  val details = dao.getDetails(id)
  assertEquals(id, details?.session?.sessionId)
  assertEquals(42, details?.session?.riskScore)
 }

 @Test fun permissionsSurviveRoundTrip() = runBlocking {
  val id = "session-2"
  dao.insertCompleteAnalysis(session(id, 1000L), permissions(id), components(id), findings(id))

  val details = dao.getDetails(id)!!
  assertEquals(setOf("android.permission.CAMERA", "android.permission.INTERNET"), details.permissions.map { it.permission }.toSet())
 }

 @Test fun componentsSurviveRoundTrip() = runBlocking {
  val id = "session-3"
  dao.insertCompleteAnalysis(session(id, 1000L), permissions(id), components(id), findings(id))

  val details = dao.getDetails(id)!!
  assertEquals(2, details.components.size)
  assertTrue(details.components.any { it.name == ".MainActivity" && it.exported })
  assertTrue(details.components.any { it.name == ".BgService" && !it.exported })
 }

 @Test fun riskFindingsSurviveRoundTrip() = runBlocking {
  val id = "session-4"
  dao.insertCompleteAnalysis(session(id, 1000L), permissions(id), components(id), findings(id))

  val details = dao.getDetails(id)!!
  assertEquals(1, details.findings.size)
  val finding = details.findings.first()
  assertEquals("STATIC_OVERLAY_PERMISSION", finding.ruleId)
  assertEquals(10, finding.scoreContribution)
  assertEquals(listOf("Declares android.permission.SYSTEM_ALERT_WINDOW"), finding.evidence)
 }

 @Test fun engineVersionSurvivesRoundTrip() = runBlocking {
  val id = "session-5"
  dao.insertCompleteAnalysis(session(id, 1000L), permissions(id), components(id), findings(id))

  assertEquals("static-v1", dao.getDetails(id)?.session?.riskEngineVersion)
 }

 @Test fun deleteAnalysisRemovesSessionAndCascadesChildren() = runBlocking {
  val id = "session-6"
  dao.insertCompleteAnalysis(session(id, 1000L), permissions(id), components(id), findings(id))
  assertEquals(id, dao.getDetails(id)?.session?.sessionId)

  dao.deleteSession(id)

  assertNull(dao.getDetails(id))
  // Foreign-key CASCADE should actually remove the child rows, not just make them unreachable
  // through getDetails (which already returns null once the parent is gone regardless).
  assertEquals(0, dao.countPermissionsForSession(id))
 }

 @Test fun historyOrdersNewestFirst() = runBlocking {
  dao.insertCompleteAnalysis(session("older", 1000L), permissions("older"), components("older"), findings("older"))
  dao.insertCompleteAnalysis(session("newer", 2000L), permissions("newer"), components("newer"), findings("newer"))

  val summaries = dao.observeAllSummaries().first()
  assertEquals(listOf("newer", "older"), summaries.map { it.sessionId })
 }

 @Test fun reAnalysisReplacesPriorPermissionsComponentsAndFindings() = runBlocking {
  val id = "session-reanalyzed"
  dao.insertCompleteAnalysis(session(id, 1000L), permissions(id), components(id), findings(id))

  // A second, different result for the same sessionId (item 19's "a future explicit re-analysis
  // may produce a different score") should fully replace the child rows, not append to them.
  val newFindings = listOf(RiskFindingEntity(sessionId = id, ruleId = "STATIC_DEBUGGABLE_APK", severity = "MEDIUM", title = "Debuggable APK", explanation = "…", scoreContribution = 10, evidence = listOf("ApplicationInfo.FLAG_DEBUGGABLE is set")))
  dao.insertCompleteAnalysis(session(id, 1000L).copy(riskScore = 10, riskLevel = "LOW"), emptyList(), emptyList(), newFindings)

  val details = dao.getDetails(id)!!
  assertEquals(10, details.session.riskScore)
  assertTrue(details.permissions.isEmpty())
  assertEquals(1, details.findings.size)
  assertEquals("STATIC_DEBUGGABLE_APK", details.findings.first().ruleId)
 }
}
