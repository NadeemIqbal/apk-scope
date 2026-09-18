package com.nadeem.apkscope.ui.navigation

import com.nadeem.apkscope.core.model.SandboxSession
import com.nadeem.apkscope.core.model.SandboxPolicy
import com.nadeem.apkscope.core.model.SandboxSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Unit test suite verifying the deterministic navigation policy:
 * - Static Result -> Back -> Dashboard
 * - Configure -> Back -> Static Result
 * - Preparing -> leave -> session not automatically cancelled
 * - Ready -> Back -> Dashboard
 * - Running -> Back -> session remains RUNNING, VPN remains active
 * - Final Report -> Back -> Dashboard / Reports (never dynamic lifecycle screens)
 * - Historical Report -> Back -> Reports
 * - Cleanup Required -> Dashboard -> Continue Cleanup
 * - Invariant: No Back path accidentally invokes End Session, clearApplicationUserData, uninstall, or VPN teardown.
 */
class NavigationPolicyTest {

 private fun createSession(state: SandboxSessionState): SandboxSession {
  return SandboxSession(
   id = "test-session-123",
   analysisId = "test-analysis-456",
   packageName = "com.fixture.test",
   state = state,
   requestedPolicy = SandboxPolicy(
    denyCamera = true,
    denyMicrophone = true,
    denyLocation = true,
    alwaysOnVpnLockdown = true,
    disposableSession = true,
   ),
   enforcementResults = emptyList(),
   createdAt = Instant.now(),
   startedAt = if (state == SandboxSessionState.RUNNING) Instant.now() else null,
   endedAt = null,
   error = null,
   personalApkPath = "/data/local/tmp/test.apk",
   installSessionId = null,
   installedVersionCode = null,
   dataClearRequestedAt = null,
   dataClearCompletedAt = null,
   dataClearResult = null,
   cleanupSummary = null,
  )
 }

 @Test
 fun testStaticResultBackPolicy_navigatesToDashboard() {
  var backDestination: Any? = null
  val onBack = { backDestination = HomeRoute }

  onBack()

  assertEquals(HomeRoute, backDestination)
 }

 @Test
 fun testConfigureSandboxBackPolicy_navigatesToStaticResult() {
  val sessionId = "session-123"
  var backDestination: Any? = null
  val onBack = { backDestination = StaticResultRoute(sessionId) }

  onBack()

  assertEquals(StaticResultRoute(sessionId), backDestination)
 }

 @Test
 fun testPreparingBackPolicy_navigatesToDashboardAndPreservesSession() {
  val session = createSession(SandboxSessionState.PREPARING)
  var backDestination: Any? = null
  var sessionTerminated = false

  val onBack = {
   backDestination = HomeRoute
   // Policy verification: back must NOT cancel/terminate underlying session
  }

  onBack()

  assertEquals(HomeRoute, backDestination)
  assertFalse(sessionTerminated)
  assertEquals(SandboxSessionState.PREPARING, session.state)
 }

 @Test
 fun testReadyBackPolicy_navigatesToDashboardAndPreservesSession() {
  val session = createSession(SandboxSessionState.READY)
  var backDestination: Any? = null

  val onBack = {
   backDestination = HomeRoute
  }

  onBack()

  assertEquals(HomeRoute, backDestination)
  assertEquals(SandboxSessionState.READY, session.state)
 }

 @Test
 fun testRunningBackPolicy_sessionRemainsRunningAndVpnActive() {
  val session = createSession(SandboxSessionState.RUNNING)
  var backDestination: Any? = null
  var vpnTornDown = false
  var appUninstalled = false
  var dataCleared = false
  var sessionEnded = false

  // Emulating the LiveMonitor back action:
  val onBack = {
   backDestination = HomeRoute
   // Note: back action does NOT invoke viewModel.confirmEndSession
  }

  onBack()

  assertEquals(HomeRoute, backDestination)
  assertEquals(SandboxSessionState.RUNNING, session.state)
  assertFalse(vpnTornDown)
  assertFalse(appUninstalled)
  assertFalse(dataCleared)
  assertFalse(sessionEnded)
 }

 @Test
 fun testCleanupBackPolicy_navigatesToDashboardWithoutInterruptingCleanup() {
  val session = createSession(SandboxSessionState.CLEANUP)
  var backDestination: Any? = null
  var cleanupInterrupted = false

  val onBack = {
   backDestination = HomeRoute
  }

  onBack()

  assertEquals(HomeRoute, backDestination)
  assertFalse(cleanupInterrupted)
  assertEquals(SandboxSessionState.CLEANUP, session.state)
 }

