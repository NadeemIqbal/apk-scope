package com.nadeem.apkscope.domain.sandbox

import com.nadeem.apkscope.core.model.CleanupSummary
import com.nadeem.apkscope.core.model.IllegalSandboxTransitionException
import com.nadeem.apkscope.core.model.PolicyEnforcementResult
import com.nadeem.apkscope.core.model.SandboxSession
import com.nadeem.apkscope.core.model.SandboxSessionState
import com.nadeem.apkscope.sandbox.SandboxStatusReport
import java.time.Instant

/**
 * Checkpoint 4.1: the one place a [SandboxStatusReport]'s facts are applied onto a [SandboxSession]
 * — pure, Android-free, unit-testable. Used identically by both delivery paths a report can now
 * arrive by: the original **push** (`SandboxReportActivity`, a best-effort cross-profile
 * `Handoff.send()` from Work that may or may not survive Android's background-activity-launch
 * restrictions) and the new **pull** (`DefaultSandboxSessionCoordinator.importEvidence`, a
 * foreground-initiated `startActivityForResult` round trip that reads Work's own durable evidence
 * store on demand). Neither path duplicates this merge logic — a report is a report, regardless of
 * which transport delivered it.
 */
object SandboxSessionReportMerger {
 /** Applies every non-null fact in [report] onto [session]. Enforcement reports are policy patches:
  * a newer result replaces the same policy, while policies omitted by that report remain known.
  * Never transitions state itself — the caller decides the target state (a plain status update vs.
  * an error vs. a cleanup outcome need different transition rules), via [safeTransition]. */
 fun mergeFacts(session: SandboxSession, report: SandboxStatusReport): SandboxSession {
  var next = session
  if (report.enforcements.isNotEmpty()) {
   next = next.copy(enforcementResults = mergeEnforcements(session.enforcementResults, report.enforcements))
  }
  report.installSessionId?.let { next = next.copy(installSessionId = it) }
  report.installedVersionCode?.let { next = next.copy(installedVersionCode = it) }
  report.dataClearRequestedAtEpochMs?.let { next = next.copy(dataClearRequestedAt = Instant.ofEpochMilli(it)) }
  report.dataClearCompletedAtEpochMs?.let { next = next.copy(dataClearCompletedAt = Instant.ofEpochMilli(it)) }
  report.dataClearResult?.let { next = next.copy(dataClearResult = it) }
  return next
 }

 private fun mergeEnforcements(
  existing: List<PolicyEnforcementResult>,
  patch: List<PolicyEnforcementResult>,
 ): List<PolicyEnforcementResult> {
  val merged = linkedMapOf<com.nadeem.apkscope.core.model.SandboxPolicyType, PolicyEnforcementResult>()
  existing.forEach { merged[it.policy] = it }
  patch.forEach { merged[it.policy] = it }
  return merged.values.toList()
 }

 /** Item 18/checkpoint 4.1 item 15: merges the work-verifiable half of a [CleanupSummary] with the one fact only the personal side can verify about itself (its own temp-file/URI-grant cleanup) — `networkSessionClosed` is always true once this runs, since reaching this merge at all means the personal-side coordinator/report-handler is the one closing out the session. */
 fun mergeCleanupSummary(workCleanup: CleanupSummary, personalCleanupDone: Boolean): CleanupSummary =
  workCleanup.copy(personalTempApkDeleted = personalCleanupDone, uriGrantReleased = personalCleanupDone, networkSessionClosed = true)

 /** Applies a legal transition, or returns the session unchanged if already at [target], or `null` if the transition is illegal — never throws, never silently mutates past what the state machine allows. */
 fun safeTransition(session: SandboxSession, target: SandboxSessionState): SandboxSession? =
  if (session.state == target) session
  else try { session.transitionTo(target) } catch (_: IllegalSandboxTransitionException) { null }
}
