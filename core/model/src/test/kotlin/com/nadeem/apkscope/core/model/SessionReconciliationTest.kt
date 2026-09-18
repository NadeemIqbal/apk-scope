package com.nadeem.apkscope.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionReconciliationTest {
 @Test fun matchingRunningRecovers() {
  assertEquals(
   SessionReconciliation.Outcome.RECOVER_RUNNING,
   SessionReconciliation.reconcile("s1", SandboxSessionState.RUNNING, workActive = true, workActiveSessionId = "s1"),
  )
 }

 @Test fun personalMissingPlusWorkRunningIsOrphan() {
  assertEquals(
   SessionReconciliation.Outcome.ORPHAN_DETECTED,
   SessionReconciliation.reconcile(personalSessionId = null, personalState = null, workActive = true, workActiveSessionId = "s1"),
  )
 }

 @Test fun personalTerminalPlusWorkRunningIsStale() {
  for (terminal in listOf(SandboxSessionState.COMPLETED, SandboxSessionState.FAILED, SandboxSessionState.CANCELLED)) {
   assertEquals(
    "state=$terminal",
    SessionReconciliation.Outcome.STALE_WORK_SESSION,
    SessionReconciliation.reconcile("s1", terminal, workActive = true, workActiveSessionId = "s1"),
   )
  }
 }

 @Test fun differentSessionIdWhilePersonalRunningIsConflict() {
  assertEquals(
   SessionReconciliation.Outcome.CONFLICT,
   SessionReconciliation.reconcile("s1", SandboxSessionState.RUNNING, workActive = true, workActiveSessionId = "s2"),
  )
 }

 @Test fun differentSessionIdWhilePersonalNotRunningIsOrphanNotConflict() {
  // Item 2: "never silently attach an unknown Work session to an unrelated Personal analysis" —
  // if Personal's own session for this id is not RUNNING, the live Work session belongs to nobody
  // Personal recognizes as active; treat as an orphan discovery, not a false conflict.
  assertEquals(
   SessionReconciliation.Outcome.ORPHAN_DETECTED,
   SessionReconciliation.reconcile("s1", SandboxSessionState.PREPARING, workActive = true, workActiveSessionId = "s2"),
  )
 }

 @Test fun noWorkActiveSessionIsNoAction() {
  assertEquals(
   SessionReconciliation.Outcome.NO_ACTION,
   SessionReconciliation.reconcile("s1", SandboxSessionState.RUNNING, workActive = false, workActiveSessionId = null),
  )
 }

 @Test fun matchingNonTerminalNonRunningIsNoAction() {
  // e.g. still PREPARING on both sides — nothing to reconcile yet.
  assertEquals(
   SessionReconciliation.Outcome.NO_ACTION,
   SessionReconciliation.reconcile("s1", SandboxSessionState.PREPARING, workActive = true, workActiveSessionId = "s1"),
  )
 }

 @Test fun personalRunningWorkAbsentIsInterrupted() {
  assertEquals(
   SessionReconciliation.Outcome.INTERRUPTED,
   SessionReconciliation.personalRunningButWorkAbsent(SandboxSessionState.RUNNING, workActive = false, workActiveSessionId = null, personalSessionId = "s1"),
  )
 }

 @Test fun personalRunningWorkDifferentSessionIsInterrupted() {
  assertEquals(
   SessionReconciliation.Outcome.INTERRUPTED,
   SessionReconciliation.personalRunningButWorkAbsent(SandboxSessionState.RUNNING, workActive = true, workActiveSessionId = "other", personalSessionId = "s1"),
  )
 }

 @Test fun personalRunningWorkMatchingIsNoAction() {
  assertEquals(
   SessionReconciliation.Outcome.NO_ACTION,
   SessionReconciliation.personalRunningButWorkAbsent(SandboxSessionState.RUNNING, workActive = true, workActiveSessionId = "s1", personalSessionId = "s1"),
  )
 }

 @Test fun personalNotRunningNeverInterrupts() {
  for (state in SandboxSessionState.entries.filter { it != SandboxSessionState.RUNNING }) {
   assertEquals(
    "state=$state",
    SessionReconciliation.Outcome.NO_ACTION,
    SessionReconciliation.personalRunningButWorkAbsent(state, workActive = false, workActiveSessionId = null, personalSessionId = "s1"),
   )
  }
 }
}
