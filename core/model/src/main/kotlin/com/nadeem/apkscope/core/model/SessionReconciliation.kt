package com.nadeem.apkscope.core.model

/**
 * Checkpoint 5.3, item 2/3/4: Personal and Work keep independent durable stores (`sandbox.db` /
 * `work_evidence.db`) with no shared transaction — a lost cross-profile report, a `pm clear` on one
 * side only, or simply never having sent one yet, can leave them disagreeing about whether a
 * sandbox session is actually running. This is the one place that decision is made, as a pure
 * function over plain facts — never Android state read inline at the call site — so the exact
 * matrix Checkpoint 5.3 specifies is directly unit-testable without a device.
 *
 * Root-caused this checkpoint (see the exit report): the concrete orphan Checkpoint 5.2 found was
 * not cross-profile state loss or a test artifact — `SandboxWorkerService.runPrepareSequence` calls
 * `SandboxVpnService.ACTION_ESTABLISH` to bring up network isolation, but nothing on any
 * install-denial/failure path (before `RUNNING`) ever calls `ACTION_CLOSE`; only the full End
 * Session sequence does. Every Prepare attempt that does not reach `RUNNING` leaves Work's VPN +
 * foreground notification alive forever, attributed to a session Personal now considers terminal.
 * This object is the detection half of the fix; the emission half is
 * `SandboxWorkerService`/`SandboxInstallResultReceiver` now tearing down network isolation on every
 * FAILED/CANCELLED transition reached before RUNNING (see `WorkSessionTeardown`).
 */
object SessionReconciliation {
    enum class Outcome {
        /** Personal and Work agree a session is running — nothing to correct, resume in place. */
        RECOVER_RUNNING,

        /** Personal still thinks the session is RUNNING but Work's tunnel/session is gone — a real interruption, not success. */
        INTERRUPTED,

        /** Work has an active session Personal has no record of at all — item 3's fail-safe "Unfinished sandbox session detected". */
        ORPHAN_DETECTED,

        /** Personal has a real record of this exact session, but it is already terminal, while Work still considers it active. */
        STALE_WORK_SESSION,

        /** Personal is RUNNING a *different* sessionId than the one Work reports active — never silently merge these. */
        CONFLICT,

        /** Nothing live on the Work side, and Personal's own state needs no correction from this check. */
        NO_ACTION,
    }

    /**
     * @param personalSessionId the session this reconciliation call is being made for, or null if the caller has no session context at all (e.g. a startup-wide sweep).
     * @param personalState Personal's durable state for [personalSessionId], or null if no such row exists in Personal's Room at all.
     * @param workActive whether Work currently reports an active (VPN-forwarding) sandbox session.
     * @param workActiveSessionId the sessionId Work's active session is attributed to, or null if [workActive] is false.
     */
    fun reconcile(
        personalSessionId: String?,
        personalState: SandboxSessionState?,
        workActive: Boolean,
        workActiveSessionId: String?,
    ): Outcome {
        if (!workActive || workActiveSessionId == null) return Outcome.NO_ACTION
        if (personalSessionId == null || personalState == null) return Outcome.ORPHAN_DETECTED
        if (personalSessionId != workActiveSessionId) {
            // A different session is the one Personal is asking about than the one Work has live —
            // never assumed to be the same session under any circumstance (item 2's "never silently
            // attach an unknown Work session to an unrelated Personal analysis").
            return if (personalState == SandboxSessionState.RUNNING) Outcome.CONFLICT else Outcome.ORPHAN_DETECTED
        }
        return when (personalState) {
            SandboxSessionState.RUNNING -> Outcome.RECOVER_RUNNING
            SandboxSessionState.COMPLETED, SandboxSessionState.FAILED, SandboxSessionState.CANCELLED -> Outcome.STALE_WORK_SESSION
            else -> Outcome.NO_ACTION
        }
    }

    /** The reverse direction of the same matrix (item 2's "Personal RUNNING + Work not running"): Personal believes a session is live but Work's tunnel/session is not. Kept separate from [reconcile] because it is evaluated from Personal's side of a *specific* session it already knows about, not from a Work-active-session sweep. */
    fun personalRunningButWorkAbsent(
        personalState: SandboxSessionState,
        workActive: Boolean,
        workActiveSessionId: String?,
        personalSessionId: String
    ): Outcome =
        if (personalState == SandboxSessionState.RUNNING && !(workActive && workActiveSessionId == personalSessionId)) Outcome.INTERRUPTED
        else Outcome.NO_ACTION
}
