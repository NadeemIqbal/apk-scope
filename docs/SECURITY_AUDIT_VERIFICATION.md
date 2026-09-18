# Security Audit Verification

Verification strategy for the Security Audit capability (Milestone 10). This is a specialization of
[Verification Strategy](VERIFICATION_STRATEGY.md)'s general completion standard for this feature's
three parts: static audits, guided runtime sessions, and evidence-based reporting. Read that document
first; this one does not repeat its general rules.

## Completion standard (inherited, applied here)

The same statuses apply: **planned, source implemented, component verified, product verified, release
supported, blocked, deferred.** A rule compiling and returning a plausible-looking verdict is "source
implemented," nothing more, until the specific evidence below exists for it.

## Per-rule verification requirement

Every rule in [Security Audit Rules](SECURITY_AUDIT_RULES.md) needs, at minimum:

1. **Component verified**: a JVM unit test proving the rule fires on its trigger condition and does not
   fire on every plausible near-miss (the same discipline `core:risk`'s `CombinationRulesTest` already
   applies — "fires when both sides present, does not fire when only one side is"). No Android
   framework or emulator required for a static audit rule; if a proposed rule cannot be tested this way,
   it does not belong in `core:risk`'s pure-JVM `audit` package.
2. **Product verified**: the rule's finding appears, correctly, in a real Security Audit report
   generated from a real analyzed APK — not merely from a synthetic `ApkAnalysisInput` built by hand in
   a test. See "Evidence locations" below for where that report must be reproducible from.

A rule is **not** product verified merely because its unit tests pass. A rule is **not** "audit passed"
merely because the report renders in the UI — the specific finding's status must be checked against the
analyzed APK's actual manifest content.

## Guided runtime session verification (Phase 10.5, not yet built)

A guided session step is verified only when:

1. The step's instruction text accurately describes an action available in the actual target app under
   test (not a generic instruction that may not apply).
2. The resulting `ObservedBehavior` / `AndroidEvidence` is attributed to the correct session and target
   package (reusing Milestone 9's ownership-verification work — see `.planning/STATE.md`'s "Milestone 9
   — ownership verification" section; this milestone does not re-solve that problem, it depends on it
   already being solved).
3. A step that produces no observation within its window is reported as **"not exercised,"** never
   silently treated as a pass or a fail. This mirrors `docs/SUPERVISOR_CONTEXT.md` item 18 (declaration
   versus observation) applied to a guided step specifically: the user being *told* to do something is
   not evidence that they did it or that it produced the expected traffic.

## Evidence-based reporting verification (Phase 10.9, not yet built)

The Security Audit report is verified only when, for a real analyzed APK with a real (or deliberately
absent) guided session:

1. Every static rule's finding matches what a manual inspection of the same APK's manifest would show.
2. Every finding that depends on guided-session evidence states its actual status
   (`NOT_APPLICABLE`/confirmed/unconfirmed) rather than defaulting to a misleadingly clean report when no
   session was run.
3. The report is additive to, and cross-linked from, the existing Final Report (see
   `docs/SUPERVISOR_CONTEXT.md` item 14, reporting model) — not a second, disconnected report the user
   has to reconcile manually against the first.

## Autonomous execution verification (Phase 10.10, not yet built)

See [Autonomous Execution](AUTONOMOUS_EXECUTION.md) for the mechanics. Verification-specific
requirements:

1. A scheduled/unattended re-audit run must produce the same report a manually-triggered run would
   produce from the same inputs — determinism is not assumed, it is tested.
2. An interrupted run (process killed, quota exhausted, device rebooted) must resume from its last
   checkpoint without re-running already-completed rule evaluations or double-recording findings — this
   is directly testable without real scheduler delay by constructing a checkpoint file and asserting the
   resumed run's behavior.
3. The documented stop mechanism (see `AUTONOMOUS_EXECUTION.md`) must actually prevent the next
   scheduled run from starting — verified by asserting no new report is produced after stopping, not by
   reading the stop code and assuming it works.

## Verification levels for this milestone

| Level | Required evidence | Boundary of the claim | Phase |
| :--- | :--- | :--- | :--- |
| JVM unit tests | Deterministic `ApkAnalysisInput` fixtures and assertions per rule, plus catalog-level engine tests (order, counts, versioning) | Rule logic works; says nothing about UI, persistence, or a real device | 10.1 (done — 33 tests, `core/risk/src/test/kotlin/com/nadeem/apkscope/core/risk/audit/`) |
| Android instrumentation | Guided-session step attribution and evidence recording against real `ObservedBehavior`/`AndroidEvidence` types | Only the exercised guided-session path | 10.5 |
| Product / on-device | Full Security Audit report generated from a real analyzed APK, cross-checked against manual manifest inspection; first complete workflow (select/run/view/persist/reopen) | The report and workflow a real user sees, for the specific APK tested | 10.2 (first workflow), 10.9 (full reporting) |
| Autonomous execution | Checkpoint/resume and stop-mechanism tests as described above | Unattended scheduling correctness; not a claim about wall-clock scheduling latency | 10.10 |

## Evidence locations

- Unit test results: `core/risk/build/test-results/test/` (regenerated on every
  `./gradlew :core:risk:test` run — not committed; cite the run's aggregate pass/fail count in
  `.planning/STATE.md` and phase `SUMMARY.md`/`VERIFICATION-REPORT.md` files, as prior milestones did).
- Phase-level planning and verification artifacts: `.planning/phases/10.{N}-{slug}/`.
- Full-milestone reconciliation, when Milestone 10 closes: `.planning/STATE.md`, following the same
  "Final Milestone N requirement reconciliation" pattern Milestone 9 used.

## What this document does not cover

Third-party vulnerability databases, CVE correlation, and destination reputation are out of scope for
Milestone 10 (see [Security Audit](SECURITY_AUDIT.md)'s "Out of scope" section) and have no verification
strategy here.

---
*Defined: 2026-09-14, opening Milestone 10.*
