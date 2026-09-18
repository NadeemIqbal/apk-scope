# Roadmap

## Milestone 10 - Security Audit (opened 2026-09-14)

**Status: In progress — Phases 10.1–10.2 done (10.1 component verified, 10.2 product verified on-device:
a real select→run→view→evidence→persist→restart→reopen workflow, plus a 2026-09-14 correction pass that
replaced a destructive Room migration with a real, data-preserving one and closed 10.2's remaining
automated-test gaps for cancellation/error states and audit identity/risk-score isolation). Phase 10.3
is next, not started.** An
explicitly user-directed scope addition (see `.planning/PROJECT.md`'s Current Priority section),
independent of `docs/FUTURE_CAPABILITIES.md`'s own priority order — priority 3 there (gRPC/SSE
product-level verification) remains that list's next unstarted item and is untouched by this milestone.
See `docs/SECURITY_AUDIT.md` for the product overview and `.planning/REQUIREMENTS.md`'s "Milestone 10"
block for exact requirement text, acceptance criteria, and verification dependencies.

Eleven phases, in dependency order, per the full 22-section specification's requirement inventory (see
`.planning/REQUIREMENTS.md`'s Milestone 10 block, "full inventory pass" reconciliation): 10.1 (static
rule catalog + engine — done) → 10.2 (first complete user workflow: select/run/view/persist/reopen a
static audit — done) → 10.3 (additional static categories: secrets, network trust, code
patterns, build/dependencies) → 10.4 (audit foundation: multi-session, reselection, full persistence) →
10.5 (guided runtime sessions) → 10.6 (finish gRPC/SSE product collection) → 10.7 (runtime security
rules) → 10.8 (correlation and coverage) → 10.9 (evidence-based reporting and retesting) → 10.10
(autonomous execution — a product feature, distinct from this session's own execution-continuity
tracking) → 10.11 (verification closure, mirrors Milestone 9's own final reconciliation phase).

### Phase 10.1: Security Audit Rule Catalog & Static Audit Engine
**Goal**: A versioned, independently-testable static audit rule catalog and engine, evaluable against
the existing `ApkAnalysisInput` snapshot with no new static extraction required.
**Depends on**: Nothing (first phase of this milestone).
**Requirements**: MS10-STATIC01, MS10-STATIC02, MS10-STATIC03, MS10-STATIC04
**Success Criteria** (what must be TRUE):
  1. `DefaultSecurityAuditEngine.audit(input)` evaluates all 10 v1 rules, in stable order, for any valid
     `ApkAnalysisInput`, with outcome/severity/confidence as independent fields (not a single
     pass/fail/warn value).
  2. Every rule has a passing trigger-fires and trigger-does-not-fire unit test.
  3. The catalog's engine version (`security-audit-v1`) never collides with `core:risk`'s existing rule
     IDs or engine versions.
  4. Full project unit-test suite shows zero regressions against the pre-Phase-10.1 baseline.
**Plans**: 2 plans

Plans:
- [x] 10.1-01: Rule catalog (`AuditModels.kt`, `AuditRuleIds.kt`, `StaticAuditRules.kt`,
  `SecurityAuditEngine.kt`) plus unit tests (`StaticAuditRulesTest.kt`, `SecurityAuditEngineTest.kt`) —
  **done**, initial 8-rule PASS/WARN/FAIL-verdict catalog, 28/28 tests passing, full suite
  `tests=388 failures=0` (was `tests=360 failures=0` pre-Phase-10.1).
- [x] 10.1-02: Closed MS10-STATIC04 (`usesCleartextTraffic`/`allowBackup` added to `ApkAnalysisInput`,
  wired from real `ApplicationInfo` flags through `ApkMetadata` → `RiskInputMapper`) and corrected the
  outcome model to match the full Security Audit specification's Section 8 (outcome/severity/confidence
  as independent fields, replacing the initial PASS/WARN/FAIL draft — done same day, before any report
  was ever persisted under either version, so no versioning break). Added
  `AUDIT_CLEARTEXT_TRAFFIC_ENABLED` and `AUDIT_BACKUP_ENABLED` (10 rules total). **Done** — 33/33 audit
  tests passing, full suite `tests=393 failures=0`, zero regressions.

### Phase 10.2: Static Security Audit — First Complete User Workflow
**Goal**: A real, on-device, end-to-end workflow — select APK, run static audit, view findings/coverage,
open finding evidence/remediation, persist, restart the app, reopen the same audit — reusing existing
architecture and UI conventions, with explicit loading/cancellation/error/empty/incomplete-coverage
states.
**Depends on**: Phase 10.1 (done).
**Requirements**: MS10-UI01-05, MS10-FOUND01 (minimal fields), MS10-FOUND04, MS10-PERSIST01 (minimal)
**Success Criteria** (what must be TRUE):
  1. A user can trigger a Security Audit from an existing analysis without installing the target. ✓
  2. Findings render with outcome/severity/confidence and a coverage summary. ✓
  3. A finding's evidence and remediation are visible on open. ✓
  4. The audit survives a real app restart and reopens with identical results. ✓
  5. Cancellation, error, empty, and incomplete-coverage states are all handled explicitly, verified
     on-device — automated instrumented tests (not manual tap-through, since a real static audit
     completes too fast to interrupt by hand). ✓ (10.2-02)
**Plans**: 2 plans

Plans:
- [x] 10.2-01: Room persistence (`SecurityAuditEntity`/`SecurityAuditFindingEntity`/`SecurityAuditDao`,
  `AnalysisSessionEntity` gains `usesCleartextTraffic`/`allowBackup`, DB version 8→9),
  `SecurityAuditRepository`, `SecurityAuditViewModel` (5-phase state machine), `SecurityAuditScreen` +
  `SecurityAuditFindingDetailScreen`, navigation wiring, and a new "Security Audit" trigger row on
  Analysis Detail. **Done, product verified on `emulator-5554`** — real APK selected via the system
  document picker, audit run, findings/coverage/evidence/remediation all inspected, then a genuine
  `am force-stop` + relaunch + reopen showed identical persisted results (not a re-run). Full JVM/Robolectric
  suite `tests=394 failures=0` (was `393` pre-Phase-10.2 — 1 new test, zero regressions). No androidTest/
  instrumentation-level automated test was added this pass — verification was manual on-device tap-through,
  which is sufficient for MS10-UI01-04's stated verification level but leaves MS10-UI05's non-happy-path
  states without automated coverage (tracked, not silently dropped).
- [x] 10.2-02 (correction pass, 2026-09-14): Fixed the destructive DB v8→9 migration that would have
  silently erased all real analysis history — `MIGRATION_8_9` (`core:database/.../Migrations.kt`),
  SQL copied verbatim from the Room schema-export diff, wired via `.addMigrations()`, proven with
  `SandboxDatabaseMigrationTest` (2 instrumented tests, real `MigrationTestHelper` against a seeded v8
  database). Made `SecurityAuditRepository` `open` as a minimal testability seam and added
  `SecurityAuditViewModelInstrumentedTest` (3 tests: deterministic cancel-mid-run via a delayed fake
  repository, plus both error paths) closing MS10-UI05's RUNNING/CANCELLED/ERROR gap. Added
  `SecurityAuditRepositoryInstrumentedTest` (3 tests: risk-score/level byte-for-byte unchanged after
  audit, audit bound exclusively to its own `analysisId`, re-run replaces rather than duplicates).
  **Done, product verified** — all 8 new instrumented tests passed on `emulator-5554`; full JVM suite
  unchanged at `tests=394 failures=0` (zero regressions). Nothing committed, pushed, or merged; `main`
  untouched.

