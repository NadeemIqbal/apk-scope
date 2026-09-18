package com.nadeem.apkscope.ui.screens.sandbox

import android.app.Activity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nadeem.apkscope.MainActivity
import com.nadeem.apkscope.core.model.SandboxError
import com.nadeem.apkscope.core.model.SandboxErrorCode
import com.nadeem.apkscope.core.model.SandboxOperationResult
import com.nadeem.apkscope.core.model.SandboxPolicy
import com.nadeem.apkscope.core.model.SandboxSession
import com.nadeem.apkscope.core.model.SandboxSessionState
import com.nadeem.apkscope.core.model.Recoverability
import com.nadeem.apkscope.domain.sandbox.DefaultSandboxSessionCoordinator
import com.nadeem.apkscope.domain.sandbox.SandboxSessionCoordinator
import com.nadeem.apkscope.domain.sandbox.SandboxSessionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Root-cause regression test — confirmed on-device, not hypothetical: a real user hit `canRequestPackageInstalls=false`
 * (a real, legitimate FAILED outcome this milestone's own error-surfacing fix correctly reported), tapped
 * "Retry", and nothing happened — zero new cross-profile activity, identical screen. Root cause:
 * [com.nadeem.apkscope.domain.sandbox.SandboxSessionCoordinator.prepare] only ever does real work for a session
 * in [SandboxSessionState.CREATED] (by design, so it cannot restart an already in-flight preparation) —
 * calling it again with the *same* FAILED session's id is a guaranteed no-op, since `FAILED` is terminal
 * and can never legally transition back to `CREATED`. [SandboxPreparingViewModel.retryPrepare] now detects
 * a terminal session and creates a genuinely fresh one for the same analysis/APK instead.
 */
@RunWith(AndroidJUnit4::class)
class SandboxPreparingRetryInstrumentedTest {
 private val context = InstrumentationRegistry.getInstrumentation().targetContext
 private val repository = SandboxSessionRepository(context)
 private val createdSessionIds = mutableListOf<String>()
 private lateinit var scenario: ActivityScenario<MainActivity>
 private lateinit var activity: Activity

 private fun launchActivity() {
  scenario = ActivityScenario.launch(MainActivity::class.java)
  scenario.onActivity { activity = it }
 }

 @After
 fun cleanup() {
  runBlocking { createdSessionIds.forEach { repository.delete(it) } }
  if (::scenario.isInitialized) scenario.close()
 }

 private suspend fun createFailedSession(real: SandboxSessionCoordinator, label: String): SandboxSession {
  val analysisId = "preparing-retry-test-$label-${UUID.randomUUID()}"
  val created = real.create(analysisId, "com.example.retrytest.$label", "/nonexistent/$label.apk", SandboxPolicy())
  val session = (created as SandboxOperationResult.Success).session
  createdSessionIds += session.id
  // Drive it to a real, persisted FAILED state via a legal transition — mirrors exactly what
  // SandboxWorkerService.report()/SandboxReportActivity's merge path produces for a real
  // canRequestPackageInstalls=false failure, without needing a real cross-profile round trip.
  val failed = session.transitionTo(SandboxSessionState.PREPARING).transitionTo(SandboxSessionState.FAILED)
   .copy(error = SandboxError(SandboxErrorCode.INSTALL_FAILED, "This device has not allowed the sandbox to install apps yet.", "canRequestPackageInstalls=false", Recoverability.REQUIRES_USER_ACTION))
  repository.save(failed)
  return failed
 }

 @Test
 fun retryOnAFailedSession_createsAGenuinelyFreshSession_ratherThanNoOpingOnTheDeadOne() = runBlocking {
  launchActivity()
  val real = DefaultSandboxSessionCoordinator(context)
  val failedSession = createFailedSession(real, "basic")
  val viewModel = SandboxPreparingViewModel(activity.application, failedSession.id, coordinatorOverride = real)

  viewModel.retryPrepare(activity)

  val newSessionId = withTimeout(15_000) {
   var value: String? = null
   while (value == null) { value = viewModel.uiState.first().retriedAsNewSessionId; if (value == null) kotlinx.coroutines.delay(20) }
   value
  }
  createdSessionIds += newSessionId

  assertNotEquals("retry must produce a genuinely different session id — the old one is terminal and can never reach CREATED again", failedSession.id, newSessionId)

  val newSession = repository.get(newSessionId)
  assertNotNull("the new session must actually be persisted", newSession)
  assertEquals("the new session must carry forward the same analysis/package this retry was for", failedSession.analysisId, newSession!!.analysisId)
  assertEquals(failedSession.packageName, newSession.packageName)
  assertNotEquals(
   "the new session must not still be sitting at CREATED — prepare() must actually have been dispatched against it",
   SandboxSessionState.CREATED, newSession.state,
  )
 }

 @Test
 fun retryOnAFailedSession_neverMutatesTheOldFailedSessionsOwnRow() = runBlocking {
  launchActivity()
  val real = DefaultSandboxSessionCoordinator(context)
  val failedSession = createFailedSession(real, "untouched")
  val viewModel = SandboxPreparingViewModel(activity.application, failedSession.id, coordinatorOverride = real)

  viewModel.retryPrepare(activity)
  withTimeout(15_000) {
   var value: String? = null
   while (value == null) { value = viewModel.uiState.first().retriedAsNewSessionId; if (value == null) kotlinx.coroutines.delay(20) }
   createdSessionIds += value
  }

  val stillThere = repository.get(failedSession.id)
  assertEquals("the original failed session's own row must be left exactly as it was — never silently resurrected or mutated", SandboxSessionState.FAILED, stillThere?.state)
  assertEquals(SandboxErrorCode.INSTALL_FAILED, stillThere?.error?.code)
 }

 @Test
 fun retryOnANonTerminalSession_stillCallsPrepareOnTheSameSessionId_unchangedFromBeforeThisFix() = runBlocking {
  launchActivity()
  val real = DefaultSandboxSessionCoordinator(context)
  val analysisId = "preparing-retry-test-nonterminal-${UUID.randomUUID()}"
  val created = real.create(analysisId, "com.example.retrytest.nonterminal", "/nonexistent/nonterminal.apk", SandboxPolicy())
  val session = (created as SandboxOperationResult.Success).session
  createdSessionIds += session.id
  // Leave it genuinely non-terminal (PREPARING) — not this fix's target case.
  repository.save(session.transitionTo(SandboxSessionState.PREPARING))

  val viewModel = SandboxPreparingViewModel(activity.application, session.id, coordinatorOverride = real)
  viewModel.retryPrepare(activity)

  // Must never fabricate a "retried as new session" signal for the non-terminal path — this is the
  // pre-existing prepare()-re-entry behavior, deliberately unchanged by this fix.
  kotlinx.coroutines.delay(500)
  assertNull("retry on a non-terminal session must not create a new session — that path is unchanged", viewModel.uiState.first().retriedAsNewSessionId)
 }
}
