# Security Audit

Product overview for the Security Audit capability (Milestone 10). This document explains what the
capability is and how its parts fit together. It is not the source of truth for private milestone
execution state; the public rule catalog and verification contract are maintained in the sibling
documents below. This document links to the three documents that own their own narrower slice:
[Security Audit Rules](SECURITY_AUDIT_RULES.md) (the rule catalog),
[Security Audit Verification](SECURITY_AUDIT_VERIFICATION.md) (the acceptance/evidence contract), and
[Autonomous Execution](AUTONOMOUS_EXECUTION.md) (unattended run mechanics).

## What this adds to the product scope

APK Scope already produces a `DeclaredCapability` / `ObservedBehavior` / `AndroidEvidence` evidence
model and a deterministic `static-v1` / `runtime-v1` / `combined-v1` risk score (see
[Risk Scoring](RISK_SCORING.md)). Security Audit is a distinct, citable reporting surface built on the
same evidence model, not a replacement for the risk score and not a second scoring pipeline. It adds
three things the risk score does not provide on its own:

1. **A named, versioned audit rule catalog** — individual pass/fail/warn checks with stable IDs,
   independent of the additive risk-score weights, so a specific check can be cited, tracked, and
   verified across releases without the score itself changing meaning.
2. **Guided runtime sessions** — a structured, on-device, step-by-step flow that walks the user through
   exercising the specific app features a static finding declares, so a runtime observation can confirm
   or fail to confirm that finding, rather than leaving every static declaration unconfirmed by default.
3. **Evidence-based reporting** — a report format that states, per rule, its status (see
   [Security Audit Verification](SECURITY_AUDIT_VERIFICATION.md) for the exact status vocabulary) and
   cites the concrete evidence backing it, rather than a single aggregate number.

## Scope

- **Static audits**: rule evaluation against `DeclaredCapability` data already collected by
  `ApkAnalyzer` (manifest, permissions, components, signing, DEX-derived findings). No new static
  extraction is introduced by this milestone unless a specific audit rule requires a field the analyzer
  does not yet expose — see the rule catalog for any such gap.
- **Guided runtime sessions**: an on-device, opt-in session that presents specific app actions to
  exercise (e.g. "open the screen that requests camera access") and records the resulting
  `ObservedBehavior` / `AndroidEvidence` against the specific rule that motivated the step. Distinct from
  the existing free-form Live Monitor, which observes whatever the user does without directing it.
- **Evidence-based reporting**: a Security Audit report view, generated from the rule catalog's
  evaluation results plus whatever guided-session evidence exists for the same analysis, additive to and
  cross-linked from the existing Final Report — not a replacement for it.
- **Autonomous execution**: optional unattended execution of a static re-audit, or of the scripted
  portions of a guided session, subject to Android's own background-execution constraints. See
  [Autonomous Execution](AUTONOMOUS_EXECUTION.md).

## Out of scope for this milestone

- Any change to the existing `static-v1` / `runtime-v1` / `combined-v1` risk score's weights or bands.
- Third-party vulnerability databases, CVE feeds, or destination reputation lookups (tracked separately
  in [Future Capabilities](FUTURE_CAPABILITIES.md) as "Destination Intelligence", priority 4).
- Any credential, API key, or network service required to run an audit. Every audit rule and every
  guided session step is evaluated from data already collected on-device; see
  [Autonomous Execution](AUTONOMOUS_EXECUTION.md)'s "Never store credentials" note.
- Automating guided-session *user interaction itself* (e.g. driving the target app's UI on the user's
  behalf). Autonomous execution automates re-evaluation and scheduling, not impersonating the user
  inside the sandboxed target app.

## Constraints this capability inherits

All constraints in [Architecture Constraints](ARCHITECTURE_CONSTRAINTS.md) apply unchanged: on-device
execution inside the Work Profile, no root, opt-in, no bypass of TLS validation or pinning, preserved
DPM/session-cleanup semantics. Security Audit adds no new privilege requirement and no new network
destination.

## Relationship to the existing roadmap

This milestone is a new, explicitly user-directed scope addition opened 2026-09-14. It does not
supersede or begin `docs/FUTURE_CAPABILITIES.md`'s priority order — priority 3 (gRPC/SSE product-level
verification) remains the next unstarted item in that separate list and is not touched by this
milestone. The maintainer's private milestone notes track how the two work tracks relate; this public
document does not reproduce that internal execution state.

---
*Added: 2026-09-14, opening Milestone 10.*