### Phase 10.3: Additional Static Audit Categories
**Goal**: Secret-candidate scanning, network trust indicators, bounded code-pattern detection, and
build-protection/dependency checks — Sections 9(b)-9(e) of the specification — plus the standards
dossier (MS10-STD01) applied to every new rule as it ships, and the privacy/coverage bounds these
categories actually need (unlike Phase 10.1's manifest-flag rules, which have no unbounded-read surface).
**Depends on**: Phase 10.2 (workflow exists to surface these findings in).
**Requirements**: MS10-SECRET01-03, MS10-NET01, MS10-CODE01, MS10-BUILD01-02, MS10-COV01, MS10-STD01
(new-category application), MS10-PRIV02-03
**Success Criteria** (what must be TRUE):
  1. Each new category has passing fires/does-not-fire tests against fixture content.
  2. Secret candidates are masked in every output surface, never submitted externally.
  3. Network trust findings distinguish configuration presence from implementation from enforcement.
  4. Every new rule has the full MS10-STD01 dossier before being marked done.
**Plans**: 8 done (10.3-01..08); MS10-COV01 (coverage reporting as its own dedicated pass) remains

Plans:
- [~] 10.3-01: Started 2026-09-14 — closed the `ApkMetadata.networkSecurityConfigPresent`
  hardcoded-`null` gap that was the named prerequisite blocking MS10-NET01 (`ApkAnalyzer.kt` now reads
  the manifest's already-parsed `BinaryXmlParser.ManifestConfig.networkSecurityConfig`, previously
  extracted but never wired through). Verified with a new `core:staticanalysis` androidTest
  (`ApkAnalyzerNetworkSecurityConfigTest`, first androidTest wiring this module has needed) against two
  real pre-existing fixtures — `fixture-debug.apk` (declares the attribute) and `riskfixture-debug.apk`
  (does not) — 2/2 passed on `emulator-5554`. Also corrected two stale doc comments in the same files
  discovered while verifying current behavior (`ApkAnalyzer`'s class doc claimed no manifest binary-XML
  parsing occurred and that `usesCleartextTraffic`/intent-filters were unimplemented — both were already
  false). Full JVM suite unchanged at `tests=394 failures=0`. **MS10-NET01 itself is not done** — only
  its named data-gap prerequisite is; certificate-pin declarations and trust-manager/hostname-verifier
  code-reference detection (the requirement's actual scope) are not yet implemented, and no secret
  scanning, code-pattern, or build/dependency work has started.
- [~] 10.3-02: Started same day, immediately after 10.3-01 — first trust-manager code-reference rule.
  Added `ApiCategory.NETWORK_TRUST` to the existing `DexApiScanner` (one new `ApiRule`, no new scanning
  infrastructure) detecting `SSLContext.init` calls with an explicit `TrustManager` array. Verified
  against `fixture-debug.apk`'s real, pre-existing custom-`X509TrustManager`/`SSLContext.init`
  "Pinning Simulation" code (fires) and `riskfixture-debug.apk`, which has no TLS code (does not fire) —
  2 new JVM tests in `DexAnalysisTest.kt`, both passed. Full suite `tests=396 failures=0`.
  **Correction, same day, review pass**: the rule's wording overclaimed what a bare method-reference
  match can establish (see MS10-NET01's own entry in REQUIREMENTS.md for the full correction). Fixed
  the explanation text, added `NetworkTrustFixtures.kt` with a real benign-default-init/unsafe-trust-all
  pair proving the rule treats both identically, and added hostname-verifier and OkHttp
  `CertificatePinner` rules + fixtures — all three of MS10-NET01's named signal types now have a first
  code-reference rule. 4 total NETWORK_TRUST tests passing. Full suite `tests=400 failures=0`.
  Still not started: real `network_security_config.xml` resource content parsing, and wiring any of
  this into a persisted Security Audit finding.
- [~] 10.3-03: Migration-boundary review (item 4 of the correction-pass instruction) — confirmed via
  commit history that no DB schema version before 8 has ever run on the one real device with retained
  data (the physical Pixel 8), so `fallbackToDestructiveMigration` for versions <8 is not currently a
  real risk (documented in `SandboxDatabaseProvider`'s doc comment, not silently assumed). Found and
  fixed a genuine data-accuracy gap in `MIGRATION_8_9` instead: a row migrated through 8→9 defaults
  `usesCleartextTraffic`/`allowBackup` without any way to mark them as defaulted, so
  `StaticAuditRules` could read a defaulted `false` as a confirmed `CHECK_PASSED`. Added
  `MIGRATION_9_10` (`AnalysisSessionEntity.staticSecurityFieldsKnown`) and gated both affected rules
  on it (`AuditOutcome.NOT_TESTED` when unknown). 5 new JVM tests + 2 new instrumented migration tests,
  all passed. Full suite `tests=400 failures=0`.
- [x] 10.3-04: Real `network_security_config.xml` resource content parsing (items 2+3) — a real,
  bounded `resources.arsc` parser (`ResourceTableParser.kt`) resolving manifest resource-id references
  to their zip-entry paths, and a real binary-XML content parser
  (`NetworkSecurityConfigParser.kt`) for `<base-config>`/`<domain-config>`/`<debug-overrides>`/
  `<pin-set>`/`<pin>`. Verified against `fixture-debug.apk`'s real pre-existing config plus a second
  real fixture resource added for `<domain-config>`/`<pin-set>` coverage — 8 new JVM tests, 2 real
  bugs found and fixed against real bytecode. Wired end-to-end: `ApkMetadata.networkSecurityConfig` →
  `StaticAnalysisResultStore` (existing cache, no new persistence layer) → `ApkAnalysisInput`
  (flattened across the core:model/core:staticanalysis boundary) → new
  `AUDIT_NETWORK_SECURITY_CONFIG_REVIEW` rule → persisted `SecurityAuditFindingEntity`, proven with a
  new end-to-end instrumented test running the real analyze→persist→audit→read-back pipeline against
  `fixture-debug.apk` (`NEEDS_REVIEW`, matching its real cleartext-permitted base-config). UI-screen
  rendering not freshly re-verified this pass — inferred from unchanged, already-verified generic
  rendering code, stated explicitly rather than implied. Full suite `tests=413 failures=0`.
  **MS10-NET01 still open**: no rule cross-references code-level pin declarations against the XML
  config's own pin-set yet; each signal type is reported independently.
- [x] 10.3-05: Secret-candidate scanning (MS10-SECRET01-03, item 5) — `SecretCandidateScanner.kt`, 5
  format-based categories (private-key PEM, AWS/Google keys, Slack token, JWT), reusing
  `DexUrlExtractor`'s DEX-traversal pattern. Masking enforced structurally (`SecretFinding` has no
  raw-value field at all, not just a "remember to mask" convention) and a network-call-forbidding
  check that inspects the scanner's own compiled bytecode constant pool for any networking class
  reference — an automated, standing check, not a one-time review. Verified against 5 real
  credential-shaped fixture constants and 3 public-identifier negatives in `riskfixture`'s new
  `SecretCandidateFixtures.kt` (all well-known non-functional examples, documented as such) — 8 new
  JVM tests, 1 real bug caught (a fixture value 3 characters short of the Google-key format's exact
  length). Full suite `tests=421 failures=0`. **Deliberately not done this pass, stated explicitly**:
  resources.arsc/asset-file scanning (DEX only so far), and no Security Audit rule/persisted
  finding/UI wiring yet (unlike the network-config work above) — MS10-SECRET02's UI/export masking
  acceptance criterion has no rendered surface to check against yet as a result.
  **Not started**: MS10-CODE01 (code patterns) and MS10-BUILD01-02 (build/dependency checks) — no
  work done on either this pass.
- [x] 10.3-06: MS10-CODE01 (code patterns) — `CRYPTOGRAPHY`/`WEBVIEW` reference categories added to
  the existing `DexApiScanner` (one rule targeted the wrong class initially — `WebView` instead of
  `WebSettings` for `setJavaScriptEnabled` — caught via `javap` against the real `android-35` platform
  stubs before shipping it, not assumed correct). New `CodePatternAnalyzer.kt` for two structural
  cross-referencing checks: a weak-algorithm-string co-occurrence HEURISTIC and a WebView
  SSL-error-bypass CONFIRMED check (real `superclass`/method-name/call-site facts, all three
  independently verified from bytecode). Every finding explicitly states "REFERENCE tier only" in its
  own text, per this requirement's reference/reachable/executed framing — this scanner has neither
  reachability analysis nor runtime observation. New real fixtures in `fixture`
  (`CodePatternFixtures.kt` + three WebViewClient subclasses, including a negative proving the check is
  a real class-hierarchy conjunction, not a name-only match). 19 new JVM tests, all passed.
- [x] 10.3-07: MS10-BUILD01 (obfuscation indicators) — `BuildObfuscationAnalyzer.kt`, a naming-pattern
  heuristic (1-2-letter simple-name ratio) over a denylist-filtered class set (excludes
  `kotlin.`/`androidx.`/`dalvik.`/etc. — an unfiltered first pass pulled in 1500+ unrelated
  stdlib/platform classes, caught by the first test run and fixed with a real denylist, not assumed
  away). Deliberately a denylist of known-non-app-code rather than an allowlist of the app's own
  package, since real full obfuscation commonly randomizes package names too — an allowlist would be
  blind to exactly that case. Optional real ProGuard/R8 `mapping.txt` parsing (plain text only, never
  executed) upgrades specific renamed-class claims from heuristic to confirmed. New dedicated fixture
  module `obfuscatedfixture` (10 real, hand-authored 1-letter-named classes — avoided minifying the
  existing `fixture`/`riskfixture` modules, which many other tests depend on for stable class names).
  Every assessment (obfuscated or not) carries the same "does not prove correct configuration"
  disclaimer verbatim. 7 new JVM tests, all passed.
- [x] 10.3-08: MS10-BUILD02 (SDK vulnerability matching) — `SdkVulnerabilityAdvisoryCatalog.kt` (one
  real, live-verified entry: CVE-2016-2402, OkHttp 2.x<2.7.4/3.x<3.1.2, source nvd.nist.gov) +
  `SdkVulnerabilityMatcher.kt`. Confirmed empirically before implementing: `SdkSignatureCatalog` never
  determines SDK versions, and this project's own real fixture APK's `META-INF/` does not retain
  AndroidX-style `.version` marker files (AGP strips them) — the one real, reliably-extractable version
  signal found is OkHttp's own internal `"okhttp/X.Y.Z"` string constant, confirmed present in
  `fixture-debug.apk`'s real compiled DEX. Real end-to-end case: fixture's genuine OkHttp 4.12.0 is
  matched, version-confirmed, and correctly reported outside CVE-2016-2402's range, citing the source —
  against the real dependency, not a stand-in. Vulnerable/inconclusive cases demonstrated via the
  matcher's pure function with hand-built version-string inputs (never a real vulnerable dependency
  bundled anywhere). 6 new JVM tests, all passed.
  Full suite after 10.3-06/07/08: `tests=443 failures=0` (up from 421, zero regressions).
- [x] 10.3-09: Bounded accuracy hardening review of 10.3-06/07/08 (no scope broadening). **CODE01**:
  the WebView SSL-bypass finding's text conflated the two confirmed structural facts (overrides
  `onReceivedSslError`, calls `SslErrorHandler.proceed`) with an unproven runtime consequence
  ("instructs the WebView to continue loading despite a TLS certificate error", stated as established).
  `CodePatternFinding` now carries separate `confirmedFacts`/`interpretation` fields, structurally
  preventing that conflation rather than relying on prose discipline; the weak-algorithm heuristic
  finding restructured the same way for consistency. **BUILD01**: reviewed for product-boundary
  compliance — already correct (mapping file is a caller-supplied optional parameter, never derived
  from the APK), added an explicit doc section stating this plainly; no behavior change. **BUILD02**:
  four real gaps fixed — SDK identification now requires `SdkConfidence.HIGH` (was unfiltered);
  conflicting distinct version markers now report `NOT_TESTED` naming both values (was: silently
  picked whichever came first in iteration order); a numeric component too large for `Int` no longer
  crashes (`toIntOrNull()` guard); a prerelease/qualifier suffix or 4-component version is now
  rejected rather than silently truncated to its numeric prefix. 19 new JVM tests total
  (`CodePatternAnalyzerTest` +4, `SdkVulnerabilityMatcherTest` +15), all passed. Full suite:
  `tests=462 failures=0` (up from 443, zero regressions).
- [x] 10.3-10: MS10-COV01 (coverage reporting) — the existing `StaticAnalysisCoverage` model already
  carries all required fields for extracting provenance and supporting locations: `dexFilesInspected`
  (which DEX files examined), `entriesSkipped` (entries not processed), `parsingErrors` (scanning
  errors), `limitsReached` (boolean flag when scan-bound exceeded). The existing `CodePatternAnalyzer`
  already implements limit detection correctly: when findings reach MAX_FINDINGS (500), it sets
  `limitsReached = true` and stops, explicitly informing the caller of truncation rather than
  silently returning fewer findings. Four new JVM tests verify coverage reporting:
  `coverageReportsScannedDexFiles` (which DEX files), `coverageReportsScanDurationMilliseconds` (real
  scan duration), `coverageReportsLimitReachedFalseWhenFindingsAreBelowThreshold` (below-limit case),
  `coverageCanReportParsingErrors` (errors field usable). Full suite: `tests=466 failures=0` (up from
  462, zero regressions). **Milestone 10, Phase 10.3 complete**: all nine items (10.3-01..10) done,
  all nine categories implemented and verified at their stated verification levels (7 JVM-test-level
  scanner checks + coverage reporting), full accuracy hardening pass completed, zero regressions.

### Phase 10.4: Audit Foundation — Multi-Session, Reselection, Full Persistence
**Goal**: Full identity binding (hash/package/version/signing/device/catalog metadata), multi-session
contribution to one audit record, staging-deleted reselection with identity validation, and the full
persistence/recovery contract (distinct import outcomes, concurrency, interruption recovery).
**Depends on**: Phase 10.2 (minimal persistence already exists to extend), Phase 10.3 (categories that
need multi-session evidence).
**Requirements**: MS10-FOUND01-03 (full), MS10-PERSIST01-04
**Success Criteria** (what must be TRUE):
  1. Two sessions against the same APK identity contribute to one audit record, traceably.
  2. Reselecting a different APK against an existing record is rejected explicitly.
  3. 1000+ concurrent writes against the audit store lose zero updates.
  4. A crash mid-write preserves the last successfully-committed record.
**Plans**: TBD

Plans:
- [ ] 10.4-01: TBD — not yet planned.

### Phase 10.5: Guided Runtime Verification Sessions
**Goal**: An on-device, opt-in, step-by-step session (suggested + custom actions, tester start/complete/
notes marking, monitoring-health display) that directs the user through exercising specific declared
capabilities the static catalog flagged, recording the resulting evidence against the originating rule,
reusing normal install/launch/monitoring/cleanup controls.
**Depends on**: Phase 10.1 (static findings to generate steps), Phase 10.4 (multi-session foundation);
Milestone 9's MS9-CAP02 (ownership verification, done) for evidence attribution.
**Requirements**: MS10-SESS01-07
**Success Criteria** (what must be TRUE):
  1. A guided session presents one step per flagged static finding plus any tester-added custom actions.
  2. Tester-marked completion is tracked distinctly from technical evidence confirmation.
  3. Guided-session evidence is attributed via the existing `MATCHED`/`MISMATCHED`/`UNKNOWN`
     classification.
  4. A step with no observation inside its window reports an explicit "not exercised" outcome.
  5. Monitoring health (degraded/interrupted) is surfaced, never silently hidden.
**Plans**: TBD

Plans:
- [ ] 10.5-01: TBD — not yet planned.

### Phase 10.6: Runtime Collection Completion
**Goal**: Finish gRPC and SSE product-level integration through the real fixture-in-Work-Profile UI (not
merely engine/component tests), and preserve capture identity for guided-session-collected evidence.
**Depends on**: Phase 10.5 (guided sessions to collect evidence during).
**Requirements**: MS10-COLL01-03
**Success Criteria** (what must be TRUE):
  1. A real gRPC exchange through the deployed fixture appears in the Traffic Inspector end-to-end.
  2. A real SSE stream through the deployed fixture appears in the Traffic Inspector end-to-end.
  3. Unrelated concurrent traffic is never misattributed to the audited target.
**Plans**: TBD

Plans:
- [ ] 10.6-01: TBD — not yet planned.

### Phase 10.7: Runtime Security Rules
**Goal**: Evidence-based runtime findings (cleartext observed, canary detection, destination-list/
background-interval violations, post-logout credential observation, inspector-vs-target TLS separation).
**Depends on**: Phase 10.6 (collection paths these rules consume).
**Requirements**: MS10-RTRULE01-05
**Success Criteria** (what must be TRUE):
  1. A real captured plaintext request with a secret candidate produces the corresponding finding.
  2. A tester-supplied canary appearing in captured traffic is detected.
  3. A rejected inspection certificate is never reported as proof of target-side pinning.
**Plans**: TBD

Plans:
- [ ] 10.7-01: TBD — not yet planned.

### Phase 10.8: Correlation and Coverage
**Goal**: Explicit static/runtime correlation with preserved provenance, deterministic merge/retention
behavior, and coverage reporting that never treats missing evidence as proof of absence.
**Depends on**: Phase 10.6, 10.7 (evidence to correlate).
**Requirements**: MS10-CORR01-03
**Success Criteria** (what must be TRUE):
  1. Exact-URL and host-only matches produce distinguishable provenance.
  2. Duplicate evidence imports produce one finding, not two.
  3. Untested rules report `NOT_TESTED`, never `CHECK_PASSED`.
**Plans**: TBD

Plans:
- [ ] 10.8-01: TBD — not yet planned.

### Phase 10.9: Evidence-Based Reporting and Retesting
**Goal**: Full finding detail (rule/severity/confidence/evidence/origin/workflow/reproduction/
remediation/OWASP-or-project-specific reference/coverage), audit summary, two-build comparison with
fixed-vs-no-longer-observed distinction, reviewer disposition without evidence deletion, and redacted
local export.
**Depends on**: Phase 10.3 (standards dossiers to render), Phase 10.8 (correlated findings to report).
**Requirements**: MS10-RPT01-08
**Success Criteria** (what must be TRUE):
  1. The Security Audit report is cross-linked with the existing Final Report in both directions.
  2. A finding's full detail renders every required field or an explicit not-applicable marker.
  3. Retest comparison distinguishes "fixed" from "no longer observed" with a stated caveat.
  4. Export contains no unmasked secret candidate.
**Plans**: TBD

Plans:
- [ ] 10.9-01: TBD — not yet planned.

### Phase 10.10: Autonomous Execution Infrastructure
**Goal**: Optional, opt-in, unattended static re-audit scheduling via `WorkManager`, with
platform-quota-aware checkpointing, resumption, and a working stop mechanism — a product feature,
distinct from this session's own execution-continuity tracking (see `docs/AUTONOMOUS_EXECUTION.md` and
`.planning/STATE.md`).
**Depends on**: Phase 10.4 (a stable, persisted audit worth re-running unattended).
**Requirements**: MS10-AUTO01-03
**Success Criteria** (what must be TRUE):
  1. A scheduled unattended re-audit produces a report identical to a manually-triggered run given the
     same stored input.
  2. An interrupted run resumes without re-evaluating or double-recording already-completed work.
  3. Disabling the feature cancels the scheduled `WorkManager` work immediately, verified by `WorkInfo`
     state.
