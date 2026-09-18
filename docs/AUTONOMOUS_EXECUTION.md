# Autonomous Execution

Operational guidance for running the Security Audit's re-evaluation and scripted guided-session steps
unattended (Phase 10.10). This document is **portable product documentation** — it describes mechanics
that apply to any device or machine running APK Scope. It intentionally contains no machine-specific
state. See "Where machine state actually lives" below for where that belongs instead.

Read [Security Audit](SECURITY_AUDIT.md) first for what autonomous execution does and does not automate
— in particular, it never drives the target app's UI on the user's behalf; it automates re-evaluation
and scheduling of already-defined checks.

## What "autonomous" means here

Two distinct things can run unattended:

1. **Static re-audit**: re-running the `security-audit-v1` rule catalog (`docs/SECURITY_AUDIT_RULES.md`)
   against an already-analyzed APK's stored `ApkAnalysisInput`. Cheap, deterministic, no device
   interaction required beyond reading already-persisted data.
2. **Scripted guided-session steps**: the subset of a guided session (Phase 10.5) that does not require
   the user physically interacting with the target app — for example, waiting out an observation window
   after a step's instruction was already shown and acknowledged. This does **not** include performing
   the instructed action itself; a guided session step that requires the user to tap something in the
   target app cannot be automated, and this document does not claim otherwise.

Both run entirely on-device, inside the same Work Profile constraints as every other capability in this
product (see [Architecture Constraints](ARCHITECTURE_CONSTRAINTS.md)). Neither requires network access,
an external service, or a credential.

## Scheduler limitations (Android platform constraints, not this app's choice)

Autonomous execution is bounded by Android's own background-execution model, not by a design choice this
product could opt out of:

- **`WorkManager`** is the only supported scheduling mechanism for unattended re-audit and scripted
  session steps — no raw `AlarmManager` wake-locks, no foreground-service-as-a-daemon workaround.
- **App Standby Buckets** throttle how often a background job actually runs based on the OS's own
  assessment of how often the user opens the app — an "Active" or "Working set" bucket runs jobs close
  to their requested interval; a "Rare" bucket may defer a job for many hours. This product does not
  attempt to elevate its own bucket.
- **Doze mode** defers background execution entirely outside maintenance windows when the device is
  stationary and screen-off. A scheduled re-audit will not run mid-Doze; it runs at the next maintenance
  window.
- **Execution time budget**: a `WorkManager` job has a bounded execution window (platform-enforced,
  currently on the order of minutes, not hours) before the OS may kill it. A full guided-session
  observation window that legitimately needs longer than that budget must be modeled as multiple
  checkpointed job runs (see below), never as one long-running job that assumes it will not be killed.
- **No guaranteed wall-clock latency.** "Scheduled every 6 hours" means "eligible to run every 6 hours,
  subject to the above" — never a hard real-time guarantee. Do not build a Security Audit requirement
  that depends on exact scheduling latency; see `docs/SECURITY_AUDIT_VERIFICATION.md`'s explicit note
  that autonomous-execution verification tests determinism and resumption, not wall-clock timing.

## Quota monitoring

"Quota" here means the platform's own job-execution allowance, not a paid API quota — this feature calls
no metered external service. Before scheduling or continuing autonomous work:

1. Check `WorkManager`'s own job state (`WorkInfo.State`) rather than assuming a scheduled job actually
   ran — a job silently deferred by Doze/App Standby reports as `ENQUEUED`, not `FAILED`; treat a
   long-`ENQUEUED` job as "waiting on quota," not as an error to retry aggressively.
2. Do not schedule autonomous work at a tighter interval than the device's current App Standby Bucket
   would honor — a request tighter than what the bucket allows just gets deferred by the platform, wastes
   a wake-up attempt, and tells you nothing you can act on.
3. If a scripted guided-session step's observation window is cut short by the execution time budget,
   record that explicitly (a distinct outcome from "no traffic observed") — running out of budget is not
   the same finding as the target app genuinely not exercising the capability under audit.

## Checkpointing

Every autonomous run must be resumable from its last completed unit of work, because it can be killed at
any point by the execution time budget, Doze, a device reboot, or the user force-stopping the app:

1. Checkpoint at rule-catalog granularity for static re-audits: after each rule evaluates, persist which
   rules have completed for this run before evaluating the next. A resumed run skips already-completed
   rules rather than re-evaluating (and potentially double-recording) them.
2. Checkpoint at session-step granularity for scripted guided-session portions: persist which step's
   observation window has closed and what its outcome was before starting the next step's window.
3. A checkpoint is local, on-device state — see "Where machine state actually lives" below. It is never
   committed to this repository and is never treated as portable across devices or app reinstalls.

## Resumption

On the next eligible `WorkManager` execution:

1. Read the existing checkpoint, if any, before starting new work.
2. If the checkpoint shows a fully completed run, do nothing (or produce a fresh run only if the
   schedule's own interval has elapsed since that completion — re-running immediately on every wake-up
   is not resumption, it is a bug).
3. If the checkpoint shows a partial run, continue from the next un-checkpointed unit of work. Never
   restart a rule or session step whose checkpoint already recorded a definite outcome.
4. A checkpoint with no recognizable schema version (e.g. left over from an earlier, incompatible
   catalog version — see `docs/SECURITY_AUDIT_RULES.md`'s versioning section) is discarded, not
   force-interpreted; a fresh run starts instead, and this is logged, not silent.

## How to stop automation

Autonomous execution has one authoritative off switch, matching how every opt-in capability in this
product already works (see [Architecture Constraints](ARCHITECTURE_CONSTRAINTS.md) — "inspection is
explicit opt-in and off by default"):

1. **In-app toggle** (the primary, supported path, once Phase 10.10 ships its UI): disabling autonomous
   Security Audit cancels the associated `WorkManager` unique work request
   (`WorkManager.cancelUniqueWork(...)`) immediately — no scheduled run fires after cancellation, and any
   in-progress run is allowed to finish its current checkpointed unit of work rather than being killed
   mid-write (avoids a corrupt partial checkpoint).
2. **Uninstall or disable the app**: removes all scheduled `WorkManager` jobs for it, same as any other
   Android app.
3. **Platform-level fallback**: the device's own Settings → Apps → APK Scope → "Force stop" or
   battery-optimization controls stop any pending background execution immediately, without needing the
   in-app toggle to be reachable (useful if the in-app toggle itself is the thing misbehaving).

There is no separate "kill switch" file, script, or hidden command outside of these three — a fourth,
undocumented stop path would itself be a support and security liability.

## Where machine state actually lives

Portable documentation (this file, and the rest of `docs/`) never contains:

- Actual checkpoint contents, job IDs, or last-run timestamps for any specific device or run.
- Any credential, API key, or token — this feature requires none; if a future phase ever introduces one,
  it is never stored in this file, in private planning state, or committed to this repository under any path.

Actual runtime checkpoint and scheduling state belongs in the app's own on-device storage (the same
`context.filesDir`-derived, atomic-write storage pattern Milestone 9 established for
`StaticAnalysisResultStore` and the URL-evidence status store), scoped per analysis, never in a location this repository's version
control tracks.

---
*Defined: 2026-09-14, opening Milestone 10. Phase 10.10 owns the actual `WorkManager` implementation this
document describes; nothing here is implemented yet. Implementation status is maintained separately
from this public design document.*
