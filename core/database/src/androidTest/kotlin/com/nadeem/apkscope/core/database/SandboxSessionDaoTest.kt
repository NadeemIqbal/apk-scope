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

/** Item 2/21/26: real Room persistence coverage for the production [SandboxSessionDao] — instrumented, same reasoning as `AnalysisSessionDaoTest`'s doc comment (Room's generated DAO needs a real SQLite driver). */
@RunWith(AndroidJUnit4::class)
class SandboxSessionDaoTest {
 private lateinit var database: SandboxDatabase
 private lateinit var dao: SandboxSessionDao

 @Before fun createDatabase() {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  database = Room.inMemoryDatabaseBuilder(context, SandboxDatabase::class.java).allowMainThreadQueries().build()
  dao = database.sandboxSessionDao()
 }

 @After fun closeDatabase() { database.close() }

 private fun session(id: String, analysisId: String = "analysis-1", state: String = "CREATED", createdAt: Long = 1000L) = SandboxSessionEntity(
  sessionId = id, analysisId = analysisId, packageName = "com.example.riskfixture", state = state,
  policyDenyCamera = true, policyDenyMicrophone = true, policyDenyLocation = true, policyAlwaysOnVpnLockdown = true, policyDisposableSession = true,
  createdAtEpochMs = createdAt, startedAtEpochMs = null, endedAtEpochMs = null,
  errorCode = null, errorUserMessage = null, errorTechnicalDetail = null, errorRecoverability = null,
  personalApkPath = "/data/user/0/com.nadeem.apkscope/files/sandbox/$id.apk", installSessionId = null, installedVersionCode = null,
  dataClearRequestedAtEpochMs = null, dataClearCompletedAtEpochMs = null, dataClearResult = null,
  cleanupAppDataCleared = null, cleanupApkRemoved = null, cleanupWorkTempApkDeleted = null,
  cleanupPersonalTempApkDeleted = null, cleanupUriGrantReleased = null, cleanupNetworkSessionClosed = null,
 )

 private fun enforcements(id: String) = listOf(
  SandboxPolicyEnforcementEntity(sessionId = id, policy = "CAMERA_RUNTIME_PERMISSION_DENIAL", status = "NOT_SUPPORTED", mechanism = "DEVICE_POLICY_MANAGER", message = "setPermissionGrantState returned false"),
  SandboxPolicyEnforcementEntity(sessionId = id, policy = "ALWAYS_ON_VPN_LOCKDOWN", status = "ENFORCED", mechanism = "DEVICE_POLICY_MANAGER", message = null),
 )

 @Test fun insertAndReadSession() = runBlocking {
  val id = "s1"
  dao.upsertSession(session(id), enforcements(id))
  val loaded = dao.getSession(id)
  assertEquals(id, loaded?.sessionId)
  assertEquals("CREATED", loaded?.state)
 }

 @Test fun enforcementResultsSurviveRoundTrip() = runBlocking {
  val id = "s2"
  dao.upsertSession(session(id), enforcements(id))
  val withEnforcements = dao.getSessionWithEnforcements(id)!!
  assertEquals(2, withEnforcements.enforcements.size)
  assertTrue(withEnforcements.enforcements.any { it.policy == "CAMERA_RUNTIME_PERMISSION_DENIAL" && it.status == "NOT_SUPPORTED" })
  assertTrue(withEnforcements.enforcements.any { it.policy == "ALWAYS_ON_VPN_LOCKDOWN" && it.status == "ENFORCED" })
 }

 @Test fun reUpsertReplacesEnforcementsNotAppends() = runBlocking {
  val id = "s3"
  dao.upsertSession(session(id, state = "PREPARING"), enforcements(id))
  dao.upsertSession(session(id, state = "READY"), listOf(SandboxPolicyEnforcementEntity(sessionId = id, policy = "ALWAYS_ON_VPN_LOCKDOWN", status = "ENFORCED", mechanism = "DEVICE_POLICY_MANAGER", message = null)))

  val result = dao.getSessionWithEnforcements(id)!!
  assertEquals("READY", result.session.state)
  assertEquals(1, result.enforcements.size)
 }

 @Test fun deletingSessionCascadesEnforcements() = runBlocking {
  val id = "s4"
  dao.upsertSession(session(id), enforcements(id))
  dao.deleteSession(id)
  assertNull(dao.getSession(id))
  assertEquals(0, dao.countEnforcementsForSession(id))
 }

 @Test fun oneAnalysisCanHaveMultipleSandboxSessions() = runBlocking {
  dao.upsertSession(session("run-1", analysisId = "analysis-shared", createdAt = 1000L), emptyList())
  dao.upsertSession(session("run-2", analysisId = "analysis-shared", createdAt = 2000L), emptyList())
  dao.upsertSession(session("unrelated", analysisId = "analysis-other", createdAt = 1500L), emptyList())

  val sessions = dao.observeSessionsForAnalysis("analysis-shared").first()
  assertEquals(listOf("run-2", "run-1"), sessions.map { it.sessionId }) // newest first
 }

 @Test fun historyOrdersNewestFirst() = runBlocking {
  dao.upsertSession(session("older", createdAt = 1000L), emptyList())
  dao.upsertSession(session("newer", createdAt = 2000L), emptyList())
  val all = dao.observeAllSessions().first()
  assertEquals(listOf("newer", "older"), all.map { it.sessionId })
 }
}