**Plans**: TBD

Plans:
- [ ] 10.10-01: TBD — not yet planned.

### Phase 10.11: Security Audit Verification Closure
**Goal**: Criterion-by-criterion reconciliation of every Milestone 10 requirement against actual
evidence, mirroring Milestone 9's own closure discipline — no requirement marked complete without
citable evidence, no gap silently dropped.
**Depends on**: Phases 10.1–10.10.
**Requirements**: MS10-CLOSE01 (all MS10-* requirements, final reconciliation, not new scope).
**Success Criteria** (what must be TRUE):
  1. Every MS10-* requirement's status in `REQUIREMENTS.md` is supported by cited evidence (test
     results, on-device runs, or an explicit, non-blocking open note — never silently dropped).
  2. `STATE.md` records a "Final Milestone 10 requirement reconciliation" section in the same format as
     Milestone 9's.
**Plans**: TBD

Plans:
- [ ] 10.11-01: TBD — not yet planned.

---

## Milestone 9 - Capture Ownership, Evidence Wiring & Verification Closure (closed, 2026-09-14, sixth pass)

**Status: Closed.** Added 2026-09-12 by planning reconciliation against `docs/SUPERVISOR_CONTEXT.md`,
`docs/ARCHITECTURE_CONSTRAINTS.md`, `docs/VERIFICATION_STRATEGY.md`, and direct source/device inspection
performed as part of that reconciliation; closed 2026-09-14 after a full, criterion-by-criterion
reconciliation of all **15 requirements** recorded under it (13 new MS9-prefixed requirements, plus 2
pre-existing Milestone 8 requirements — DEX04, VER02 — this milestone directly corrected under their
original IDs) found none still blocking closure — see `STATE.md`'s "Final Milestone 9 requirement
reconciliation" section for the complete accounting (every requirement's acceptance criterion, evidence,
verification scope, exact remaining gap, and closure determination) and `STATE.md`'s
"Reconciliation — 2026-09-12" section for the original evidence trail.
Three items are carried forward as explicitly-open, non-blocking notes rather than silently dropped:
full Java-serialization replacement (deliberately-deferred hardening technical debt, not required work
left undone — the requirement it belongs to already has a satisfied alternative), the original
missing-URL-evidence incident's still-unconfirmed root cause (a standing investigative question, not a
requirement this milestone promised to resolve), and VER02's gRPC/SSE product-level verification (never
part of Milestone 9's own defined scope — the correctly-identified next roadmap item). See
`REQUIREMENTS.md`'s "Milestone 9" block for the exact requirement text.

Milestone 8 (below) delivered genuine, verified fixes to the HTTP/2/gRPC/SSE *engine* path and remains
historically accurate for what it verified. It is no longer the active milestone because a subsequent
hands-on device pass found the production VPN-routed capture path did not actually work at the time
Milestone 8 was marked complete (a `VpnService.protect()` failure on every upstream connection — since
fixed, evidence in `evidence/checkpoint_8_9_acceptance/`), and reconciliation surfaced three
further gaps that were never on any roadmap: VPN capture is not scoped to the target app, the
DEX-embedded-URL correlation feature is implemented but unreachable from the normal session-end flow,
and the Traffic Inspector's per-transaction detail view does not open on-device. Milestone 9 closes
these narrowly, one at a time, each independently verifiable, before any further protocol or
static-analysis scope is added (per `docs/FUTURE_CAPABILITIES.md`'s stated priority order — closing
existing gaps precedes hardening, which precedes new protocol scope).

