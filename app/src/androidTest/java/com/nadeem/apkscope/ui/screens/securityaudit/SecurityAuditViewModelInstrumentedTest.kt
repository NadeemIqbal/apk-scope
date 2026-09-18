package com.nadeem.apkscope.ui.screens.securityaudit

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nadeem.apkscope.core.database.AnalysisSessionEntity
import com.nadeem.apkscope.core.database.SandboxDatabaseProvider
import com.nadeem.apkscope.core.risk.audit.SecurityAuditReport
import com.nadeem.apkscope.domain.AnalysisNotFoundException
import com.nadeem.apkscope.domain.SecurityAuditRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Milestone 10 correction pass: closes the gap the Phase 10.2 checkpoint named explicitly —
 * `RUNNING`/`CANCELLED`/`ERROR` were implemented but never exercised, because a real static audit
 * completes too fast for a manual on-device tap-through to interrupt. [DelayedFakeRepository] makes
 * the race deterministic instead of trying (and failing) to win it by chance.
 */
@RunWith(AndroidJUnit4::class)
class SecurityAuditViewModelInstrumentedTest {
 private val context = InstrumentationRegistry.getInstrumentation().targetContext
 private val application = context.applicationContext as Application
 private val analysisDao = SandboxDatabaseProvider.get(context).analysisSessionDao()

 /** Delays before delegating to a real [SecurityAuditRepository] — the delay is what makes cancelling mid-run deterministic instead of a timing gamble. */
 private class DelayedFakeRepository(context: android.content.Context, private val delayMs: Long) : SecurityAuditRepository(context) {
  override suspend fun runAndPersistAudit(sessionId: String): SecurityAuditReport {
   delay(delayMs)
   return super.runAndPersistAudit(sessionId)
  }
 }

 private class FailingFakeRepository(context: android.content.Context, private val failure: Exception) : SecurityAuditRepository(context) {
  override suspend fun runAndPersistAudit(sessionId: String): SecurityAuditReport = throw failure
 }

 private fun seedAnalysis(sessionId: String) = runBlocking {
  analysisDao.insertSession(
   AnalysisSessionEntity(
    sessionId = sessionId, packageName = "com.example.vmtest", appName = "VM Test", versionName = "1.0",
    versionCode = 1, sha256 = "feedface", analyzedAtEpochMs = System.currentTimeMillis(), minSdkVersion = 24,
    targetSdkVersion = 34, debuggable = false, signatureVerified = true, signatureDetail = null,
    nativeLibraryAbis = emptyList(), permissionCount = 0, componentTotalCount = 0, componentExportedCount = 0,
    riskScore = 0, riskLevel = "LOW", riskEngineVersion = "static-v1",
   ),
  )
 }

 @Test
 fun cancel_beforeRunCompletes_transitionsToCancelledAndPersistsNothing() = runBlocking {
  val sessionId = "vm-cancel-${UUID.randomUUID()}"
  seedAnalysis(sessionId)
  val fakeRepo = DelayedFakeRepository(application, delayMs = 2000)
  val viewModel = SecurityAuditViewModel(application, sessionId, fakeRepo)

  // Let the init{} block's initial load settle to EMPTY first.
  awaitPhase(viewModel, SecurityAuditPhase.EMPTY, timeoutMs = 3000)

  viewModel.runAudit()
  awaitPhase(viewModel, SecurityAuditPhase.RUNNING, timeoutMs = 1000)
  viewModel.cancel()

  val state = viewModel.uiState.first { it.phase == SecurityAuditPhase.CANCELLED || it.phase == SecurityAuditPhase.LOADED }
  assertEquals("must land on CANCELLED, not race through to LOADED", SecurityAuditPhase.CANCELLED, state.phase)

  // The single atomic persistence write must never have been reached — assert no audit exists.
  delay(2500) // outlast the fake's delay in case cancellation somehow failed to actually stop it
  assertNull(
   "cancelling before the repository's one atomic write must leave nothing persisted",
   SandboxDatabaseProvider.get(context).securityAuditDao().getAuditForAnalysis(sessionId),
  )
 }

 @Test
 fun runAudit_whenRepositoryThrowsAnalysisNotFound_transitionsToErrorWithSpecificMessage() = runBlocking {
  val sessionId = "vm-notfound-${UUID.randomUUID()}"
  seedAnalysis(sessionId)
  val fakeRepo = FailingFakeRepository(application, AnalysisNotFoundException(sessionId))
  val viewModel = SecurityAuditViewModel(application, sessionId, fakeRepo)
  awaitPhase(viewModel, SecurityAuditPhase.EMPTY, timeoutMs = 3000)

  viewModel.runAudit()

  val state = viewModel.uiState.first { it.phase == SecurityAuditPhase.ERROR }
  assertEquals(SecurityAuditPhase.ERROR, state.phase)
  assertEquals("The underlying analysis could not be found — it may have been deleted.", state.errorMessage)
 }

 @Test
 fun runAudit_whenRepositoryThrowsGenericException_transitionsToErrorWithItsMessage() = runBlocking {
  val sessionId = "vm-error-${UUID.randomUUID()}"
  seedAnalysis(sessionId)
  val fakeRepo = FailingFakeRepository(application, IllegalStateException("simulated disk failure"))
  val viewModel = SecurityAuditViewModel(application, sessionId, fakeRepo)
  awaitPhase(viewModel, SecurityAuditPhase.EMPTY, timeoutMs = 3000)

  viewModel.runAudit()

  val state = viewModel.uiState.first { it.phase == SecurityAuditPhase.ERROR }
  assertEquals(SecurityAuditPhase.ERROR, state.phase)
  assertEquals("simulated disk failure", state.errorMessage)
 }

 private suspend fun awaitPhase(viewModel: SecurityAuditViewModel, phase: SecurityAuditPhase, timeoutMs: Long) {
  kotlinx.coroutines.withTimeout(timeoutMs) {
   viewModel.uiState.first { it.phase == phase }
  }
 }
}
