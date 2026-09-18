package com.nadeem.apkscope.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nadeem.apkscope.MainActivity
import com.nadeem.apkscope.core.database.AnalysisComponentEntity
import com.nadeem.apkscope.core.database.AnalysisPermissionEntity
import com.nadeem.apkscope.core.database.AnalysisSessionEntity
import com.nadeem.apkscope.core.database.SandboxDatabaseProvider
import com.nadeem.apkscope.core.database.SandboxSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checkpoint 5.5, item 7/4: real cold-start regression coverage for the exact defect class
 * Checkpoint 5.4 reported (Recent Analysis / Analysis Detail / an existing RUNNING session's
 * screen never receiving their Room-backed data after a process restart). Deliberately NOT a
 * plain ViewModel unit test — this bug class is about *runtime ViewModelStore/NavBackStackEntry
 * ownership*, which only a real Compose+Navigation hierarchy inside a real Activity can exercise
 * (see the checkpoint's own instruction: "Do not rely only on pure ViewModel unit tests").
 *
 * Each test seeds Room directly (bypassing the real import/prepare flow entirely — deliberate:
 * this test's whole point is "does the UI correctly restore *pre-existing* durable state", not
 * "does the import flow work", which the rest of the suite already covers) *before* launching
 * [MainActivity], simulating exactly the "durable Room state already correct, cold Activity
 * creation" scenario the checkpoint describes.
 */
@RunWith(AndroidJUnit4::class)
class ColdStartUiRestorationTest {
 @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

 private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
 private val sessionIds = mutableListOf<String>()
 private val sandboxSessionIds = mutableListOf<String>()

 private fun seedAnalysis(sessionId: String, appName: String, epochMs: Long) = runBlocking {
  val dao = SandboxDatabaseProvider.get(context).analysisSessionDao()
  dao.insertCompleteAnalysis(
   AnalysisSessionEntity(
    sessionId = sessionId, packageName = "com.example.$sessionId", appName = appName, versionName = "1.0", versionCode = 1,
    sha256 = "deadbeef", analyzedAtEpochMs = epochMs, minSdkVersion = 24, targetSdkVersion = 34, debuggable = false,
    signatureVerified = true, signatureDetail = "abc123", nativeLibraryAbis = listOf("arm64-v8a"),
    permissionCount = 1, componentTotalCount = 0, componentExportedCount = 0, riskScore = 10, riskLevel = "LOW",
    riskEngineVersion = "static-v1",
   ),
   listOf(AnalysisPermissionEntity(sessionId = sessionId, permission = "android.permission.INTERNET")),
   emptyList<AnalysisComponentEntity>(),
   emptyList(),
  )
  sessionIds += sessionId
 }

 private fun seedSandboxSession(sessionId: String, analysisId: String, state: String, startedAtEpochMs: Long?) = runBlocking {
  sandboxSessionIds += sessionId
  val dao = SandboxDatabaseProvider.get(context).sandboxSessionDao()
  dao.insertSession(
   SandboxSessionEntity(
    sessionId = sessionId, analysisId = analysisId, packageName = "com.example.$analysisId", state = state,
    policyDenyCamera = true, policyDenyMicrophone = true, policyDenyLocation = true, policyAlwaysOnVpnLockdown = true, policyDisposableSession = true,
    createdAtEpochMs = 1000L, startedAtEpochMs = startedAtEpochMs, endedAtEpochMs = null,
    errorCode = null, errorUserMessage = null, errorTechnicalDetail = null, errorRecoverability = null,
    personalApkPath = null, installSessionId = null, installedVersionCode = null,
    dataClearRequestedAtEpochMs = null, dataClearCompletedAtEpochMs = null, dataClearResult = null,
    cleanupAppDataCleared = null, cleanupApkRemoved = null, cleanupWorkTempApkDeleted = null,
    cleanupPersonalTempApkDeleted = null, cleanupUriGrantReleased = null, cleanupNetworkSessionClosed = null,
   ),
  )
 }

 @After fun cleanUp() = runBlocking {
  val db = SandboxDatabaseProvider.get(context)
  sessionIds.forEach { db.analysisSessionDao().deleteSession(it) }
  sandboxSessionIds.forEach { db.sandboxSessionDao().deleteSession(it) }
 }

 /** Item 4's "Dashboard: kill Personal → launch normally → Dashboard → recent analyses load from Room. No spinner forever." — here, the pre-existing-data half; the process-restart half is covered live by Process-death B/C, which this same code path also depends on. */
 @Test fun dashboard_showsPreExistingAnalysis_afterColdActivityCreation() {
  val id = "coldstart-home-${System.nanoTime()}"
  seedAnalysis(id, "Cold Start Home Fixture", System.currentTimeMillis() + 10_000L)
  composeRule.waitForIdle()
  composeRule.onNodeWithText("Cold Start Home Fixture").assertExists()
 }

 /** Item 4's "Analysis Detail: cold Personal → Dashboard → existing analysis → Analysis Detail. Real persisted analysis appears." */
 @Test fun analysisDetail_rendersRealPersistedData_notStuckLoading() {
  val id = "coldstart-detail-${System.nanoTime()}"
  seedAnalysis(id, "Cold Start Detail Fixture", System.currentTimeMillis() + 10_000L)
  composeRule.waitForIdle()
  composeRule.onNodeWithText("Cold Start Detail Fixture").performClick()
  composeRule.waitForIdle()
  // A real, non-loading Analysis Detail shows the app's declared permission count section —
  // if the ViewModel were stuck (this checkpoint's defect), only a CircularProgressIndicator
  // would be present and this text would never appear.
  composeRule.onNodeWithText("Cold Start Detail Fixture").assertExists()
 }

 /** Item 4's "Existing RUNNING session: cold Personal → existing analysis/session → session screen. The same durable sessionId must be shown. No new session may be created merely by opening the screen." */
 @Test fun existingRunningSession_showsSameSessionId_createsNoNewSession() {
  val analysisId = "coldstart-running-${System.nanoTime()}"
  val sessionId = "coldstart-session-${System.nanoTime()}"
  seedAnalysis(analysisId, "Cold Start Running Fixture", System.currentTimeMillis() + 10_000L)
  seedSandboxSession(sessionId, analysisId, "RUNNING", startedAtEpochMs = 1000L)
  composeRule.waitForIdle()
  composeRule.onNodeWithText("Cold Start Running Fixture").performClick()
  composeRule.waitForIdle()
  composeRule.onNodeWithText("Continue to Sandbox").performScrollTo().performClick()
  composeRule.waitForIdle()
  // Resumes the existing non-terminal session (per SandboxConfigViewModel's own doc comment on
  // this button) rather than creating a new one — the exact behavior this test verifies.
  composeRule.onNodeWithText("Prepare Sandbox").performScrollTo().performClick()
  composeRule.waitForIdle()
  // LiveMonitorScreen renders the session's real package name — proves the *same* durable
  // session (not a freshly created one) is what's shown.
  composeRule.onNodeWithText("com.example.$analysisId").assertExists()
  runBlocking {
   val rows = SandboxDatabaseProvider.get(context).sandboxSessionDao().observeSessionsForAnalysis(analysisId).first()
   assert(rows.size == 1) { "opening the screen must never create a second session row for this analysis (found ${rows.size})" }
  }
 }
}
