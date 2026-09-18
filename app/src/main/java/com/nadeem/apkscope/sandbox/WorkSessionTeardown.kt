package com.nadeem.apkscope.sandbox

import android.content.Context
import android.content.Intent
import com.nadeem.apkscope.core.model.SandboxSessionState

/**
 * Checkpoint 5.3 root-cause fix — the concrete orphan Checkpoint 5.2 found: `runPrepareSequence`
 * calls `SandboxVpnService.ACTION_ESTABLISH` to bring up network isolation before a session ever
 * reaches `RUNNING`, but until this fix, nothing on any failure path between `PREPARING` and
 * `RUNNING` ever called `ACTION_CLOSE` — only the full End Session sequence
 * (`SandboxWorkerService.runEndSessionSequence`) did, and that is reachable only from `RUNNING`.
 * Every install-denial, install-failure, notification-unavailable-gate, or environment-preflight
 * failure left Work's VPN tunnel and its foreground notification running forever, attributed to a
 * session Personal had already recorded as terminal.
 *
 * Called from every FAILED/CANCELLED report site in [SandboxWorkerService] and
 * [SandboxInstallResultReceiver] (their two `report()` functions, not each call site individually —
 * this is deliberately centralized so no future failure path can forget it). Safe/idempotent to call
 * for a session that never established a tunnel, or one already torn down: [SandboxVpnService]'s own
 * `closeAll()` no-ops on already-null state, and this function itself only fires the intent when the
 * live tunnel is actually still attributed to *this* [sessionId] — never a blind close that could
 * tear down some other, legitimately active session.
 */
object WorkSessionTeardown {
 /** True exactly for the states this fix must react to — reached from a lifecycle branch that never itself runs the End Session sequence. */
 private val statesRequiringEarlyTeardown = setOf(SandboxSessionState.FAILED, SandboxSessionState.CANCELLED)

 fun tearDownIfEarlyTermination(context: Context, sessionId: String, reportedState: SandboxSessionState) {
  if (reportedState !in statesRequiringEarlyTeardown) return
  if (SandboxVpnService.activeSessionId != sessionId) return
  context.startForegroundService(Intent(context, SandboxVpnService::class.java).setAction(SandboxVpnService.ACTION_CLOSE))
 }
}
