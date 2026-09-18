package com.nadeem.apkscope.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item 1/21: the state machine's transition table, tested directly — no Android, no coordinator, just the pure rules. */
class SandboxStateMachineTest {
 @Test fun happyPathIsFullyLegal() {
  val path = listOf(
   SandboxSessionState.CREATED, SandboxSessionState.PREPARING, SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION,
   SandboxSessionState.INSTALLING, SandboxSessionState.INSTALLED, SandboxSessionState.READY, SandboxSessionState.LAUNCHING,
   SandboxSessionState.RUNNING, SandboxSessionState.ENDING, SandboxSessionState.CLEARING_DATA,
   SandboxSessionState.WAITING_FOR_UNINSTALL_CONFIRMATION, SandboxSessionState.CLEANUP, SandboxSessionState.COMPLETED,
  )
  for (i in 0 until path.size - 1) {
   assertTrue("${path[i]} -> ${path[i + 1]} should be legal", SandboxStateMachine.canTransition(path[i], path[i + 1]))
  }
 }

 @Test fun terminalStatesHaveNoLegalNextState() {
  for (state in listOf(SandboxSessionState.COMPLETED, SandboxSessionState.FAILED, SandboxSessionState.CANCELLED)) {
   assertTrue(SandboxStateMachine.legalNextStates(state).isEmpty())
   assertTrue(SandboxStateMachine.isTerminal(state))
  }
 }

 @Test fun cannotSkipStatesForward() {
  assertFalse(SandboxStateMachine.canTransition(SandboxSessionState.CREATED, SandboxSessionState.READY))
  assertFalse(SandboxStateMachine.canTransition(SandboxSessionState.PREPARING, SandboxSessionState.RUNNING))
  assertFalse(SandboxStateMachine.canTransition(SandboxSessionState.READY, SandboxSessionState.COMPLETED))
 }

 @Test fun cannotGoBackward() {
  assertFalse(SandboxStateMachine.canTransition(SandboxSessionState.READY, SandboxSessionState.PREPARING))
  assertFalse(SandboxStateMachine.canTransition(SandboxSessionState.RUNNING, SandboxSessionState.READY))
  assertFalse(SandboxStateMachine.canTransition(SandboxSessionState.CLEANUP, SandboxSessionState.RUNNING))
 }

 @Test fun cleanupRequiredIsReachableFromEveryCleanupPhaseAndCanRetryOrComplete() {
  assertTrue(SandboxStateMachine.canTransition(SandboxSessionState.CLEARING_DATA, SandboxSessionState.CLEANUP_REQUIRED))
  assertTrue(SandboxStateMachine.canTransition(SandboxSessionState.WAITING_FOR_UNINSTALL_CONFIRMATION, SandboxSessionState.CLEANUP_REQUIRED))
  assertTrue(SandboxStateMachine.canTransition(SandboxSessionState.CLEANUP, SandboxSessionState.CLEANUP_REQUIRED))
  assertTrue(SandboxStateMachine.canTransition(SandboxSessionState.CLEANUP_REQUIRED, SandboxSessionState.CLEANUP))
  assertTrue(SandboxStateMachine.canTransition(SandboxSessionState.CLEANUP_REQUIRED, SandboxSessionState.COMPLETED))
 }

 @Test fun readySessionCanEnterCleanupWhenEndedBeforeLaunch() {
  assertTrue(SandboxStateMachine.canTransition(SandboxSessionState.READY, SandboxSessionState.ENDING))
 }

 @Test fun everyNonTerminalStateCanFail() {
  for (state in SandboxSessionState.entries.filterNot { SandboxStateMachine.isTerminal(it) }) {
   assertTrue("$state should be able to transition to FAILED", SandboxStateMachine.canTransition(state, SandboxSessionState.FAILED))
  }
 }

 @Test fun onlyPreRunStatesCanBeCancelled() {
  val cancellable = listOf(
   SandboxSessionState.CREATED, SandboxSessionState.PREPARING, SandboxSessionState.WAITING_FOR_INSTALL_CONFIRMATION,
   SandboxSessionState.INSTALLING, SandboxSessionState.INSTALLED, SandboxSessionState.READY, SandboxSessionState.LAUNCHING,
  )
  for (state in cancellable) assertTrue("$state should be cancellable", SandboxStateMachine.canTransition(state, SandboxSessionState.CANCELLED))
  val notCancellable = listOf(SandboxSessionState.RUNNING, SandboxSessionState.ENDING, SandboxSessionState.CLEARING_DATA, SandboxSessionState.CLEANUP)
  for (state in notCancellable) assertFalse("$state should not be cancellable", SandboxStateMachine.canTransition(state, SandboxSessionState.CANCELLED))
 }

 @Test fun sessionTransitionToAppliesLegalTransition() {
  val session = testSession(state = SandboxSessionState.CREATED)
  val next = session.transitionTo(SandboxSessionState.PREPARING)
  assertEquals(SandboxSessionState.PREPARING, next.state)
 }

 @Test(expected = IllegalSandboxTransitionException::class)
 fun sessionTransitionToThrowsOnIllegalTransition() {
  testSession(state = SandboxSessionState.CREATED).transitionTo(SandboxSessionState.RUNNING)
 }

 @Test fun illegalTransitionNeverMutatesState() {
  val session = testSession(state = SandboxSessionState.CREATED)
  try {
   session.transitionTo(SandboxSessionState.RUNNING)
  } catch (_: IllegalSandboxTransitionException) {
   // expected — session itself is immutable (a data class), so there is nothing to "roll back";
   // this test exists to document and lock in that guarantee rather than merely hope for it.
  }
  assertEquals(SandboxSessionState.CREATED, session.state)
 }
}