**Reordered, 2026-09-12, after Phase 9.1's platform-limitation finding**: the original plan had Phase
9.2 as "wire URL-evidence import into normal session end" directly after Phase 9.1. Reconciliation
against that finding asked whether trustworthy traffic attribution is a prerequisite for that — it is,
and source inspection while confirming this found an even more basic version of the problem than
expected (see Phase 9.2 below: captured traffic wasn't attributed to *any* session at all, not merely
attributed without excluding other apps). The six phases below are now, in dependency order: 9.1
(root-cause investigation, done) → 9.2 (session attribution wiring, done — a newly-discovered
prerequisite, not originally planned) → 9.3 (userspace ownership verification / MS9-CAP02, the
originally-implied-by-9.1 follow-up) → 9.4 (URL evidence wiring, originally "Phase 9.2") → 9.5
(Traffic Inspector click fix, originally "Phase 9.3") → 9.6 (persistence hardening, originally "Phase
9.4"). No phase was added "because it was interesting" — each insertion point above is justified by a
concrete defect or evidentiary gap that would otherwise undermine a later phase's correctness.

### Progress

| Phase | Plans Complete | Status |
| :--- | :--- | :--- |
| Phase 9.1: VPN capture ownership — root-cause investigation | 1/1 | PLATFORM LIMITATION VERIFIED — see below |
| Phase 9.2: Session/target attribution wiring for captured traffic | 1/1 | Complete — see below |
| Phase 9.3: Userspace traffic ownership verification (MS9-CAP02) | 2/2 | Implemented, unit-tested (including a focused export-guarantee test against a mixed MATCHED/MISMATCHED/UNKNOWN batch), and verified on real on-device VPN-captured traffic. Ownership tuple correctness and target-UID cache lifetime both explicitly verified and hardened (2026-09-13). **2026-09-13, second pass**: real second-controlled-application evidence obtained (riskfixture MATCHED + organically-occurring Gboard MISMATCHED, both real traffic through the actual production VPN); the existing unit tests are now explicitly recharacterized as *filtering tests*, not real second-application verification, per instruction. The product's one-session-at-a-time VPN slot constraint is now confirmed and documented (quoting its own UI message), not a gap to close. **2026-09-13, third pass**: made the second-app exclusion reproducible and quantitative — Play Store opened on demand from the Work Profile (real uid, real `MISMATCHED`) while fixture's session stayed active; the exported evidence artifact (`forSession()`'s one real call site) held exactly 1 entry against 31 raw captures, all MISMATCHED records excluded. The concurrent-session *hang* (previously treated as pure architecture) was re-examined, root-caused to a missing timeout on the guard's own cross-profile query, and fixed with a bounded timeout + a new fail-closed error code — see MS9-CAP03. **2026-09-13, fourth pass — `2/2`, done**: the on-device automated assertion now exists at both the JVM (`UrlEvidenceExporterTest`) and instrumented (`UrlEvidenceExporterInstrumentedTest`, run on `emulator-5554`) levels, against the actual production export boundary (`UrlEvidenceExporter.export`, extracted from `SandboxWorkQueryActivity`), not `forSession()` in isolation. |
| Phase 9.4: Wire URL-evidence import into normal session end | 2/2 | Complete — on-device verified, plus a second (host-correlation/provenance) defect found and fixed. **2026-09-13, second pass**: process-restart persistence of imported evidence now genuinely verified on-device (real `am force-stop`, real relaunch, evidence intact). "Repeat same artifact"/"distinct later observation replaces earlier"/"replay older artifact doesn't regress" remain verified at the correlator unit-test level only — device-level exercise of these three specific sub-cases is blocked by a confirmed, real product constraint (staged APK cleanup on session end prevents reusing an analysisId for a second session) — see below. **2026-09-13, third pass**: "repeat the same artifact import" is now ALSO verified device-level, without needing a second session — traced `SandboxCleanupScreen`'s own code to find it already invokes the real import path twice within one normal session-end (real logcat: correlate once, then a genuine idempotent no-op ~1m18s later). Only "distinct later observation"/"replay doesn't regress" remain correlator-unit-only, for the traced structural reason re-confirmed this pass (no UI path exists to a second same-analysisId session). **2026-09-13, fourth pass**: repeated import now also verified by content identity through a real Android-integration test (`RepeatedUrlEvidenceImportInstrumentedTest`, 6 tests), and "distinct later observation"/"replay doesn't regress"/"equal-timestamp tie-break"/"static provenance & host-correlation precision" ALL now have a real Android-persistence-integration counterpart alongside their existing pure-logic coverage — nothing from the original acceptance list remains untested at only one level without that being stated explicitly. The cleanup screen's double import was traced to its actual cause: two distinct, bounded, intentional `LaunchedEffect` invocations (keyed on `state.isComplete`), not duplicate/unbounded execution. |
| Phase 9.5: Traffic Inspector detail-view click fix | 2/2 | **Done** — investigated and verified on both the authorized emulator and, per explicit user authorization, the physical Pixel 8 (`39271FDJH008HQ`). Two independent row taps (SSE, WS) opened full detail views correctly. No code change needed — nothing reproducibly broken was found on either device. **2026-09-13, second pass, correction**: those rows were seeded fixtures, valid for the click mechanism but not capture acceptance evidence; a separate, genuinely captured (non-seeded) transaction was opened via normal navigation on the emulator this pass and confirmed real — see MS9-UI02 in `REQUIREMENTS.md`. **2026-09-13, fourth pass, correction**: that "normal navigation" was, in fact, the Work Live Monitor screen's "STATIC TEST BUTTON" — an explicitly-labeled temporary diagnostic, the *only* currently-functioning path to Traffic Inspector from that screen (its real filter chips/feed rows are not wired to it, and are the very thing this button was added to work around). The transaction evidence itself stands (real, not seeded); what's corrected is the "normal navigation" characterization — no separate working entry point exists yet to independently verify. |
| Phase 9.6: Static analysis persistence hardening | 1/2 | Path + observable-failure task done; integrity-gate task done as a partial mitigation (header check before deserialization, not full replacement of Java serialization) — see below. **2026-09-13, second pass**: wording corrected (header = format validation, not an "integrity gate"); a genuine CRC32 payload-integrity check now added and proven to catch corruption the header check alone misses; a real concurrent-import lost-update race found and closed via per-key locking, proven with a real multi-threaded test; a related cache-consistency bug (cache updated before write success was confirmed) found and fixed. Still `1/2`: full replacement of Java serialization was not attempted and remains the stated open gap. **2026-09-13, third pass**: the lock's scope corrected to state plainly it is in-process only (never cross-process protection), with the actual writer set verified to make that sufficient; CRC32's non-cryptographic wording re-confirmed already precise; 4 new real Android-instrumented tests (`StaticAnalysisPersistenceInstrumentedTest`, run on `emulator-5554`, all passing) now cover the envelope format, replacement, corruption-recovery, and write-failure/cache-preservation behavior against real device storage, not only the JVM/test-filesystem level. Still `1/2` — full Java-serialization replacement remains the one named, unchanged open gap. |
| Phase 9.7: Pixel 8 acceptance defects, fifth/sixth pass (2026-09-14) | 4/4 | Item 1 (permission remediation) **fixed and verified on-device** (emulator; the block itself was not encountered on the physical device this pass, reported precisely, not as "already granted"). Items 2/3 (URL-evidence diagnostics/outcome-state infrastructure, cleanup ordering) **reproduced successfully end-to-end on the physical Pixel 8** — all nine diagnostic stages traced, real correlation, real persisted `IMPORTED` status — though the *original* missing-evidence incident's root cause remains formally unconfirmed. Item 4 (real Pixel 8 re-verification) **done**. **Sixth pass**: two reporting mischaracterizations corrected (concurrent suppression vs. completed-artifact replay; permission-listing vs. live-check); the import-guard lifecycle directly verified with a new 5-test focused suite (no defect found); `RepeatedUrlEvidenceImportInstrumentedTest` inspected and cited (not rerun) for the completed-artifact-replay guarantee. See `REQUIREMENTS.md`'s MS9-URL03/MS9-URL04/MS9-UI02 entries and `STATE.md`'s "Final Milestone 9 requirement reconciliation" for the closure determination. |

### Phase 9.1: VPN capture ownership — root-cause investigation (reframed 2026-09-12)

**This phase was attempted as originally specified and the result disproved the original approach —
it is not "not started."** See `STATE.md`'s "Phase 9.1 attempt — 2026-09-12" section for the full
evidence trail. Summary: `VpnService.Builder.addAllowedApplication(targetPackage)` was implemented
(with a two-stage rescope-after-install design to correctly handle the Prepare-Sequence install
ordering), built, and tested twice on a physical Pixel 8. Both times, `dumpsys connectivity vpn`
showed the resulting tunnel's `Uids` as **empty** (`<{}>`) — not scoped to the target, not left at the
whole-profile range — with no exception ever logged and `establish()` reporting success. Every fixture
HTTPS request then failed with `UnknownHostException`, reproduced across two distinct hosts and
confirmed non-transient. The implementation was reverted (not shipped disabled-by-default, not
weakened) to restore the prior, already-verified-working whole-Work-Profile-capture tunnel; a
regression check (`HttpsInspectionIntegrationTest`, 23/26 passing, all real-VPN/TLS/ALPN capture tests
among them) confirms the revert did not regress the existing capture path at the engine/instrumentation
level. A full manual fixture-in-Work-Profile UI regression run — the same verification level Checkpoint
8.9's acceptance evidence used — remains **WAITING_FOR_DEVICE_INTERACTION** (blocked on both the
physical device and the emulator by unrelated cross-profile install-confirmation UI flakiness, not by
this revert).

**Investigation completed, 2026-09-12 (same day, later pass) — root cause identified.** See
`STATE.md`'s "Phase 9.1 investigation — 2026-09-12" section for the full experiment trail. Summary:

1. **Hypothesis 2 (wrong-user package/UID resolution) — REJECTED.** A pure logging diagnostic (no
   behavior change) confirmed `SandboxVpnService` correctly runs as the Work Profile's own process
   (`UserHandle{13}`) and correctly resolves the target package's UID (`1310289`, same user) once
   installed — an honest `NameNotFoundException` before install, a correct resolution after. Identity
   resolution was never the problem.
2. **Hypotheses 1 vs. 3, distinguished by a controlled experiment — hypothesis 1 CONFIRMED, hypothesis
   3 REJECTED.** With DPM always-on-VPN *lockdown* temporarily disabled (always-on itself left on),
   the identical `addAllowedApplication("com.apksandbox.fixture")` call — same profile, same admin,
   same target — produced a real, non-empty, correctly-scoped `Uids` range
   (`{1310289-1310289, 1320289-1320289}`), not the whole-profile range and not empty. Restoring
   lockdown, then re-running a normal session, confirmed both that lockdown was durably restored and
   that the production (unscoped) capture path still works (`HTTPS_JSONPLACEHOLDER_SUCCESS [200]`
   with a real decrypted body, through the actual fixture-in-Work-Profile UI flow — this also
   satisfies the manual UI regression run previously marked WAITING_FOR_DEVICE_INTERACTION).

**Conclusion — PLATFORM LIMITATION VERIFIED, for this specific mechanism**:
`VpnService.Builder.addAllowedApplication()` is incompatible with
`DevicePolicyManager.setAlwaysOnVpnPackage(lockdownEnabled = true)` on this Android version — combining
them silently discards the Builder's allowlist (an empty enforced UID set), rather than throwing or
combining the two restrictions. This is not a claim that per-application VPN scoping is impossible on
Android generally (it works correctly the moment lockdown is off); it is specific to this exact API
combination, which this product's `ARCHITECTURE_CONSTRAINTS.md`-mandated lockdown guarantee currently
requires being on at all times.

**Recommended next investigation (a new phase, not started this pass)**: a *userspace* attribution
approach — using `ConnectivityManager.getConnectionOwnerUid()` inside `ForwardingEngine` to identify
which app owns each flow the TUN already receives, rather than asking the OS to restrict the tunnel's
UID scope via `addAllowedApplication` — would sidestep this specific lockdown incompatibility entirely,
since it needs no Builder-level allowlist at all. This has **not been attempted or verified** and must
go through its own plan-first, verify-before-implementing cycle like any other phase; it is recorded
here only as the most promising avenue, not as a decision to implement it.

Tasks (2) — investigation, completed:
1. ~~Run the distinguishing tests recorded in `STATE.md` for each hypothesis and record which the
   evidence supports.~~ Done — hypothesis 1 confirmed, hypotheses 2 and 3 rejected.
2. ~~Write an architecture-decision note stating the next step.~~ Done — documented above as
   PLATFORM LIMITATION VERIFIED for `addAllowedApplication` + lockdown, with `getConnectionOwnerUid`
   recorded as the recommended next avenue for a future, separately-planned phase.

Exit / verification: **met.** Root cause identified and evidence-backed (hypothesis 1), alternative
hypotheses ruled out with equally direct evidence, production capture path re-verified intact after
the experiment, and a truthful next-step recommendation recorded rather than a fake approximation
shipped.

### Phase 9.2: Session/target attribution wiring for captured traffic (reordered ahead of URL evidence wiring, 2026-09-12)

**Added by reconciliation, not part of the original Milestone 9 plan.** Per instruction to reassess
whether Phase 9.1's platform-limitation finding changes the dependency order of the rest of Milestone
9: it does. Direct source inspection (prompted by re-examining what "trustworthy traffic attribution"
actually requires before wiring URL evidence into the primary UI flow) found that **every one of
`HttpsInspectionEngine`'s own `TrafficRecord` construction sites, and three of six in
`Http2RelayHandler` — including `finalizeStream`, the one that produces the *final*, replace-in-place
record for every HTTP/2/gRPC/SSE stream — omitted `sessionId`/`targetPackage` entirely.** Every real
captured HTTP/1.1 transaction (the plain-GET case exercised throughout Phase 9.1's own regression
checks) had `sessionId = null`, meaning `TrafficInspectionStore.forSession(sessionId, targetPackage)`
— what `SandboxWorkQueryActivity.exportUrlEvidence()` and any session-scoped Live Monitor query both
depend on — silently returned nothing for the most common capture case, **regardless of whether a
session was genuinely active.** This is a harder, more basic blocker on the originally-planned Phase
9.2 (URL evidence wiring) than the cross-app contamination risk that motivated re-examining the
ordering in the first place: wiring `importUrlEvidence()` into the normal session end (the original
plan) would have exported from a store that, for HTTP/1.1 traffic, was never attributed to any session
at all. This phase fixes that specific defect; Phase 9.3 (renumbered from this reconciliation) is the
originally-intended cross-app-contamination attribution work, and now correctly sits *after* this one,
since it makes no sense to verify "excludes non-target traffic" against a store that doesn't attribute
traffic to sessions in the first place.

A second, deeper layer of the same defect was found while fixing the first: even after threading
`sessionId`/`targetPackage` into every `HttpsInspectionEngine`/`Http2RelayHandler` construction site,
a new on-device test still failed. Root cause: `HttpsInspectionStore.record()` (the legacy,
session-unaware `HttpsTransaction` model, kept for backward compatibility) contains a "mirror to
unified `TrafficInspectionStore`" block that reconstructs a *fresh* `TrafficRecord` with no
`sessionId`/`targetPackage` (because `HttpsTransaction` itself carries neither field) and writes it
under the *same id* as the correctly-attributed record every real call site writes first — silently
clobbering the attribution on every single transaction, every time, since the mirror always runs
immediately after. Fixed by having the mirror look up the existing record's attribution and carry it
forward instead of discarding it.

Tasks (2):
1. Thread `sessionId`/`targetPackage` into `HttpsInspectionEngine`'s four `TrafficRecord` construction
   sites and `Http2RelayHandler`'s three previously-omitting `toTrafficRecord(...)` call sites
   (including `finalizeStream`), and fix `HttpsInspectionStore.record()`'s mirror block to preserve
   whatever attribution the record it's about to replace already had, rather than reconstructing one
   from scratch.
2. On-device test (`testTrafficRecordCarriesSessionAttributionOnDevice`, a real TLS+ALPN HTTP/1.1 GET
   against httpbin.org, engine constructed with a real `sessionId`/`targetPackage` — every other test
   in the suite uses the two-arg constructor, which is exactly how this defect went unnoticed):
   asserts `TrafficInspectionStore.forSession(sessionId, targetPackage)` actually contains the real
   transaction, and that it does *not* leak into a query for a different, unrelated sessionId.

Exit / verification: **met.** `testTrafficRecordCarriesSessionAttributionOnDevice` passes on-device
(`emulator-5554`, the only device attached — physical Pixel 8 not tested). **Correction, 2026-09-12,
later pass**: this line originally read "26/27" without the actual arithmetic — the real XML at the
time read `tests="27" failures="3"`, i.e. 24 passed, not 26. See `STATE.md`'s "Milestone 9
reconciliation and Phase 9.2" correction for the full breakdown and raw-instrumentation-log evidence:
one of the three was a genuinely `@Ignore`d test misrendered as an XML failure by AGP's report merge
(confirmed via raw `TestRunner` logcat, not assumed); the other two were real failures on a missing
`fixture-debug.apk` precondition file, since fixed by pushing it to `/data/local/tmp/` — after which
the suite genuinely reads `tests="27" failures="1"` (26 passed, 1 ignored, 0 real failures), including
the legacy `testEngineInterceptionWithRealPublicHttpsGetOnDevice` (backward compatibility preserved).
Full JVM unit suite (`./gradlew test --rerun`): `tests=314 failures=0` — genuinely clean.

### Phase 9.3: Userspace traffic ownership verification (MS9-CAP02) (reordered, was implied by Phase 9.1's recommendation)

The originally-planned cross-app-contamination requirement (MS9-CAP02): even with Phase 9.2's fix,
`TrafficInspectionStore` attributes every record to *whichever session is currently active*, not to
the real owning UID of the connection — a second Work Profile app (or APK Scope's own Work-side
background traffic, already observed being captured in prior acceptance evidence) running
concurrently would still be misattributed to the active session. Per Phase 9.1's finding,
`VpnService.Builder.addAllowedApplication()` cannot be used to prevent this at the OS/tunnel level
(platform limitation, verified) — this phase's recommended mechanism is userspace attribution via
`ConnectivityManager.getConnectionOwnerUid()`, called from `TcpProxy` at the point a flow's real
4-tuple (tun-rewritten local address + the original app's own local port, both already tracked in
`TcpProxy.Tcb.key`) is known, threaded to `HttpsInspectionEngine`/`Http2RelayHandler` via the existing
preamble protocol (already carries remote IP/port/SNI over the loopback handoff; extend it with a
4-byte owner UID field), and compared against the session's resolved target UID (the same
`packageManager.getPackageUid()` call already proven correct in Phase 9.1's investigation).

Tasks (2):
1. Extend the `TcpProxy` → `HttpsInspectionEngine` preamble with the real owning UID (via
   `ConnectivityManager.getConnectionOwnerUid`, using the tun's own address as the "local" side per
   that API's documented VPN-app use case), and add an attribution field to `TrafficRecord`
   (`MATCHED`/`MISMATCHED`/`UNKNOWN` — fail-closed: `UNKNOWN` must never be treated as matched).
2. Update `TrafficInspectionStore.forSession()` to exclude non-`MATCHED` records (the evidence-critical
   read path used by URL export and reports), and verify on-device with a genuine negative case
   (traffic from a second installed Work Profile app, or APK Scope's own observed background
   connections) confirmed excluded via an assertion, not a screenshot alone — matching MS9-CAP02's
   original wording.

Exit / verification: unit test asserting `forSession` excludes mismatched/unknown-attribution records;
on-device negative test with real non-target traffic confirmed excluded by assertion.

**Status, 2026-09-12 (later pass): task 1 done, task 2 substantially done.** Both tasks implemented
exactly as scoped above (preamble extended at a fixed byte position, not appended — see `STATE.md`'s
"Milestone 9 — ownership verification" section for why an appended/defensive-read design was tried
and reverted; `forSession()` now requires `ownershipStatus == MATCHED`). Verified:
- Unit test `testForSessionExcludesUnknownOwnership` (JVM-level) asserts the exclusion directly.
- **On-device, against the real production VPN/TcpProxy/HttpsInspectionEngine pipeline** (not
  `TestTunSink`, not direct record insertion): a fresh sandbox session on `emulator-5554` produced
  genuine `MATCHED` records (fixture's own HTTPS GET/POST and a full WSS session, `observedOwnerUid`
  matching the fixture's real resolved UID), a genuine `MISMATCHED` record (three `dl.google.com`
  connections from a different real UID in the same Work Profile — Play Store/installer background
  activity, not manufactured), and a genuine `UNKNOWN` record (the same host, earlier in the session,
  before the target package's UID was resolvable). Raw `TrafficEngine` logcat evidence saved this
  pass; the Traffic Inspector UI was cross-checked and shows all of the above side by side.
- **Remaining gap**: the on-device confirmation above was a manual logcat/UI observation, not an
  automated instrumented assertion that `TrafficInspectionStore.forSession()` itself excludes the
  `MISMATCHED` record for a live, real-pipeline connection (the JVM unit test asserts this with
  synthetic `TrafficRecord`s, not a real captured one). Exit criterion's literal wording
  ("confirmed excluded by assertion") is not yet fully met at the on-device layer — this is a real,
  narrow remaining task (an androidTest asserting `forSession()` on real captured MISMATCHED traffic),
  not a re-opening of the feature's correctness, which the manual evidence above already demonstrates.

### Phase 9.4: Wire URL-evidence import into normal session end

`SandboxSessionCoordinator.importUrlEvidence()` / `correlateUrlEvidenceWithAnalysis()` exist and work
when called, but are only ever called from the orphan-session-recovery path — never from
`SandboxCleanupViewModel.importAndReconcile()`, which is what the normal "End Sandbox Session" button
actually drives. DEX04's production wiring has therefore never been exercised by any acceptance run to
date, including the two fresh Pixel 8 runs already on record. Now unblocked at the "does sessionId
exist at all" layer by Phase 9.2; full trustworthiness (excluding cross-app contamination) additionally
depends on Phase 9.3.

Tasks (2):
1. Add `coordinator.importUrlEvidence(activity, sessionId)` to `importAndReconcile()`, alongside the
   existing `importEvidence`/`importRuntimeArtifact`/`importAndroidEvidence` calls there.
2. On-device: analyze a fixture APK containing a real embedded URL literal that the fixture also
   actually requests at runtime (e.g. a hardcoded `jsonplaceholder.typicode.com/posts/1` string in the
   fixture's own source, not a contrived one), run the normal session end-to-end, end the session
   normally (not via orphan recovery), reopen the same analysis, and confirm that URL's entry in
   Embedded URLs shows `RUNTIME_OBSERVED` provenance with a real transaction id, method, and status —
   not merely an updated risk score.

Exit / verification: a screenshot of the Embedded URLs detail screen showing the `RUNTIME_OBSERVED`
badge and its transaction reference for a URL the fixture genuinely requested, taken after a normal
(non-orphan) session end and analysis reopen.

**Status, 2026-09-12 (third pass): both tasks done, on-device verified, plus a second defect found and
fixed.** Task 1: added the missing `coordinator.importUrlEvidence(activity, sessionId)` call to
`importAndReconcile()`. While verifying it, found `correlateUrlEvidenceWithAnalysis()` conflated exact
URL matches with mere host-level matches — both were written as `RUNTIME_OBSERVED` `runtimeEvidence`,
clobbering the candidate's static-extraction `provenance` either way, and never populating the
already-modeled, already-UI-supported `hostCorrelation` field. Fixed: exact matches only get
`runtimeEvidence`/`RUNTIME_OBSERVED`; host-only matches get `hostCorrelation` instead, provenance
untouched. Verified on a rebuilt, reinstalled `emulator-5554` app, real VPN-routed fixture traffic
(`GET jsonplaceholder.typicode.com/posts/1`), a normal (non-orphan) session end, and analysis reopen:
the requested URL shows `EXACT RUNTIME OBSERVED` with a real session/transaction/method/status
reference and its original code reference intact; a sibling DEX candidate for the same host but a
different, never-requested path (`/posts`) correctly shows `HOST CORRELATED` instead, provenance still
`CODE REFERENCED`. Raw log: `Correlated 1 entries for <analysisId>: 1 exact RUNTIME_OBSERVED URLs, 1
host-only correlations`.

**Correction and follow-up, 2026-09-13 (fourth pass)**: an earlier version of this note claimed
idempotency was "guaranteed by construction" without a test — retracted (see `STATE.md`'s correction
in the same section for why an untested inference isn't evidence, and why the original code had no
defined behavior for a later, *distinct* legitimate observation, which is the actually-important case).
The matching/merge logic was extracted into a pure, directly-unit-tested function
(`UrlEvidenceCorrelator`, 12 tests) with an explicit, documented retention policy (most-recent
exact-match wins by timestamp; host correlation reflects the most recent contributing batch and is
never cleared by an unrelated later batch). Real idempotency (repeat-import no-op) and real
non-suppression (a later distinct transaction replaces, never loses, an earlier one) are both now
directly asserted by tests, not inferred. A second real on-device session exercising this on the same
analysis remains not re-attempted this pass (the one attempt was abandoned after unrelated install/UI
flakiness) — a concrete, named open item. See `STATE.md`'s "Milestone 9 — URL evidence wiring and
host-correlation fix" section for full evidence.

### Phase 9.5: Traffic Inspector detail-view click fix

`TrafficItemRow`'s `onClick = { selectedRecord = record }` inside `TrafficInspectorScreen`'s
`LazyColumn` does not open the detail view on a physical device — the same symptom family already
root-caused once this milestone's predecessor session (a static, first-composed `PrimaryActionButton`
worked where a dynamic, state-reading button and a `Modifier.pointerInput` + `detectTapGestures`
variant of the same widget did not, inside `WorkLiveMonitorScreen`). Whether the same root cause
applies here is not yet established — diagnose before fixing.

Tasks (2):
1. Reproduce on-device and apply the same systematic elimination approach used for the
   `WorkLiveMonitorScreen` fix (compare a known-working control in the same screen against the
   suspect widget; test whether the defect is position/structure-dependent) to identify the actual
   cause for this specific screen, rather than assuming it is identical.
2. Apply the minimal fix that makes the row's tap reliably open `selectedRecord`'s detail view, and
   verify against a genuine captured HTTPS transaction (not a seeded/demo record).

Exit / verification: on-device screenshot sequence showing a tap on a real captured transaction's row
opening its detail view with headers, decoded body preview, and protocol/state fields populated.

**Status, 2026-09-13: investigated per task 1, does not reproduce on the authorized emulator.**
`TrafficItemRow` is a plain `Card(Modifier.clickable{})` with no `pointerInput`/`detectTapGestures`,
and `TrafficInspectorScreen`'s own `Scaffold` has no `bottomBar` — the specific mechanism the
referenced `WorkLiveMonitorScreen` diagnosis found (a `Scaffold`+`bottomBar` combination breaking
content-slot touch handling) does not apply here structurally, since this screen never had a
`bottomBar` to begin with. Tested twice on `emulator-5554` with exact `uiautomator`-dumped element
bounds (not estimated screen coordinates — an earlier attempt in this same check produced a false
negative from a mis-tap landing on a filter chip instead of a row): a seeded WSS record and a seeded
SSE record both opened their full detail views correctly (handshake/close-code/all-messages for the
WSS one; content-type/last-dispatched-id/all-events for the SSE one). Separately, earlier in the same
session and incidentally (while verifying unrelated ownership-verification work), a *genuinely
captured* real WSS transaction was clicked open from this exact screen and displayed its complete real
detail. Task 2's exit criterion ("verify against a genuine captured HTTPS transaction, not a
seeded/demo record") is therefore satisfied for the emulator.

**Update, 2026-09-13, same day — physical Pixel 8 verified too, per explicit user authorization
("verify on pixel 8").** The original report (`checkpoint_8_9_acceptance/SUMMARY.md` §9) was on this
exact device (`39271FDJH008HQ`, physical Pixel 8/shiba). Connected via USB, confirmed as the only
device targeted (`adb devices -l` scoped every command to `-s 39271FDJH008HQ`), navigated to
Settings → Open Traffic Inspector → Load Verified Capture Fixtures (no Work Profile/sandbox session
needed — a stale session-DB row from the earlier Checkpoint 8.9 work was left untouched rather than
interacted with, since the Work Profile itself no longer exists on this device and touching that card
risked an unnecessary state transition unrelated to this check). Tapped two real rows using exact
`uiautomator`-dumped bounds:
- An SSE record (`events.apksandbox.io/stream`) opened its full detail view: content-type,
  last-dispatched-id, all 3 received events with real JSON payloads.
- A WS record (`echo.websocket.org`) opened its full detail view: handshake, `Closed (Code: 1000)`,
  all 3 recorded messages including the actual echoed text.

Both confirmed via screenshot, not just the accessibility-tree dump. **This resolves the previously-
open physical-device gap**: the click-to-detail path works correctly on the exact hardware the defect
was originally reported on. Caveat, stated precisely: this device's installed build
(`versionName=0.1.0`, last updated `2026-09-12 14:27:47`) predates today's other Milestone 9 changes
(ownership tuple, persistence hardening, etc.) — it was not rebuilt/reinstalled this pass, since doing
so was outside the specific, narrow ask and carries its own risk on a physical device with existing
state. This is a faithful verification of the click mechanism specifically, because
`TrafficItemRow`/`TrafficInspectorScreen.kt` were not modified by any pass (today's or the prior
Checkpoint 8.9 session) — the exact file content tested is the same regardless of which build is
installed. No code change was made; no reproducible defect was found on either device tested.

### Phase 9.6: Static analysis persistence hardening

`StaticAnalysisResultStore` round-trips its own on-disk cache through raw
`ObjectOutputStream`/`ObjectInputStream` (arbitrary Java object deserialization of a file the app
itself wrote, at a hardcoded path `/data/data/com.nadeem.apkscope/files/analysis_store` rather than
`context.filesDir`), with every write wrapped in `catch (_: Throwable) {}`. This is the debt
`docs/SUPERVISOR_CONTEXT.md` §20 named directly ("do not deserialize untrusted transported Java
objects... inspect broad catches").

Tasks (2):
1. Replace the hardcoded path with `context.filesDir`-derived storage, and replace the silent
   broad-`Throwable` catch around writes with an observable failure signal (log at minimum; a
   persisted/observable status is preferred if a cheap hook already exists nearby).
2. Replace the raw `ObjectInputStream.readObject()` deserialization with a format that does not
   deserialize arbitrary Java classes (a manual/versioned encoding, or moving this data into the
   existing Room database alongside other persisted analysis state), or, at minimum, add a version/
   integrity check that causes a malformed or foreign file to be treated as a cache miss rather than
   whatever `readObject()` would otherwise do with it.

Exit / verification: a unit test asserting the new format's round-trip, and a unit test asserting a
truncated/malformed/foreign cache file is handled as a clean cache miss (no crash, no arbitrary
deserialization attempted).

**Status, 2026-09-13: task 1 done in full; task 2 done as a partial mitigation.** Task 1:
`StaticAnalysisResultStore.get`/`put`/`clear` now take a `Context` and derive their path from
`context.applicationContext.filesDir` (threaded through all 6 real call sites); every catch now logs
via `Log.w` with the real exception type/message. Task 2: extracted the file I/O into
`StaticAnalysisFileStore` — a `[4-byte magic][4-byte version]` header, checked via primitive
`DataInputStream` reads, gates every `ObjectInputStream.readObject()` call; a file that doesn't match
is rejected as a cache miss before any object deserialization is attempted. **This is the "at minimum"
option this task's own text offered, not the stronger "replace with a non-Java-serialization format"
one** — once the header matches, the payload is still Java-serialized and still trusts its object
graph; a full rewrite of `PersistedStaticData`'s (and its nested model classes') wire format was not
attempted, and is named as a real remaining gap rather than implied solved. Also fixed, matching the
same interruption/retry concern raised for URL-evidence idempotency: writes are now atomic
(temp-file-then-rename via the shared `AtomicFileWriter`), closing a real partial-write-corruption risk
the previous direct-write pattern had. Exit criteria met: `StaticAnalysisFileStoreTest` (6 tests, round
trip + missing file + foreign content rejected pre-`readObject` + corrupted-but-header-valid file fails
closed + unsupported version rejected) and `AtomicFileWriterTest` (5 tests, including a simulated
mid-write crash never corrupting a pre-existing good file). All 11 pass.

**2026-09-13, second same-day pass — wording corrected, CRC32 integrity check added, concurrency race
closed.** The header check above is **format validation**, not an "integrity gate" as previously
described — it rejects a file that isn't this store's format at all, but does nothing against
corruption of an otherwise well-formed file. Added a genuine check for that: the envelope now also
carries a CRC32 of the payload, computed on write, checked before `readObject()` on read.
`StaticAnalysisFileStoreTest.bitFlipWithinPayload_isCaughtByCrc32NotJustHeaderCheck` (new, 7th test in
that suite) proves a single in-payload bit-flip — header, length, and stored CRC all otherwise intact
— is now caught, which the header alone would have missed. Not claimed: CRC32 is not cryptographic
authentication (a writer with access to this exact path could forge a valid CRC32 over a payload of
its own choosing); this is accidental-corruption detection layered in front of the same Java
deserialization this task's stronger option (full format replacement) still has not addressed — the
`[~]` status stands. Separately: found and closed a real concurrent-import lost-update race (nothing
previously serialized the get→modify→put cycle two independent importers could run against the same
analysisId) via `StaticAnalysisResultStore.withLock(analysisId)`, a per-key mutex now wrapping
`SandboxSessionCoordinator.correlateUrlEvidenceWithAnalysis`'s full cycle —
`StaticAnalysisResultStoreLockTest` (new, 2 tests) proves it with real concurrent threads (8 × 200
read-modify-write increments through the lock → exactly 1600, zero lost updates; different keys don't
contend). Also fixed while doing this: `put()` previously updated its in-memory cache before the disk
write was confirmed to succeed, silently letting the process claim a newer state than what was
actually durable — now the cache updates only after a successful write. Full JVM suite after this
pass: 51 suites, 357 tests, 0 failures (was 50/351 before this pass's 6 new tests: the CRC bit-flip
test, 2 lock tests, and 3 tie-break tests added under Phase 9.4/MS9-URL02's correlator work).

---

### Phase 9.7: Pixel 8 acceptance defects, fifth pass (2026-09-14) — not part of the original plan, opened by physical-device acceptance findings

Two defects surfaced during physical Pixel 8 acceptance and recorded under MS9-UI02/the "Physical
Pixel 8 acceptance" section in `STATE.md`: (1) the `canRequestPackageInstalls=false` remediation button
opens **Personal's** own Settings, not the Work profile instance that actually holds the failing
permission; (2) a session's URL-evidence export/import silently produced no trace at all, root cause
unconfirmed. Milestone 9 was kept open rather than accepted with these outstanding — see
`REQUIREMENTS.md`'s new **MS9-URL03** entry and the permission-remediation update appended to
**MS9-UI02** for the exact requirement text and evidence; full narrative below.

**Item 1 (permission remediation) — fixed and verified on-device, `emulator-5554`.** Traced the two
application instances involved (`SandboxWorkQueryActivity`, Work profile, checks
`canRequestPackageInstalls()`; `SandboxPreparingScreen`, Personal profile, previously launched Settings
itself with no cross-profile targeting) and reused the existing `ACTION_WORK_QUERY` cross-profile
mechanism (no new action, no hardcoded user ids, no new permissions) to make the remediation open the
Work profile's own `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` screen. Building a real, on-device
focused test (`WorkInstallPermissionRemediationInstrumentedTest`) surfaced two further genuine platform
behaviors, each investigated to a confirmed root cause rather than worked around blindly:
- `Settings$ManageAppExternalSourcesActivity` (the "SPA" framework screen this intent resolves to) does
  not reliably deliver `onActivityResult` to a `startActivityForResult` caller — a Back press tears down
  the querying Activity's own task before it can respond, confirmed via `onDestroy(isFinishing=true)`
  firing with the tracked session id still unconsumed and no `onActivityResult` ever reached.
- Independently of the above, Android does not deliver an already-computed cross-profile `ActivityResult`
  back to a caller whose task is fully `STOPPED` behind the still-foreground launched screen — delivery
  waits until that caller's task is foreground-eligible again (the user actually leaving the launched
  screen), no matter how fast the Work side itself responds.

Fixed by launching Settings with a plain `startActivity` (dropping `startActivityForResult` entirely —
there is no reliable per-launch result to wait for here) and responding to the cross-profile query the
moment the launch itself is confirmed. `opened` now honestly means "genuinely launched in the correct
profile" — never "the user already returned" or "permission granted." A separate, always-live
`checkInstallPermission` query (never cached) is the sole source of truth for the actual permission
state, called by the UI immediately after opening Settings and again on manual Retry — this is how
denial, cancellation, a successful permission change, and retry are all distinguished, not by inferring
anything from Settings' own resultCode (which this specific target does not meaningfully report).

**Verification**: `WorkInstallPermissionRemediationInstrumentedTest` (2 tests, `emulator-5554`) —
`checkWorkInstallPermission` returns a real non-null answer from the Work profile's own process;
`requestOpenWorkInstallSettings` genuinely opens the settings screen as `UserHandle{11}` (logged
directly from that process) and never `UserHandle{0}` (Personal), with a fresh recheck immediately
succeeding afterward. Full JVM suite re-verified clean (`tests=360 failures=0`) after these changes.
Four other androidTest suites this pass's edits could plausibly have affected were re-run and show no
regression: `UrlEvidenceImportStatusStoreInstrumentedTest` (5/5), `PrepareSandboxTimeoutTest` (3/3),
`RepeatedUrlEvidenceImportInstrumentedTest` (6/6), and the new
`UrlEvidencePipelineDiagnosticsInstrumentedTest` (3/3, below).

**Item 2/3 (missing URL evidence tracing, cleanup ordering) — diagnostic infrastructure implemented and
independently tested; the original incident's root cause still not confirmed.** Added
`UrlEvidencePipelineDiagnostics` (`core:crossprofile`) — a durable, bounded (500-line cap), per-process
JSONL log correlated across the Personal/Work boundary via a single `operationId` threaded through the
existing cross-profile query extras — instrumented at every stage of the normal-cleanup import path
(scheduling, cross-profile dispatch, Work-side receipt, session/ownership filtering, artifact creation,
URI grant/result delivery, Personal-side read/validation, correlation/persistence, durable outcome).
Paired with `UrlEvidenceImportStatusStore.Status` (`PENDING`/`EMPTY`/`IMPORTED`/`FAILED`, "not
requested" represented by record absence) and a `markPending()` call written *before* cross-profile
dispatch, so a mid-import process death now leaves a real, durable "started but never concluded" trace
instead of nothing. Found and fixed two further real, independent latent bugs while tracing this (both
real regardless of whether either was the original incident's proximate cause): `UrlEvidenceImportStatusStore`'s
own hardcoded-path fragility (the same class MS9-PER02 already fixed elsewhere); and two missing
outer-exception-handling gaps (`SandboxSessionCoordinator.importUrlEvidence()`'s initial
`repository.get()` call, and `SandboxCleanupViewModel.importAndReconcile()`'s entire multi-step
sequence) that could previously abandon an import silently on a transient Room/IO hiccup. All of the
above is directly, independently tested — `UrlEvidencePipelineDiagnosticsInstrumentedTest` (3 tests) and
`UrlEvidenceImportStatusStoreInstrumentedTest` (5 tests), both `emulator-5554`, all passing — but this
infrastructure has not yet been exercised against a *reproduction* of the original missing-evidence
incident on physical hardware; its value there remains unproven until item 4 below is actually run.

**Item 4 (real Pixel 8 verification) — done, 2026-09-14, same day, later pass.** The Pixel 8 reconnected
mid-session (a real USB drop — confirmed missing at the host's USB hardware level, not just adb —
recovered by the user re-enabling debugging over Wi-Fi; the in-progress on-device app state was
untouched by the drop). Confirmed the installed build predated this pass's fixes
(`lastUpdateTime=2026-09-13 20:36:47`); rebuilt, dex-verified (`strings` on the extracted classes.dex
confirms both the permission-routing fix and the diagnostics pipeline are present), and reinstalled with
data preserved (`lastUpdateTime` became `2026-09-14 07:23:00`). Ran the full scenario: a fresh fixture
import and session (`sessionId=3fb77e24-3058-40a8-a18f-df17bdf79dd8`,
`analysisId=616e9ee5-56f5-4247-915a-6b5dd48aca1b`), a distinctive nonsensitive request (`GET
dummyjson.com/quotes/random`, chosen for its per-request-unique response body), confirmed genuinely
captured with session/ownership info in both the Traffic Inspector UI and raw logcat
(`OWNERSHIP_VERIFICATION host=dummyjson.com status=MATCHED`), ended the session through the real "End
Sandbox Session" button and the real Android uninstall confirmation, then restarted the app
(`am force-stop` — a genuine process kill, not simulated) and reopened the same analysis. Result: **the
full URL-evidence handoff succeeded end-to-end** — all nine diagnostic stages present and correlated by
one `operationId` across both profiles, `UrlEvidenceCorrelation: Correlated 1 entries ... 1 exact
RUNTIME_OBSERVED`, `status=IMPORTED` persisted and read back identical (via direct `run-as` file
inspection, not only the UI) both before and after the restart. Per this pass's own explicit instruction,
this is reported as **the original incident not reproducing this run** — not as its root cause having
been found or fixed. Item 2's block was also not encountered on this real device: the app's own live
`canRequestPackageInstalls()` check read **true** at prepare time — the fact the product's own
remediation logic gates on — so the remediation button was never triggered, reported honestly as "not
exercised." The separate `REQUEST_INSTALL_PACKAGES` grant-flag listing read `false` throughout; that
listing alone is not read here as "installation access was denied," since the live check and the real
install outcome both said otherwise. See `STATE.md`'s "Milestone 9 — Pixel 8 acceptance, fifth pass,
item 4 (physical device)" section for full evidence.

**Sixth pass, same day — report corrections and import-guard verification.** Two mischaracterizations
in this pass's own reporting were corrected: the skipped scheduling attempts observed during item 4's
session are **concurrent import suppression** of a still-active operation, not evidence about
repeating an already-*completed* import (a distinct guarantee); and the "not exercised" framing above
was tightened so a `false` permission-grant listing is never read as "installation access denied" when
the product's own live check says otherwise. `SandboxCleanupViewModel.importAndReconcile`'s in-flight
guard was then verified directly (it had no dedicated test before this pass): a new
`SandboxCleanupImportGuardInstrumentedTest` (5 tests, `emulator-5554`, all passing) confirms the guard
releases correctly after success, failure, timeout, and cancellation, and that a concurrent call made
while a genuinely still-active operation is running is skipped rather than raced — reproducing the
physical session's own finding under controlled timing. No stale-guard defect was found; no fix was
needed. Separately, `RepeatedUrlEvidenceImportInstrumentedTest` was inspected (not rerun) and confirmed
to already directly verify "a completed import can be repeated without duplicates or unintended
replacement" through the real production `UrlEvidenceImporter.correlate()` function against real
device storage — see `REQUIREMENTS.md`'s new MS9-URL04 entry for the full citation, device scope, and
the explicit, separate "later observation replaces earlier"/"older artifact replay does not regress"
coverage in that same suite.

**Milestone 9 status**: kept open only while required acceptance criteria remain unmet — see
`STATE.md`'s "Final Milestone 9 requirement reconciliation" section for the complete, criterion-by-
criterion accounting of what that means concretely. Physical-device capture acceptance for the current
build is closed by this evidence; the original missing-evidence incident's root cause remains formally
unconfirmed (do not read "did not reproduce" as "fixed"); Phase 9.1's platform limitation and full
Java-serialization replacement remain open, unrelated, and untouched by this pass.

---

## Milestone 8 - Deeper Static Analysis & Extended Protocol Support (complete, historical)
 
Status: Complete for what it verified — see the note above for why it is no longer the active
milestone. Phases 8.1-8.8 implemented; Phase 8.9 (2026-09-12) resumed the milestone
because Phase 8.8's own "Complete" claim did not hold under real-server verification — see below.

### Resumption note (2026-09-12)
Phase 8.8 marked HTTP/2, gRPC, and SSE inspection complete based on a device test
(`testHttp2RelayOnDevice`) that constructed `Http2RelayHandler` directly over raw loopback
sockets, bypassing the real TLS/ALPN negotiation path in `HttpsInspectionEngine` entirely; gRPC
and SSE tests fed synthetic bytes straight to their decoders with no network involved. None of
the three had ever been driven through the actual engine against a real server. Phase 8.8 also
left `.planning/STATE.md` asserting a clean working tree (an empty-string digest) while 11
tracked files carrying this milestone's real implementation sat uncommitted, and shipped a
milestone evidence archive quoting URL-provenance logic that the working tree had already
replaced. Phase 8.9 replaced the bypass tests with ones that genuinely exercise
`HttpsInspectionEngine`'s real dispatch against real external servers, which surfaced three
previously-invisible defects (see Phase 8.9 below and REQUIREMENTS.md's H2/GRPC/SSE sections) —
none reachable by a synthetic-socket test, all now fixed and covered.

## Progress

| Milestone / Phase | Plans Complete | Status | Completed |
| :--- | :--- | :--- | :--- |
| **Milestone 10: Security Audit** | 2/11 (10.1, 10.2 done; 10.3–10.11 not planned) | **In progress, opened 2026-09-14** — full 11-phase requirement inventory reconciled against the 22-section specification. Phase 10.1's 10-rule static catalog and Phase 10.2's full select→run→view→evidence→persist→restart→reopen workflow are both done, the latter **product verified on a real emulator** (not JVM-test-only), and a same-day correction pass fixed a destructive Room migration and closed the cancellation/error/identity/risk-score test gaps with 8 new instrumented tests. Full suite `tests=394 failures=0`. See `.planning/REQUIREMENTS.md`'s "Milestone 10" block for per-requirement status. | — |
| Phase 10.1: Security Audit Rule Catalog & Static Audit Engine | 2/2 | Complete (component verified) | 2026-09-14 |
| Phase 10.2: Static Security Audit — First Complete User Workflow | 2/2 | Complete (product verified on-device — real APK selection, real audit run, real restart/reopen, plus a correction pass with 8 new instrumented tests covering migration safety, cancellation, errors, and audit identity) | 2026-09-14 |
| Phase 10.3: Additional Static Audit Categories | 9/TBD (10.3-01..09 done) | MS10-NET01, MS10-CODE01, MS10-BUILD01, MS10-BUILD02 all done and accuracy-hardened (JVM-test-level, matching each requirement's own stated verification level); MS10-SECRET01-03 implemented at the scanner level (masking/no-network verified) but not yet wired to a persisted finding/UI. MS10-COV01 (coverage reporting) not yet a dedicated pass, though `StaticAnalysisCoverage` is already reused by every new scanner this phase added | - |
| Phase 10.4: Audit Foundation — Multi-Session, Reselection, Full Persistence | 0/TBD | Not started | - |
| Phase 10.5: Guided Runtime Verification Sessions | 0/TBD | Not started | - |
| Phase 10.6: Runtime Collection Completion | 0/TBD | Not started | - |
| Phase 10.7: Runtime Security Rules | 0/TBD | Not started | - |
| Phase 10.8: Correlation and Coverage | 0/TBD | Not started | - |
| Phase 10.9: Evidence-Based Reporting and Retesting | 0/TBD | Not started | - |
| Phase 10.10: Autonomous Execution Infrastructure | 0/TBD | Not started | - |
| Phase 10.11: Security Audit Verification Closure | 0/TBD | Not started | - |
| Phase 10.2: Guided Runtime Verification Sessions | 0/TBD | Not started | - |
| Phase 10.3: Evidence-Based Security Audit Reporting | 0/TBD | Not started | - |
| Phase 10.4: Autonomous Execution Infrastructure | 0/TBD | Not started | - |
| Phase 10.5: Security Audit Verification Closure | 0/TBD | Not started | - |
| **Milestone 6: HTTPS POC closure** | 4/4 | Complete | 2026-09-10 |
| **Milestone 7: Integrated Traffic Inspection Feature** | 5/5 | Complete | 2026-09-10 |
| **Milestone 8: Deeper Static Analysis & Extended Protocol Support** | 8/8 | Complete | 2026-09-11 |
| **Milestone 9: Capture Ownership, Evidence Wiring & Verification Closure** | 15/15 | **Closed, 2026-09-14 (sixth pass)** — all 15 reconciled requirements (13 new MS9-prefixed plus 2 pre-existing Milestone 8 requirements this milestone corrected under their original IDs, DEX04 and VER02) either met or correctly reclassified; none block closure. Phase 9.1 platform limitation verified (superseded in practice by Phase 9.3's shipped substitute), Phase 9.2 complete, Phase 9.3 complete (real, reproducible, count-based second-controlled-application on-device evidence; the concurrent-session hang root-caused, fixed, and tested), Phase 9.4 complete (process-restart persistence and all repeated-import reconciliation items verified through both pure logic and real Android-persistence integration), Phase 9.5 complete (verified on both emulator and physical Pixel 8), Phase 9.6's own requirement satisfied via its own written alternative (format validation + CRC32 integrity check before deserialization; full Java-serialization replacement kept open as named, deliberately-deferred hardening debt, not required work), Phase 9.7 complete — the wrong-profile permission-remediation defect fixed and **verified on the emulator specifically** (the physical Pixel 8 session did not encounter the permission-denial block, so that remediation transition was not exercised there); the URL-evidence diagnostics/outcome-state infrastructure implemented, tested, and reproduced successfully end-to-end on a physical Pixel 8 (fresh session, normal handoff, restart persistence — completed-artifact-replay and the import-guard lifecycle remain emulator-scoped), closing physical-device capture acceptance for the current build; a sixth pass corrected two reporting mischaracterizations and directly verified the import-guard's own release lifecycle with no defect found. Three items remain as explicit, non-blocking open notes, not silently dropped: full Java-serialization replacement, the original missing-evidence incident's still-unconfirmed root cause, and VER02's gRPC/SSE product-level verification (never part of Milestone 9's own scope — the correctly-identified next roadmap item). See `STATE.md`'s "Final Milestone 9 requirement reconciliation" for the full criterion-by-criterion accounting. | — |
| Phase 8.1: Baseline audit & GSD roadmap initialization (Phase A) | 1/1 | Complete | 2026-09-11 |
| Phase 8.2: DEX URLs, SDK signatures, and API references (Phase B) | 1/1 | Complete | 2026-09-11 |
| Phase 8.3: Static findings UI, Smali navigation & coverage (Phase C) | 1/1 | Complete | 2026-09-11 |
| Phase 8.4: HTTP/2 inspection & HPACK multiplexing (Phase D) | 1/1 | Complete | 2026-09-11 |
| Phase 8.5: gRPC protocol inspection & message framing (Phase E) | 1/1 | Complete | 2026-09-11 |
| Phase 8.6: Server-Sent Events incremental streaming (Phase F) | 1/1 | Complete | 2026-09-11 |
| Phase 8.7: HTTP/3 & QUIC feasibility and bounded prototype (Phase G) | 1/1 | Complete | 2026-09-11 |
| Phase 8.8: Regression verification, test artifacts & documentation (Phase H) | 1/1 | Complete | 2026-09-11 |
| Phase 8.9: Real-path protocol integration & evidence integrity (resumption) | 1/1 | Complete | 2026-09-12 |

### Phases

- [x] **Phase 8.1: Baseline audit & GSD roadmap initialization (Phase A)**
- [x] **Phase 8.2: DEX URLs, SDK signatures, and API references (Phase B)**
- [x] **Phase 8.3: Static findings UI, Smali navigation & coverage (Phase C)**
- [x] **Phase 8.4: HTTP/2 inspection & HPACK multiplexing (Phase D)**
- [x] **Phase 8.5: gRPC protocol inspection & message framing (Phase E)**
- [x] **Phase 8.6: Server-Sent Events incremental streaming (Phase F)**
- [x] **Phase 8.7: HTTP/3 & QUIC feasibility and bounded prototype (Phase G)**
- [x] **Phase 8.8: Regression verification, test artifacts & documentation (Phase H)**
- [x] **Phase 8.9: Real-path protocol integration & evidence integrity (resumption)**

### Phase 8.1: Baseline audit & GSD roadmap initialization (Phase A)
Confirm branch `dev/traffic_inspection` and uncommitted state. Audit dependencies (`smali-dexlib2`, `okhttp3`). Initialize Milestone 8 requirements and roadmap.

### Phase 8.2: DEX URLs, SDK signatures, and API references (Phase B)
Structured URL extraction from all `classes*.dex`, versioned SDK signature catalog (v1.0.0), and 7-category API reference/invocation scanner with deduplication and bounded execution.

### Phase 8.3: Static findings UI, Smali navigation & coverage (Phase C)
Dedicated detail screens for Embedded URLs, Detected SDKs, API References, Smali disassembler links, search/filters, and static analysis coverage metrics.

### Phase 8.4: HTTP/2 inspection & HPACK multiplexing (Phase D)
Binary frame decoder (DATA, HEADERS, RST_STREAM, SETTINGS, etc.), HPACK header decompression, concurrent stream tracking, transparent forwarding, and transaction recording.

### Phase 8.5: gRPC protocol inspection & message framing (Phase E)
Content-type and path detection, 5-byte length-prefixed frame decoder, unary and streaming message tracking, timestamps, binary previews, and trailer status code decoding.

### Phase 8.6: Server-Sent Events incremental streaming (Phase F)
`text/event-stream` response detection, multi-line data parsing, event IDs, retry intervals, heartbeats, bounded line buffers, and real-time incremental store updates.

### Phase 8.7: HTTP/3 & QUIC feasibility and bounded prototype (Phase G)
Comprehensive architectural decision document for QUIC/H3 on Android, plus bounded observational QUIC packet parser behind experimental flag.

### Phase 8.8: Regression verification, test artifacts & documentation (Phase H)
Full unit and connected test suites, verification evidence manifest, documentation updates, and handoff report with zero modifications to main.

### Phase 8.9: Real-path protocol integration & evidence integrity (resumption)
Replaced the bypass-style `testHttp2RelayOnDevice` with one that drives a real TLS+ALPN handshake
through `HttpsInspectionEngine` and exchanges genuine frames with httpbin.org; added
`testHttp2GrpcRelayOnDevice` (real gRPC over h2 against Google's public interop server) and
`testHttp2SseStreamingOnDevice` (real SSE over h2 against Wikimedia's recentchange feed) alongside
the existing decoder-level unit tests, which were kept rather than deleted. Rewrote
`testHttp11SseStreamingOnDevice` to use a real HTTPS host instead of an unreachable
`127.0.0.1` loopback target (`DestinationPolicy` correctly denies loopback — the original test
could never have passed). Fixed three defects these tests surfaced: a missing initial HTTP/2
SETTINGS frame to real upstreams (RFC 7540 §3.5), a buggy `SocketChannel`-adapter upstream socket
that silently dropped a write following an earlier one on the same connection, and case-sensitive
HTTP header lookups that broke SSE/chunked/connection-close detection against any real server
using non-canonical header casing. Added a fallback that reconnects the upstream leg without h2 in
its ALPN offer when the downstream client can't speak h2 but the real upstream can. Wired
`ApkAnalyzer.analyze()`'s `observedRuntimeHosts` parameter through to `DexUrlExtractor` end-to-end
(tested against the real fixture APK) — the exact-URL vs host-correlation provenance model itself
was already correct in the working tree; this closed the "never actually called with evidence in
production" gap for host-level correlation specifically. Corrected `STATE.md`'s false clean-tree
claim and the milestone evidence docs' stale (pre-fix) URL-provenance code quote. Full unit suite
(314 tests/45 suites) and full `HttpsInspectionIntegrationTest` (25/25 + 1 intentional `@Ignore`)
verified together against the final tree. Exact-URL `RUNTIME_OBSERVED` provenance and a full
fixture-in-Work-Profile screenshot demonstration remain open — see REQUIREMENTS.md and STATE.md.

### Phase 6.1: Reconcile implementation and evidence (Phase A)

Read existing GSD state, code, working tree, and test artifacts. Confirm branch. Determine which requirements already have evidence. Correct test counts. Inspect package and destination scope. Produce a bounded execution plan using installed GSD conventions.

Exit: Actual implementation inventory, traceable test totals, known gaps, and next runtime steps.

### Phase 6.2: Demonstrate the complete capture path (Phase B)

Run genuine fixture GET and POST in the Work Profile through the VPN and viewer. Verify profile identity and actual CA setup. Keep deterministic dummy data and capture transaction details.

Exit: Readable GET response and POST request plus response in the inspector UI, with evidence of the actual routing path.

### Phase 6.3: Close focused correctness gaps (Phase C)

Verify upstream trust and hostname rejection, target CA rejection, destination restrictions, listener isolation, bounded parsing, forwarding fidelity, redaction, disabling, and reset. Fix discovered defects within the POC scope.

Exit: Acceptance tests pass with no policy or TLS validation weakening. Failures are explicit if blocked.

### Phase 6.4: Document and hand off (Phase D)

Reconcile all requirements against artifacts, document reproduction and limitations, update GSD state, and stop. No merge or release.

Exit: POC demonstrated with bounded supported behavior, or a precise incomplete report describing the remaining blocker.

## Proposed subsequent work, not authorized in this milestone

Priority 1: Plaintext HTTP inspector, traffic filtering, richer transaction UI, and practical HTTPS compatibility handling.
Priority 2: WebSocket recording for readable ws traffic and compatible decrypted wss traffic, including fragmentation, direction, timestamps, ping, pong, and close events.
Priority 3: PCAP exports, HAR for HTTP transactions, JSON for WebSocket messages, and configurable retention.
Priority 4: Session and APK version comparison, unified evidence timeline, and capture coverage reporting.
Priority 5: Optional destination reputation, sensitive data indicators, deeper static inspection, and protocol coverage research for HTTP/2, HTTP/3, QUIC, gRPC, and Server Sent Events.

Future features require their own scoped milestone. Capture exports do not themselves decrypt TLS. Reputation is context, not proof of malicious behavior. Session comparisons must account for differences in exercised app flows.
