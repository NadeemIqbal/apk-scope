# APK Scope

## What This Is

An Android tool for inspecting APK declarations, running selected APKs inside an Android Managed Work Profile, observing network behavior, correlating Android OS evidence, and presenting explainable risk findings. Product scope also includes Security Audit: a versioned static audit rule catalog distinct from the risk score, guided runtime sessions that direct the user through exercising specific declared capabilities so a runtime observation can confirm or fail to confirm them, and evidence-based reporting that cites, per rule, its status and supporting evidence rather than a single aggregate number. See `docs/SECURITY_AUDIT.md`.

The repository and product are branded APK Scope. The Android namespace and application ID are `com.nadeem.apkscope`; fixture-only package IDs remain separate (`com.apksandbox.*`) where tests intentionally target those APK identities.

## Core Value

Help a user understand what an APK declares and what it actually does during an exercised session, with evidence and clear visibility limits.

## Current Priority

**2026-09-14 update**: Milestone 9 closed on this date (see `.planning/STATE.md`'s "Final Milestone 9
requirement reconciliation"). The current priority is now Milestone 10 — Security Audit, an explicitly
user-directed scope addition (static audits, guided runtime sessions, evidence-based reporting; see
`docs/SECURITY_AUDIT.md`). This does **not** supersede or begin `docs/FUTURE_CAPABILITIES.md`'s own
priority order — priority 3 (gRPC/SSE product-level verification through the real
fixture-in-Work-Profile UI) remains that list's next unstarted item and is untouched by Milestone 10.
The two tracks are independent; neither is silently dropped in favor of the other.

*(The paragraph below is superseded by the above as of 2026-09-14 — preserved as history.)*

Milestones 6, 7, and 8 closed the HTTPS POC, integrated traffic inspection (HTTP/1.1, WebSocket, HTTPS,
filters), and extended protocol support (deeper DEX analysis, HTTP/2, gRPC, SSE, QUIC feasibility)
respectively — each with genuine engine/component-level verification. A hands-on device acceptance pass
after Milestone 8 found the production VPN-routed capture path itself did not work at the time (a
`VpnService.protect()` failure on every upstream connection); that is now fixed and re-verified for the
plain HTTPS/h2 path on a physical Pixel 8. The same pass, and this reconciliation's own source
inspection, surfaced three further gaps with no prior roadmap coverage: VPN capture is not scoped to
the target application (captures the whole Work Profile), the DEX-embedded-URL correlation feature
(DEX04) is implemented but unreachable from the normal session-end flow, and the Traffic Inspector's
per-transaction detail view does not open on-device.

Per `docs/FUTURE_CAPABILITIES.md`'s stated priority order ("Close the current genuine traffic viewer,
persisted correlation, attribution, and lifecycle gaps" before any further hardening or protocol
scope), the current priority is Milestone 9: close these four gaps, narrowly, one at a time, each with
its own device-level acceptance evidence — not expand protocol or static-analysis coverage further
until they are closed.

## Current Milestone

Milestone 10 — Security Audit (opened 2026-09-14). See `ROADMAP.md` for phases 10.1–10.11 and
`REQUIREMENTS.md`'s "Milestone 10" block for exact requirement text, acceptance criteria, and
verification dependencies. Phase 10.1 (rule catalog, 10 rules) is component verified; Phase 10.2 (the
first complete user workflow: select an APK, run a static audit, view findings/coverage, open a
finding's evidence and remediation, persist, restart the app, reopen the same audit) is **product
verified on a real emulator** — see `.planning/phases/10.1-static-audit-engine/` and
`.planning/STATE.md`'s Phase 10.2 checkpoint for the actual device evidence. Phase 10.3 is next, not
started.

*(Milestone 9 — Capture Ownership, Evidence Wiring & Verification Closure — closed 2026-09-14. See
`ROADMAP.md`'s Milestone 9 block for phases 9.1–9.7 and `REQUIREMENTS.md`'s "Milestone 9" block, both
preserved as historical record.)*

## Requirements

Active acceptance requirements are in REQUIREMENTS.md (see its "Milestone 10" block for the current
priority; earlier milestone blocks, including Milestone 9's, are preserved as historical record). Read `docs/SUPERVISOR_CONTEXT.md`,
`docs/PRODUCT_VISION.md`, `docs/ARCHITECTURE_CONSTRAINTS.md`, `docs/VERIFICATION_STRATEGY.md`, and
`docs/FUTURE_CAPABILITIES.md` before planning further implementation — these superseded the earlier
`../docs/context/BASELINE.md` / `../docs/context/HTTPS_POC.md` references, which describe an earlier,
now-closed milestone. For Milestone 10 specifically, also read `docs/SECURITY_AUDIT.md`,
`docs/SECURITY_AUDIT_RULES.md`, `docs/SECURITY_AUDIT_VERIFICATION.md`, and
`docs/AUTONOMOUS_EXECUTION.md` before planning further Security Audit implementation.

## Constraints

1. Entire inspection engine runs on the Android device inside the Work Profile.
2. Reuse the existing Work Profile VPN and preserve destination policy enforcement.
3. Inspection is explicit opt in and off by default.
4. Intercept only the fixture and approved test destinations for this POC.
5. Start with HTTP/1.1 over TLS. Preserve ordinary server certificate and hostname validation.
6. Do not bypass pinning, patch APKs, require root, or add a second VPN.
7. Preserve DPM evidence, risk scoring, profile provisioning, and session cleanup semantics.
8. Do not commit, push, merge, tag, or publish under current authorization.
9. Preserve uncommitted work and the initialized GSD setup.
10. Do not interpret future roadmap items as authorization to implement them.

## Success

A separate fixture app in the Work Profile sends genuine HTTPS GET and POST requests through the existing VPN; the viewer displays captured request and response contents. Negative TLS tests reject invalid trust and server identity. Disabling and resetting inspection preserves the ordinary monitoring path.

## Key Decisions

Certificate installation and application trust are separate states. Arbitrary APK decryption is not a promised capability. Independent evidence sources remain independent. See ../docs/context/DECISIONS.md.

**2026-09-14**: Security Audit's rule catalog (`security-audit-v1`) is deliberately independent of the existing `static-v1`/`runtime-v1`/`combined-v1` risk score — a distinct pass/fail/warn catalog, not a second scoring pipeline and not a replacement for the risk score. See `docs/SECURITY_AUDIT.md`.