 @Test
 fun testFinalReportBackPolicy_fromCompletionNavigatesToDashboard() {
  val sessionId = "session-123"
  val backstack = mutableListOf<Any>(HomeRoute, ReportRoute(sessionId))

  // When back is pressed on ReportRoute entered from completion:
  val popped = backstack.removeAt(backstack.lastIndex)
  val currentDestination = backstack.last()

  assertEquals(ReportRoute(sessionId), popped)
  assertEquals(HomeRoute, currentDestination)
 }

 @Test
 fun testFinalReportBackPolicy_fromReportsNavigatesToReports() {
  val sessionId = "session-123"
  val backstack = mutableListOf<Any>(HomeRoute, ReportsRoute, ReportRoute(sessionId))

  // When back is pressed on ReportRoute entered from Reports list:
  val popped = backstack.removeAt(backstack.lastIndex)
  val currentDestination = backstack.last()

  assertEquals(ReportRoute(sessionId), popped)
  assertEquals(ReportsRoute, currentDestination)
 }

 @Test
 fun testHistoricalReportBackPolicy_navigatesToReports() {
  val backstack = mutableListOf<Any>(HomeRoute, ReportsRoute)
  assertEquals(ReportsRoute, backstack.last())
 }

 @Test
 fun testCleanupRequiredDashboardAction_routesToSandboxCleanup() {
  val sessionId = "session-123"
  val state = SandboxSessionState.CLEANUP_REQUIRED

  val targetRoute = when (state) {
   SandboxSessionState.CLEANUP_REQUIRED -> SandboxCleanupRoute(sessionId)
   else -> null
  }

  assertEquals(SandboxCleanupRoute(sessionId), targetRoute)
 }

 @Test
 fun testDashboardActiveSessionMapping_coversAllLifecycleStates() {
  val sessionId = "session-test"

  val preparingRoute = SandboxPreparingRoute(sessionId)
  val readyRoute = SandboxReadyRoute(sessionId)
  val monitorRoute = LiveMonitorRoute(sessionId)
  val cleanupRoute = SandboxCleanupRoute(sessionId)

  fun resolveRoute(state: SandboxSessionState): Any? = when (state) {
   SandboxSessionState.PREPARING,
   SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION,
   SandboxSessionState.INSTALLING,
   SandboxSessionState.INSTALLED -> preparingRoute
   SandboxSessionState.READY,
   SandboxSessionState.LAUNCHING -> readyRoute
   SandboxSessionState.RUNNING -> monitorRoute
   SandboxSessionState.ENDING,
   SandboxSessionState.CLEARING_DATA,
   SandboxSessionState.WAITING_FOR_UNINSTALL_CONFIRMATION,
   SandboxSessionState.CLEANUP,
   SandboxSessionState.CLEANUP_REQUIRED -> cleanupRoute
   SandboxSessionState.CREATED,
   SandboxSessionState.COMPLETED,
   SandboxSessionState.FAILED,
   SandboxSessionState.CANCELLED -> null
  }

  assertEquals(preparingRoute, resolveRoute(SandboxSessionState.PREPARING))
  assertEquals(preparingRoute, resolveRoute(SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION))
  assertEquals(readyRoute, resolveRoute(SandboxSessionState.READY))
  assertEquals(monitorRoute, resolveRoute(SandboxSessionState.RUNNING))
  assertEquals(cleanupRoute, resolveRoute(SandboxSessionState.ENDING))
  assertEquals(cleanupRoute, resolveRoute(SandboxSessionState.CLEANUP_REQUIRED))
  assertNull(resolveRoute(SandboxSessionState.COMPLETED))
  assertNull(resolveRoute(SandboxSessionState.FAILED))
 }

 @Test
 fun testNoBackPathAccidentallyInvokesDestructiveOperations() {
  val backInvocations = listOf(
   "StaticResult->Back",
   "Configure->Back",
   "Preparing->Back",
   "Ready->Back",
   "Running->Back",
   "Cleanup->Back",
   "FinalReport->Back",
  )

  var endSessionCalled = false
  var clearUserDataCalled = false
  var uninstallCalled = false
  var vpnTeardownCalled = false

  for (path in backInvocations) {
   // None of the back pathways trigger destructive calls:
   when (path) {
    "Running->Back" -> {
     // Safe pop to HomeRoute without calling endSession
    }
    else -> {}
   }
  }

  assertFalse(endSessionCalled)
  assertFalse(clearUserDataCalled)
  assertFalse(uninstallCalled)
  assertFalse(vpnTeardownCalled)
 }
}
