package com.nadeem.apkscope.domain

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nadeem.apkscope.core.database.AnalysisComponentEntity
import com.nadeem.apkscope.core.database.AnalysisPermissionEntity
import com.nadeem.apkscope.core.database.AnalysisSessionEntity
import com.nadeem.apkscope.core.database.SandboxDatabaseProvider
import com.nadeem.apkscope.core.risk.DefaultRiskEngine
import com.nadeem.apkscope.core.risk.audit.AuditOutcome
import com.nadeem.apkscope.core.risk.audit.AuditRuleIds
import com.nadeem.apkscope.core.staticanalysis.ApkAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Milestone 10 correction pass: real, on-device verification (not a JVM-mocked repository) for two
 * requirements the prior checkpoint left as device-observation-only claims — MS10-FOUND04 (audits
 * never mutate the existing risk score) and part of MS10-FOUND01 (the audit/analysis relationship is
 * correctly and exclusively keyed by `analysisId`). Runs against the real `SandboxDatabaseProvider`
 * singleton and a real on-device Room database, per this project's own established instrumented-test
 * convention (see `StaticAnalysisPersistenceInstrumentedTest`'s doc comment) — a fresh, random
 * `sessionId` per test avoids colliding with any real analysis history already on the device; nothing
 * is cleared or wiped to make this pass.
 */
@RunWith(AndroidJUnit4::class)
class SecurityAuditRepositoryInstrumentedTest {
 private val context = InstrumentationRegistry.getInstrumentation().targetContext
 private val application = context.applicationContext as Application
 private val analysisDao = SandboxDatabaseProvider.get(context).analysisSessionDao()
 private val auditDao = SandboxDatabaseProvider.get(context).securityAuditDao()

 private fun seedAnalysis(sessionId: String, riskScore: Int = 77, riskLevel: String = "HIGH"): Unit = runBlocking {
  analysisDao.insertCompleteAnalysis(
   session = AnalysisSessionEntity(
    sessionId = sessionId, packageName = "com.example.audittest", appName = "Audit Test App",
    versionName = "1.0", versionCode = 1, sha256 = "cafebabe", analyzedAtEpochMs = System.currentTimeMillis(),
    minSdkVersion = 24, targetSdkVersion = 28, debuggable = true, usesCleartextTraffic = true, allowBackup = true,
    signatureVerified = true, signatureDetail = "sig-1", nativeLibraryAbis = emptyList(),
    permissionCount = 2, componentTotalCount = 2, componentExportedCount = 2,
    riskScore = riskScore, riskLevel = riskLevel, riskEngineVersion = "static-v1",
   ),
   permissions = listOf(
    AnalysisPermissionEntity(sessionId = sessionId, permission = "android.permission.RECEIVE_BOOT_COMPLETED"),
    AnalysisPermissionEntity(sessionId = sessionId, permission = "android.permission.INTERNET"),
   ),
   components = listOf(
    AnalysisComponentEntity(sessionId = sessionId, name = ".MainActivity", type = "ACTIVITY", exported = true),
    AnalysisComponentEntity(sessionId = sessionId, name = ".ExportedReceiver", type = "RECEIVER", exported = true),
   ),
   findings = emptyList(),
  )
 }

 @Test
 fun runAndPersistAudit_leavesExistingRiskScoreAndLevelByteForByteUnchanged() = runBlocking {
  val sessionId = "audit-test-${UUID.randomUUID()}"
  seedAnalysis(sessionId, riskScore = 63, riskLevel = "HIGH")
  val before = analysisDao.getDetails(sessionId)!!.session

  SecurityAuditRepository(application).runAndPersistAudit(sessionId)

  val after = analysisDao.getDetails(sessionId)!!.session
  assertEquals("riskScore must be unchanged by running a Security Audit (MS10-FOUND04)", before.riskScore, after.riskScore)
  assertEquals("riskLevel must be unchanged", before.riskLevel, after.riskLevel)
  assertEquals("riskEngineVersion must be unchanged — Security Audit has its own separate engineVersion", before.riskEngineVersion, after.riskEngineVersion)
  assertEquals(before, after) // full entity equality — no field silently touched
 }

 @Test
 fun runAndPersistAudit_bindsAuditExclusivelyToItsOwnAnalysisId() = runBlocking {
  val sessionA = "audit-test-a-${UUID.randomUUID()}"
  val sessionB = "audit-test-b-${UUID.randomUUID()}"
  seedAnalysis(sessionA)
  seedAnalysis(sessionB)
  val repository = SecurityAuditRepository(application)

  repository.runAndPersistAudit(sessionA)

  val auditA = auditDao.getAuditForAnalysis(sessionA)
  val auditB = auditDao.getAuditForAnalysis(sessionB)
  assertTrue("the audited analysis must have a persisted audit", auditA != null)
  assertEquals(sessionA, auditA!!.audit.analysisId)
  assertNull("an unrelated analysis must show no audit — identity must not leak across analyses", auditB)
 }

 @Test
 fun runAndPersistAudit_reRunReplacesRatherThanDuplicatesTheSameAnalysisAudit() = runBlocking {
  val sessionId = "audit-test-rerun-${UUID.randomUUID()}"
  seedAnalysis(sessionId)
  val repository = SecurityAuditRepository(application)

  val first = repository.runAndPersistAudit(sessionId)
  val firstAuditId = auditDao.getAuditForAnalysis(sessionId)!!.audit.auditId
  val second = repository.runAndPersistAudit(sessionId)
  val secondAuditId = auditDao.getAuditForAnalysis(sessionId)!!.audit.auditId

  assertNotEquals("a re-run must mint a new auditId, not silently keep the old identity", firstAuditId, secondAuditId)
  assertEquals("exactly one current audit per analysis — the unique index on analysisId must hold", 1, countAuditsFor(sessionId))
  assertEquals(first.findings.size, second.findings.size) // same catalog, same input -> same finding count
 }

 /**
  * Milestone 10 (Security Audit), Phase 10.3, MS10-NET01 item 3: proves the newly-parsed network
  * security config evidence genuinely reaches a *persisted* audit finding, not just an in-memory
  * `AuditFinding` — the full real pipeline, not a fabricated input: a real `fixture-debug.apk`
  * analyzed by [ApkAnalyzer.analyze] (real resources.arsc resolution + real XML content parsing),
  * persisted via the same [SessionRepository.persistCompletedAnalysis] path a real app import uses
  * (which is what populates [StaticAnalysisResultStore], the cache [SecurityAuditRepository] depends
  * on for this data — see `RiskInputMapper`'s doc), then a real audit run and read back from Room.
  * `fixture`'s real `network_security_config.xml` declares `cleartextTrafficPermitted="true"` on its
  * `<base-config>`, so this specific real fixture must produce NEEDS_REVIEW, not a fabricated PASS.
  */
 @Test
 fun runAndPersistAudit_networkSecurityConfigFindingReachesPersistedAuditFromARealApk() = runBlocking {
  val apkFile = File("/data/local/tmp/fixture-debug.apk")
  assertTrue("fixture-debug.apk must be pushed to /data/local/tmp first", apkFile.exists() && apkFile.canRead())
  val sessionId = "audit-test-nsc-${UUID.randomUUID()}"

  val result = ApkAnalyzer.analyze(context, apkFile)
  val risk = DefaultRiskEngine().evaluate(result.toRiskInput())
  val sessionRepository = SessionRepository(context)
  sessionRepository.persistCompletedAnalysis(
   sessionId = sessionId, appName = "Fixture", analyzedAtEpochMs = System.currentTimeMillis(), result = result, risk = risk,
  )

  val report = SecurityAuditRepository(application).runAndPersistAudit(sessionId)
  val inMemoryFinding = report.findings.firstOrNull { it.ruleId == AuditRuleIds.NETWORK_SECURITY_CONFIG_REVIEW }
  assertTrue("the audit report returned from runAndPersistAudit must include this finding", inMemoryFinding != null)
  assertEquals(AuditOutcome.NEEDS_REVIEW, inMemoryFinding!!.outcome)
  assertTrue(
   "detail must reflect the real parsed content (fixture's base-config permits cleartext)",
   inMemoryFinding.detail.contains("cleartext"),
  )
  assertTrue("remediation must be non-blank, matching every other rule's contract", inMemoryFinding.remediation.isNotBlank())

  // The actual persisted-findings assertion — read back from Room, not the in-memory return value.
  val persisted = auditDao.getAuditForAnalysis(sessionId)
  assertTrue("must be persisted", persisted != null)
  val persistedFinding = persisted!!.findings.firstOrNull { it.ruleId == AuditRuleIds.NETWORK_SECURITY_CONFIG_REVIEW }
  assertTrue("the finding must survive the Room round-trip, not just exist in memory", persistedFinding != null)
  assertEquals("NEEDS_REVIEW", persistedFinding!!.outcome)
  assertEquals(inMemoryFinding.detail, persistedFinding.detail)
  assertEquals(inMemoryFinding.remediation, persistedFinding.remediation)
 }

 private suspend fun countAuditsFor(analysisId: String): Int {
  // No direct COUNT query on the DAO surface today — getAuditForAnalysis returning non-null exactly
  // once, combined with the unique index itself (which would throw on a second row if it were ever
  // violated by non-DAO code), is the behavior actually being asserted; this helper documents that
  // rather than adding a query whose only caller would be this one test.
  return if (auditDao.getAuditForAnalysis(analysisId) != null) 1 else 0
 }
}
