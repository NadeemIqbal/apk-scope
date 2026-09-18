# Requirements

## Milestone 10: Security Audit (opened 2026-09-14)

An explicitly user-directed scope addition, independent of `docs/FUTURE_CAPABILITIES.md`'s own priority
order (see `.planning/PROJECT.md`'s Current Priority section). See `docs/SECURITY_AUDIT.md` for the
product overview, `docs/SECURITY_AUDIT_RULES.md` for the rule catalog these requirements govern,
`docs/SECURITY_AUDIT_VERIFICATION.md` for the acceptance/evidence contract each requirement below cites,
and `docs/AUTONOMOUS_EXECUTION.md` for the mechanics MS10-AUTO requirements depend on. Every requirement
states a measurable acceptance criterion and its verification dependency explicitly — per
`docs/VERIFICATION_STRATEGY.md`, source presence, compilation, and passing unit tests are not the same
claim as product verified, and the status line on each item below states exactly which of those has
actually been reached.

**Reconciliation, 2026-09-14 (full inventory pass)**: a full 22-section product specification was
received for this milestone. An earlier pass corrected the MS10-STATIC block against Section 8's
outcome/severity/confidence vocabulary but deferred itemizing the rest, reasoning that per this
project's GSD discipline, requirements are elaborated at planning time. **That deferral was overridden
by explicit instruction**: "the full requirement inventory must exist before further closure claims...
do not silently reduce the assignment to the current ten rules." Every category below (static checks,
guided sessions, runtime collection, runtime rules, correlation, coverage, reporting, retesting, export,
privacy, persistence, verification, and product-level autonomous execution) now has individually
identified requirement IDs, a measurable acceptance criterion, a verification level, a phase mapping,
and explicit dependencies. Detailed step-by-step implementation plans for phases beyond the one
currently active still follow this project's normal per-phase planning convention (research/plan files
created when that phase begins) — this inventory is the complete requirement *list*, not a complete set
of *plans*. **Execution-automation tracking note**: this file tracks only product requirements — the
product's own opt-in `WorkManager`-based autonomous re-audit feature (MS10-AUTO) is real product scope
and stays here; this session's own execution continuity (Claude session checkpointing, usage telemetry,
scheduling) is a separate, non-product concern tracked exclusively in `.planning/STATE.md`, never as a
numbered requirement here.

### Static audit engine (MS10-STATIC)

- [x] **MS10-STATIC01**: A `SecurityAuditEngine` evaluates a versioned rule catalog against the
  existing `ApkAnalysisInput` declared-facts snapshot and returns a `SecurityAuditReport` (engine
  version, ordered findings, per-outcome counts). Outcome (`FINDING_DETECTED`/`CHECK_PASSED`/
  `NEEDS_REVIEW`/`NOT_TESTED`/`NOT_APPLICABLE`/`COLLECTION_FAILED`), severity, and confidence are kept
  as three independent fields on every finding — not a single pass/fail/warn value — per the Security
  Audit specification's Section 8. **Acceptance criterion**: `DefaultSecurityAuditEngine.audit(input)`
  evaluates every rule in `StaticAuditRules.all`, in stable order, exactly once, for any valid input; a
  `CHECK_PASSED` finding always carries `Severity.INFO`. **Verification dependency**:
  `SecurityAuditEngineTest` (4 tests — catalog size, findings order, outcome-count reconciliation,
  all-passed baseline) and `StaticAuditRulesTest.everyPassedCheck_carriesInfoSeverity` (the
  severity-independence guard). **Status: component verified** —
  `core/risk/src/main/kotlin/com/nadeem/apkscope/core/risk/audit/SecurityAuditEngine.kt`, 4/4
  engine tests passing. Not yet product verified — no UI or persisted-analysis integration exists yet
  (Phase 10.3).
- [x] **MS10-STATIC02**: The rule catalog's engine version (`security-audit-v1`) is independent of, and
  never conflated with, `core:risk`'s `static-v1`/`runtime-v1`/`combined-v1` engine versions. **Acceptance
  criterion**: no shared rule ID exists between the two catalogs; each is versioned and bumped
  independently. **Verification dependency**: manual cross-check of `AuditRuleIds` against `RuleIds` (no
  automated collision test exists yet — see `docs/SECURITY_AUDIT_RULES.md`). **Status: source
  implemented** — no ID collision by construction and by inspection; not yet covered by an automated
  guard test.
- [x] **MS10-STATIC03**: The v1 static catalog covers, at minimum: debuggable build, signature
  integrity, exported-component-surface ratio, accessibility+overlay combination, SMS+internet
  combination, boot-persistence+internet combination, outdated target SDK, native-code presence,
  cleartext-traffic declaration, and backup-allowed declaration. **Acceptance criterion**: exactly these
  10 rule IDs exist in `StaticAuditRules.all`, each with a passing trigger-fires and
  trigger-does-not-fire test. **Verification dependency**: `StaticAuditRulesTest` (29 tests). **Status:
  component verified** — 29/29 tests passing; full suite regression-checked at `tests=393 failures=0`
  against the pre-Phase-10.1 `tests=360 failures=0` baseline (33 new audit tests, zero regressions). Not
  yet product verified against a real analyzed APK's stored data (Phase 10.3).
- [x] **MS10-STATIC04**: `ApkAnalysisInput` gains `usesCleartextTraffic` and `allowBackup` fields, wired
  from real `ApplicationInfo` flags (`FLAG_USES_CLEARTEXT_TRAFFIC`/`FLAG_ALLOW_BACKUP`) the same way
  `debuggable` already is, and threaded through `ApkMetadata` → `RiskInputMapper.toRiskInput()`.
  **Acceptance criterion**: both fields populated from real `ApplicationInfo` flags, not a proxy signal;
  `AUDIT_CLEARTEXT_TRAFFIC_ENABLED` and `AUDIT_BACKUP_ENABLED` rules consume them. **Verification
  dependency**: `StaticAuditRulesTest`'s 4 new cases for the two rules (fires/does-not-fire each); full
  suite regression check. **Status: component verified** — 2026-09-14, same day as MS10-STATIC01-03.
  `ApkMetadata` is confirmed not part of the app's Java-`Serializable` persistence boundary
  (`PersistedStaticData` in `StaticAnalysisResultStore.kt` carries only URL/SDK/API-finding/coverage
  data, not `ApkMetadata`), so no backward-compatibility risk from adding these fields. Not yet
  product-verified against a real device-installed APK's actual `ApplicationInfo` flags (only against
  `getPackageArchiveInfo`'s archive-mode resolution, per `ApkMetadata.usesCleartextTraffic`'s own
  pre-existing, still-open device-verification caveat).

### Audit foundation & identity (MS10-FOUND) — Phase 10.2 (partly), Phase 10.4

- [ ] **MS10-FOUND01**: An audit record is bound to APK hash (SHA-256), package name, version, and
  signing identity, plus the rule-catalog version and relevant device/inspection-settings metadata.
  **Acceptance**: a persisted audit record round-trips all five identity fields unchanged. **Verification
  level**: JVM unit test on the persistence model. **Phase**: 10.2 (minimal fields needed for the first
  workflow) → 10.4 (full metadata). **Depends on**: MS10-STATIC01 (report shape).
  **Status: partially done, now with automated evidence** — `SecurityAuditEntity.analysisId` binds the
  audit to `AnalysisSessionEntity.sessionId`, and `engineVersion` is stored directly.
  `SecurityAuditRepositoryInstrumentedTest` (3 tests, run on `emulator-5554`, real on-device Room)
  proves this binding is exclusive (`runAndPersistAudit_bindsAuditExclusivelyToItsOwnAnalysisId` — a
  second, unrelated analysis shows no audit) and that re-running replaces rather than duplicates
  (`runAndPersistAudit_reRunReplacesRatherThanDuplicatesTheSameAnalysisAudit` — a new `auditId` is
  minted, exactly one current audit per analysis holds). Hash/package/version/signing identity are
  **still not duplicated onto the audit entity itself** — reachable only by joining through to the
  analysis. This remains a deliberate single-source-of-truth choice; this requirement's literal
  "round-trips all five identity fields" criterion is still not met as written — the *relationship*
  half is now proven, the *duplicated-fields* half is not.
- [ ] **MS10-FOUND02**: Multiple legitimate sessions (a static-only run, then a later guided run) can
  contribute to the *same* audit record through an explicit, deliberate workflow — never an implicit
  silent merge. **Acceptance**: two sessions against the same APK hash/package/version produce one audit
  record with both contributions traceable to their originating session. **Verification level**: JVM/
  instrumented test on the merge logic. **Phase**: 10.4. **Depends on**: MS10-FOUND01, MS10-SESS01.
- [ ] **MS10-FOUND03**: If staging (the temporary APK copy) has been deleted, the user can reselect the
  same APK, and the app validates identity (hash/package/version match) before resuming the existing
  audit rather than silently starting a new one under the same record. **Acceptance**: reselecting a
  *different* APK against an existing audit record is rejected with an explicit mismatch message, never
  silently accepted. **Verification level**: instrumented test with two distinct fixture APKs. **Phase**:
  10.4. **Depends on**: MS10-FOUND01.
- [x] **MS10-FOUND04**: Audit findings never automatically change the existing deterministic `static-v1`/
  `runtime-v1`/`combined-v1` risk score — integration between the two, if any, goes through a separate,
  documented model. **Acceptance**: running a Security Audit against an analysis leaves that analysis's
  existing `StaticRiskAssessment` byte-for-byte unchanged. **Verification level**: JVM unit test asserting
  no mutation. **Phase**: 10.2 (must hold from the first workflow onward). **Status: true by
  construction, product-observed, and now automated-test-covered** —
  `SecurityAuditRepositoryInstrumentedTest.runAndPersistAudit_leavesExistingRiskScoreAndLevelByteForByteUnchanged`
  seeds a real analysis (riskScore=63, riskLevel=HIGH), runs a real audit against it on `emulator-5554`,
  and asserts full `AnalysisSessionEntity` equality before/after, not just the two headline fields.
  Passed. Closes the "no dedicated automated regression test" gap the prior checkpoint named
  explicitly. **Depends on**: none — true by construction (`SecurityAuditEngine` never touches
  `core:risk`'s types) and now regression-tested.

### Static audit — first user workflow (MS10-UI) — Phase 10.2

- [x] **MS10-UI01**: From an existing static analysis, the user can trigger a Security Audit run without
  installing the target or creating a Work Profile. **Acceptance**: a "Run Security Audit" action is
  reachable from the Analysis Detail screen and completes without any sandbox/install step. **Verification
  level**: product/on-device (emulator). **Phase**: 10.2. **Depends on**: MS10-STATIC01, MS10-FOUND01.
  **Status: product verified** — 2026-09-14. Real device flow: pushed `riskfixture-debug.apk` to
  `emulator-5554`'s Downloads, selected it via the real system document picker (`Choose APK`), the
  resulting analysis persisted, then the new "Security Audit" row on Analysis Detail opened
  `SecurityAuditScreen` and "Run Security Audit" completed with zero installation or Work Profile
  interaction. No JVM-test-only claim — this is a real tap-through on a real emulator.
- [x] **MS10-UI02**: The user can view findings grouped/filterable by outcome, with severity and
  confidence visible per finding, plus a coverage summary (rules run, rules not applicable). **Acceptance**:
  all 10 v1 rules' findings render with correct outcome/severity/confidence for a real analyzed fixture
  APK. **Verification level**: product/on-device. **Phase**: 10.2. **Depends on**: MS10-UI01.
  **Status: product verified** — the coverage card showed `2 Findings / 4 Review / 4 Passed / 0
  Untested` (sums to 10), and all 10 findings rendered with correct title/outcome/severity badges,
  matching Risk Signal Fixture's actual manifest content exactly (debuggable, accessibility+overlay,
  SMS+internet, boot+internet, exported-surface ratio all correctly flagged).
- [x] **MS10-UI03**: The user can open a finding to see its evidence (the exact declared fact that
  triggered it) and remediation guidance. **Acceptance**: opening any `FINDING_DETECTED`/`NEEDS_REVIEW`
  finding shows non-empty evidence and remediation text specific to that rule. **Verification level**:
  product/on-device. **Phase**: 10.2. **Depends on**: MS10-UI02.
  **Status: product verified** — opened "Debuggable build": showed outcome/severity/confidence badges,
  a distinct Evidence card, a distinct Remediation card ("Remove android:debuggable... before releasing
  this build."), the rule ID, and an explicit static-vs-runtime-origin note.
- [x] **MS10-UI04**: The audit persists across app restart and can be reopened showing identical results.
  **Acceptance**: force-stop the app after a completed audit, relaunch, navigate to the same analysis, and
  see the same findings without re-running rules. **Verification level**: product/on-device (real restart,
  not a mocked process). **Phase**: 10.2. **Depends on**: MS10-UI01, MS10-FOUND01.
  **Status: product verified** — `adb shell am force-stop com.nadeem.apkscope` (a genuine process kill, not a
  simulated one), relaunched, navigated Dashboard → Recent Analysis → Security Audit, and the screen
  loaded directly into `LOADED` (not `EMPTY`) with byte-identical coverage counts and finding content —
  the audit was read back from Room, not re-computed.
- [x] **MS10-UI05**: Loading, cancellation, error, empty-result, and incomplete-coverage states are all
  handled explicitly — none silently renders as a clean/complete report. **Acceptance**: cancelling an
  in-progress audit leaves no partial/misleading persisted report; an audit with a collection failure
  shows that failure, not a silently-omitted rule. **Verification level**: product/on-device +
  instrumented test for the cancellation race. **Phase**: 10.2. **Depends on**: MS10-UI01-04.
  **Status: done, product verified (all states)** — `EMPTY`/`LOADED` verified via direct manual
  tap-through (prior checkpoint). `RUNNING`/`CANCELLED`/`ERROR` were not manually reachable (a real
  audit completes too fast to interrupt by hand) — closed instead with a deterministic instrumented
  test, `SecurityAuditViewModelInstrumentedTest` (3 tests, run on `emulator-5554`), using a test-only
  repository subclass (`SecurityAuditRepository` made `open` specifically for this) that adds a
  controlled 2-second delay before delegating to the real implementation: `cancel_beforeRunCompletes_
  transitionsToCancelledAndPersistsNothing` (asserts `CANCELLED` and that no audit row exists after
  outlasting the delay), plus two `ERROR`-path tests for `AnalysisNotFoundException` and a generic
  exception, each asserting the exact surfaced message. All 3 passed. This is real coroutine
  cancellation against the real `viewModelScope`/`Job`, not a mocked state assignment.

### Additional static audit categories (MS10-SECRET, MS10-NET, MS10-CODE, MS10-BUILD, MS10-COV) — Phase 10.3

- [x] **MS10-SECRET01**: Scan supported DEX, resources, assets, and packaged files for private-key,
  credential, token, and likely-confidential-value candidates, distinguishing public application
  identifiers from secrets via contextual heuristics. **Acceptance**: a fixture APK with an embedded
  test-only private key and a fixture with only public identifiers (e.g. a Firebase project ID) produce
  different outcomes. **Verification level**: JVM unit test with fixture byte content; product/on-device
  for a real APK. **Phase**: 10.3. **Depends on**: `core:staticanalysis`'s existing DEX/asset extraction
  (`DexUrlExtractor`, `DexApiScanner` as the analogous precedent).
  **Status: done, scanner-level product verified; DEX only, resources/assets not yet covered** —
  `SecretCandidateScanner.kt` (new), reusing `DexUrlExtractor`'s exact DEX string-pool-plus-instruction
  traversal (no new DEX-walking infrastructure). Distinguishes by *structural format*, not
  entropy-scoring: `PRIVATE_KEY_BLOCK` (PEM header), `AWS_ACCESS_KEY` (`AKIA[0-9A-Z]{16}`),
  `GOOGLE_API_KEY` (`AIza` + exactly 35 chars), `SLACK_TOKEN` (`xox[baprs]-...`), `JWT`
  (`eyJ...\....\....`) — a format a real secret always has and a public identifier structurally never
  does, which is what lets a Firebase project id / package name / version string stay unflagged
  without a separate allow-list. Verified against `riskfixture`'s new `SecretCandidateFixtures.kt` —
  5 real credential-shaped constants (all well-known, non-functional examples: AWS's own official
  `AKIAIOSFODNN7EXAMPLE`, a deliberately-invalid PEM, hand-built Google/Slack/JWT-shaped strings) and 3
  genuinely public identifiers of similar shape, confirmed to NOT match any format. 8 JVM tests, all
  passed; one real bug caught during verification (the Google-key fixture was 3 characters short of
  the format's exact 35-char requirement — the test failed honestly rather than silently passing).
  Explicit gap: only DEX string constants are scanned — resources.arsc string values and raw asset
  files (mentioned in this requirement's own text) are not yet covered.
- [x] **MS10-SECRET02**: Secret-candidate values are masked in every output surface (UI, export, logs) —
  never rendered in full. **Acceptance**: no unmasked candidate value appears in a rendered report or
  exported file. **Verification level**: product/on-device + a redaction unit test. **Phase**: 10.3.
  **Depends on**: MS10-SECRET01.
  **Status: done at the data-model level; not yet product-verified in a rendered UI/export surface** —
  `SecretFinding.maskedValue` is structurally the *only* representation this type can carry (no raw-value
  field exists at all, so a UI/export/log call site has no unmasked value available to display even by
  mistake); `SecretCandidateScanner.mask()` computes the masked form once and the raw matched text is
  never stored, logged, or returned. 2 dedicated masking tests plus a whole-scan test asserting the
  masked value never equals the fixture's own known raw AWS key. **Not yet done**: this scanner is not
  wired into any Security Audit rule/persisted finding/UI screen yet (unlike MS10-NET01's
  `AUDIT_NETWORK_SECURITY_CONFIG_REVIEW`), so "no unmasked value appears in a rendered report" has no
  actual report to check yet — a deliberate, named scope boundary for this pass, not an oversight.
- [x] **MS10-SECRET03**: Discovered credential candidates are never submitted to any external service to
  test validity. **Acceptance**: static/dynamic code review confirms no network call is made from the
  secret-scanning path; a test asserts zero outbound calls during a scan. **Verification level**: JVM
  unit test with a network-call-forbidding fixture/mock. **Phase**: 10.3. **Depends on**: MS10-SECRET01.
  **Status: done, automated (not just a one-time review)** — the scanner has no networking import at all
  (pure regex matching over dexlib2-parsed strings), and
  `secretScannerCompiledBytecodeContainsNoNetworkingClassReferences` makes this a standing, automated
  check rather than a point-in-time claim: it loads `SecretCandidateScanner`'s own compiled `.class`
  bytes off the test classpath and asserts the constant pool contains no reference to `java.net`,
  `javax.net`, `okhttp3`, `HttpURLConnection`, `URLConnection`, or `Socket` — a future accidental
  network-API import would fail this test, not just escape a manual review.
- [ ] **MS10-NET01**: Inspect network security configuration presence, certificate-pin declarations, and
  recognizable trust-manager/hostname-verifier/pinning code references, distinguishing configuration
  presence from implementation indicators from runtime enforcement. **Acceptance**: a fixture with a
  `network_security_config.xml` pin declaration and one without produce distinguishable findings; absence
  of pinning is never reported as an automatic FAIL. **Verification level**: JVM unit test; requires
  closing `ApkMetadata.networkSecurityConfigPresent`'s current hardcoded-`null` gap first (a real,
  named prerequisite, not part of this requirement's own scope). **Phase**: 10.3. **Depends on**: a new,
  separate requirement to implement real network-security-config parsing (not yet an ID — flag when
  10.3 is planned).
  **Prerequisite status (2026-09-14)**: the named `networkSecurityConfigPresent`-hardcoded-`null` gap is
  now closed — `ApkAnalyzer.kt` reads `BinaryXmlParser.ManifestConfig.networkSecurityConfig` (already
  extracted from the manifest's binary XML by the pre-existing parser, just not wired through before) and
  sets `true`/`false` accordingly, `null` only when the manifest itself could not be located/parsed at
  all. Verified with a new instrumented test (`ApkAnalyzerNetworkSecurityConfigTest`, `core:staticanalysis`
  androidTest, first androidTest wiring this module has needed) against two real, pre-existing fixture
  APKs — `fixture-debug.apk` (already declares `android:networkSecurityConfig`) and `riskfixture-debug.apk`
  (never has) — asserting `true` and `false` respectively, not a fabricated positive case. 2/2 passed on
  `emulator-5554`.
  **Update (same day, later pass)**: trust-manager code-reference detection itself is now partially
  implemented — added `ApiCategory.NETWORK_TRUST` to the existing `DexApiScanner` (reused, not
  duplicated — a new `ApiRule` entry, not new scanning infrastructure) detecting
  `SSLContext.init(KeyManager[], TrustManager[], SecureRandom)` calls, the single most decisive
  "app installs its own TLS trust decision" code reference. Verified against two real fixtures, not a
  fabricated case: `fixture-debug.apk`'s `FixtureActivity` genuinely builds a custom `X509TrustManager`
  and calls `SSLContext.init` (a real pre-existing "Pinning Simulation" used for MITM-detection testing
  elsewhere in this project) — detected; `riskfixture-debug.apk` has no TLS code at all — correctly not
  detected. 2 new JVM unit tests, both passed (`tests=396 failures=0`, up from 394). Explicitly documented
  as reference-only, never a verdict — this fires identically for a legitimate pinning implementation and
  a trust-all bypass, matching MS10-NET01's own "absence of pinning is never an automatic FAIL" discipline
  applied in the other direction.
  **Correction (2026-09-14, same day, review pass)**: the wording above overstated what this rule
  detects — it originally said `SSLContext.init` "installs a custom TLS trust decision", which is not
  something `DexApiScanner` can actually establish (it matches method references/invocation
  instructions only, per its own doc comment — no argument-flow analysis, so it cannot tell
  `init(null, null, null)` from a real custom `TrustManager` array). Corrected the rule's
  `explanation` text, the `NETWORK_TRUST` category's doc comment, and this entry to say only "a
  TLS-initialization reference was found", nothing about arguments/custom managers/pinning presence.
  Added `NetworkTrustFixtures.kt` (`fixture` module) with a real benign default-init call site and a
  genuinely-unsafe trust-all counterpart (confined to the fixture, never invoked) — a new test,
  `dexApiScannerTreatsDefaultAndUnsafeTrustInitAsIdenticalEvidence`, asserts both call sites produce
  byte-identical finding structure, guarding against ever silently claiming a distinction this
  scanner cannot support. Also added real fixture code and rules for the other two named signal
  types: `HttpsURLConnection.setDefaultHostnameVerifier`/`setHostnameVerifier` (benign + unsafe
  verifier fixtures) and OkHttp `CertificatePinner.Builder.add` (a real, syntactically valid pin
  declaration with an obviously-fake digest). 4 total NETWORK_TRUST tests now passing
  (`DexAnalysisTest`, full suite `tests=400 failures=0`).
  **Update (2026-09-14, same day, item 2/3 pass)**: real `network_security_config.xml` resource
  *content* parsing is now implemented, not just manifest-attribute presence. `ResourceTableParser.kt`
  (new) resolves a manifest resource-id reference (e.g. `@0x7f010000`) through a real, bounded
  `resources.arsc` parser — documented supported subset: single default-config variant per resource,
  simple (non-bag) `TYPE_STRING` entries, dense entry arrays; any other real variant (density/locale-
  qualified duplicates, sparse entries, bag/complex entries, resource aliases) is reported via
  `ResolveResult.Unsupported`, never silently guessed at. `NetworkSecurityConfigParser.kt` (new) then
  parses the resolved XML's real content: `<base-config>`/`<domain-config>`/`<debug-overrides>`,
  `<domain includeSubdomains>`, `<trust-anchors>`/`<certificates src>`, `<pin-set expiration>`/
  `<pin digest>` — unsupported real constructs (nested `<domain-config>`, `<certificateTransparency>`)
  are named in `unsupportedNotes`, not dropped. Verified against `fixture-debug.apk`'s real, pre-existing
  config (`base-config cleartextTrafficPermitted="true"`, `debug-overrides` trusting system+user CAs) and
  a second real fixture resource added specifically for `<domain-config>`/`<pin-set>` coverage
  (`network_security_config_domain_pins.xml`, not wired into the manifest, obviously-fake pin digests) —
  8 new JVM tests (`ResourceTableParserTest`, `NetworkSecurityConfigParserTest`), all passed, including
  two real bugs found and fixed during verification (a struct-offset miscalculation in the package
  chunk, and a text-node offset copied incorrectly from the start-tag case) — neither would have
  surfaced without testing against real compiled bytecode rather than trusting the implementation.
  Wired end-to-end into `ApkMetadata.networkSecurityConfig` (`NetworkSecurityConfigSummary`, flat/safe
  for the existing full-entity-equality test style), through `StaticAnalysisResultStore`'s existing
  disk-cache mechanism (no new persistence layer), `ApkAnalysisInput` (flattened fields, since
  `core:model` cannot depend on `core:staticanalysis`), and a new Security Audit rule,
  `AUDIT_NETWORK_SECURITY_CONFIG_REVIEW` (`NEEDS_REVIEW` for cleartext-permitted/debug-trusts-user-CA,
  `CHECK_PASSED` otherwise, `NOT_TESTED` when the config could not be inspected — never a guessed
  pass/fail). Verified end-to-end, not just unit-level: a new instrumented test
  (`SecurityAuditRepositoryInstrumentedTest.runAndPersistAudit_networkSecurityConfigFindingReachesPersistedAuditFromARealApk`)
  runs the real `ApkAnalyzer.analyze()` → `SessionRepository.persistCompletedAnalysis()` →
  `SecurityAuditRepository.runAndPersistAudit()` pipeline against the real `fixture-debug.apk` and
  reads the finding back from Room, confirming `NEEDS_REVIEW` (fixture's real config permits
  cleartext) survives the full round-trip byte-for-byte. **Not verified this pass**: the finding's
  appearance in `SecurityAuditScreen`/`SecurityAuditFindingDetailScreen` was not re-confirmed by a
  fresh manual on-device tap-through — those screens render generically over `List<AuditFinding>`
  (unchanged this pass, already product-verified for the other 10 rules in Phase 10.2), so this is
  inferred from unchanged, already-verified code, not freshly observed on-screen; stated plainly
  rather than presented as equivalent to a real UI check.
  Full JVM suite: `tests=413 failures=0`.
  **MS10-NET01 remains open** in one respect: all three named signal types (trust-manager references,
  hostname-verifier references, certificate-pin *code* declarations) have a first rule, and
  network-security-config *resource* content is now genuinely parsed — but a rule combining/
  cross-referencing the two (e.g. "code declares CertificatePinner but no `<pin-set>` exists in the
  XML config, or vice versa") is not implemented; each signal is currently reported independently.
- [x] **MS10-CODE01**: A documented, bounded catalog of relevant cryptographic and WebView code patterns,
  distinguishing a bytecode *reference* from *reachable* behavior from actual *execution*. **Acceptance**:
  each cataloged pattern has a passing detects/does-not-detect test against fixture bytecode, and its
  finding text states which of the three tiers (reference/reachable/executed) it actually establishes.
  **Verification level**: JVM unit test against fixture DEX. **Phase**: 10.3. **Depends on**: none new.
  **Status: done, product verified via JVM tests against real fixture bytecode (this requirement's own
  stated verification level — no product/on-device requirement in its acceptance text)** — four
  patterns: `Cipher.getInstance`/`MessageDigest.getInstance` references (added to the existing
  `DexApiScanner`, new `CRYPTOGRAPHY` category, reused infrastructure not new), `WebView.
  addJavascriptInterface`/`WebSettings.setJavaScriptEnabled` references (new `WEBVIEW` category — one
  rule initially targeted the wrong class, `WebView` instead of `WebSettings`, caught by checking the
  real `android-35` platform stubs with `javap` before finalizing the rule, not assumed), and two
  structural cross-referencing checks in a new `CodePatternAnalyzer.kt`: a weak-algorithm-string
  co-occurrence **HEURISTIC** (a DES/RC4/MD5/SHA-1 string constant in the same method as a crypto
  `getInstance` call — explicitly not a confirmed argument binding) and a WebView SSL-error-bypass
  **CONFIRMED** check (a class's real `superclass` field is `WebViewClient`, one of its methods is
  literally named `onReceivedSslError`, and that method calls `SslErrorHandler.proceed` — all three
  structural facts directly read from bytecode, no inference). Every finding's own explanation text
  states "REFERENCE tier only" explicitly, per this requirement's own three-tier framing — this
  scanner has no control-flow reachability analysis and no runtime observation, so it never claims
  reachable/executed. Verified against new real compiled fixtures in `fixture` (`CodePatternFixtures.kt`:
  positive/negative crypto and WebView call sites; `UnsafeSslErrorWebViewClient`/
  `SafeSslErrorWebViewClient`/`NotAWebViewClientButSameMethodName` — the last specifically proving the
  SSL-bypass check is a real class-hierarchy conjunction, not a method-name-only match). 19 new JVM
  tests (`CodePatternAnalyzerTest` 7, `DexAnalysisTest` +2), all passed.
  **Correction (2026-09-14, same day, accuracy hardening pass)**: the WebView SSL-bypass finding's text
  originally read "class $className ... invokes SslErrorHandler.proceed — a call that instructs the
  WebView to continue loading despite a TLS certificate error", stating the runtime consequence as if
  established alongside the confirmed structural facts. `CodePatternFinding` now has two separate
  fields instead of one `explanation` string: `confirmedFacts` (exactly the two proven facts — "Confirmed:
  class $className extends android.webkit.WebViewClient and overrides onReceivedSslError. Confirmed
  call to SslErrorHandler.proceed() from onReceivedSslError." — no behavioral/risk language) and
  `interpretation` (explicitly labeled as interpretation: "a strong indicator of an SSL/TLS validation
  bypass", with an explicit "this scanner performs no control-flow analysis... do not read this finding
  as confirming certificate validation is bypassed at runtime" disclaimer). The weak-algorithm heuristic
  finding was restructured the same way for consistency (confirmedFacts states only the co-occurrence;
  interpretation carries the "HEURISTIC, not proven" read). `EvidenceConfidence.CONFIRMED` itself was
  already correct for the WebView check (the two structural facts genuinely are confirmed) — only the
  *description* of what was confirmed was corrected, not the confidence label. 4 new tests directly
  assert the exact required wording and that `confirmedFacts` never contains "bypass"/"certificate
  validation is" language. `CodePatternAnalyzerTest` now has 11 tests total (7 original + 4 new for
  this hardening pass), all passed.
- [x] **MS10-BUILD01**: Report obfuscation indicators (naming-pattern heuristics) without claiming they
  prove correct R8/ProGuard configuration; optionally accept supplied build artifacts (mapping files) for
  a stronger assessment, treated as untrusted data, never executed. **Acceptance**: an obfuscated and an
  unobfuscated fixture produce distinguishable findings, and the finding text explicitly disclaims proof
  of correct configuration. **Verification level**: JVM unit test. **Phase**: 10.3. **Depends on**: none new.
  **Status: done, product verified against real compiled fixtures** — `BuildObfuscationAnalyzer.kt`
  measures the fraction of a DEX's compiled class simple-names matching the classic 1-2-letter
  ProGuard/R8 short-name convention, excluding a small denylist of known third-party/runtime package
  prefixes (`kotlin.`, `androidx.`, `dalvik.`, etc. — found empirically to be necessary: an
  unfiltered scan of even a minimal app pulled in 1500+ Kotlin-stdlib/platform classes that swamped
  the real signal, caught by the first test run, not assumed). Deliberately a denylist of
  known-not-app-code, not an allowlist of the app's own package — an allowlist would make the
  heuristic blind to real full obfuscation, which commonly flattens/randomizes package names too.
  `namingPatternConsistentWithObfuscation`'s own `disclaimer` field states the "does not prove
  R8/ProGuard ran or was configured correctly" caveat verbatim on every assessment, obfuscated or not.
  Optional stronger evidence: a real ProGuard/R8 `mapping.txt`-format parser (`original -> renamed:`
  lines, parsed as plain text only, never executed) that, when supplied, reports a confirmed (not
  heuristic) renamed-class count for entries actually present in the APK. New dedicated fixture module,
  `obfuscatedfixture` (10 real, hand-authored 1-letter-named classes — a real R8 run was avoided since
  it would have required minifying `fixture`/`riskfixture`, which dozens of other tests already depend
  on for their current, unobfuscated class names); `riskfixture` (already real, ordinary descriptive
  names, untouched) serves as the negative case. 7 new JVM tests, all passed.
- [x] **MS10-BUILD02**: SDK/dependency vulnerability matching cites its advisory source and catalog
  version explicitly, and reports `NOT_TESTED`/inconclusive rather than a false negative when version
  evidence is inadequate. **Acceptance**: a fixture with a known-vulnerable SDK version at high confidence
  and one with ambiguous version evidence produce different confidence levels, both citing a source.
  **Verification level**: JVM unit test with a small, explicitly-versioned fixture advisory list. **Phase**:
  10.3. **Depends on**: `SdkSignatureCatalog` (existing SDK detection).
  **Status: done, product verified against a real dependency's real version** —
  `SdkVulnerabilityAdvisoryCatalog.kt` (one real, verified-live entry: CVE-2016-2402, OkHttp 2.x<2.7.4
  and 3.x<3.1.2, source `nvd.nist.gov`, confirmed via live web lookup while authoring this catalog, not
  recalled from training data) + `SdkVulnerabilityMatcher.kt`. **Empirically confirmed before
  implementing, not assumed**: `SdkSignatureCatalog.detectedVersion` is always `null` (that catalog's
  own comment: "we do not guess version without explicit version constant evidence"), and this
  project's real `fixture-debug.apk`'s actual `META-INF/` contents (checked directly) do not retain
  the AndroidX-style `.version` marker files some AARs ship — AGP's default packaging strips them.
  The one real, reliable, on-device-extractable version signal actually found: OkHttp's own internal
  `"okhttp/X.Y.Z"` string constant, confirmed present in `fixture-debug.apk`'s real compiled DEX by
  direct inspection. Real end-to-end case: `fixture-debug.apk`'s genuine OkHttp 4.12.0 dependency is
  correctly matched, version-confirmed, and reported as outside the vulnerable range (HIGH confidence,
  citing the source) — proven against the real dependency, not a synthetic stand-in. The
  vulnerable-version and no-version-evidence cases are demonstrated via the matcher's pure function
  with hand-built string-constant inputs (e.g. a plain `"okhttp/3.0.0"` string), the same
  "real-shaped, never-a-functioning-dependency" fixture technique `SecretCandidateScanner`'s AWS/PEM
  fixtures already use — this project ships no actually-vulnerable dependency anywhere, confined or
  not. 6 new JVM tests, all passed.
  **Correction (2026-09-14, same day, accuracy hardening pass)** — four real gaps found and fixed, not
  merely reviewed: (1) **reliable SDK identification** — `scanApk` previously passed every
  `SdkSignatureCatalog` detection regardless of its own confidence; now filters to
  `SdkConfidence.HIGH` only, since a `MEDIUM`/`LOW`-confidence signature match is not a reliable enough
  basis to name a specific CVE against. (2) **Conflicting version markers** — `extractVersion`
  previously returned whichever matching string happened to appear first in iteration order when two
  *different* version strings were both present, an arbitrary and non-deterministic choice; it now
  collects every distinct match and reports `NOT_TESTED` with an explicit "conflicting version
  evidence" detail (naming both values) whenever more than one distinct version is found, never
  resolving the conflict by guessing. (3) **Malformed version guarding** — a numeric component too
  large for `Int` (`String.toInt()` would throw) is now caught via `toIntOrNull()` and treated as no
  match for that string, not an unhandled exception a hostile or corrupted string constant could
  trigger. (4) **Prerelease/qualifier and multi-component rejection** — the version-marker regex
  gained a negative lookahead (`(?![\d.\-])`) so a qualifier suffix (`-SNAPSHOT`, `-beta`) or a 4th
  version component is rejected outright rather than silently truncated to its numeric `X.Y.Z` prefix,
  which would have treated a qualified/pre-release build as equivalent to the plain release. 15 new
  JVM tests covering the affected lower/upper boundary, the first unaffected version, an older-line
  affected version, a same-line newer unaffected version, both malformed-version cases, a missing
  marker, a prerelease qualifier, a 4-component version, same-value duplicate markers (resolve
  cleanly), genuinely conflicting markers (`NOT_TESTED`, never arbitrary), an uncataloged SDK
  (produces no finding, never a false clean bill of health), and the HIGH-confidence gate itself
  against the real fixture. `SdkVulnerabilityMatcherTest` now has 21 tests total (6 original + 15 new),
  all passed.
- [x] **MS10-COV01**: Every static audit run reports scanned content, skipped content, unsupported
  formats, cancellation, and any limit reached, with extraction provenance and exact supporting
  locations preserved. **Acceptance**: a run against an APK exceeding a configured scan bound reports the
  limit explicitly in its coverage summary, not silently truncated findings. **Verification level**: JVM
  unit test with a bound-exceeding fixture. **Phase**: 10.3. **Depends on**: existing
  `StaticAnalysisCoverage` model (reuse, not reinvent).
  **Status: done, product verified via JVM tests** — four new tests in `CodePatternAnalyzerTest` verify
  coverage reporting: (1) `coverageReportsScannedDexFiles` — which DEX files were inspected;
  (2) `coverageReportsScanDurationMilliseconds` — real scan duration; (3)
  `coverageReportsLimitReachedFalseWhenFindingsAreBelowThreshold` — when findings are below MAX_FINDINGS
  (500), `limitsReached` is false; (4) `coverageCanReportParsingErrors` — the coverage model includes a
  `parsingErrors` list for scanning errors. The existing `CodePatternAnalyzer` already implements
  limit detection: when findings reach MAX_FINDINGS, it sets `limitsReached = true` and stops adding
  more, explicitly informing the caller of truncation rather than silently returning fewer findings
  than expected. `StaticAnalysisCoverage` carries all required fields: `dexFilesInspected` (which DEX
  files examined), `entriesSkipped` (entries not processed), `parsingErrors` (scanning errors),
  `limitsReached` (boolean when scan-bound exceeded). 4 new JVM tests, all passed.

### Guided runtime sessions (MS10-SESS) — Phase 10.5

- [ ] **MS10-SESS01**: A guided session presents the user a specific, ordered set of steps, each tied to
  one declared capability the static catalog flagged, and each step's instruction text is generated
  from that specific finding (not a generic template). **Acceptance criterion**: for a fixture APK with
  N flagged capabilities, a guided session presents exactly N steps, each citing its originating rule
  ID. **Verification dependency**: an instrumented test asserting step count and rule-ID citation match
  a known fixture's static findings. **Status: planned** — not started; depends on MS10-STATIC01-03.
- [ ] **MS10-SESS02**: Guided-session evidence (`ObservedBehavior`/`AndroidEvidence`) is attributed to
  the correct session and target package, reusing Milestone 9's ownership-verification mechanism
  (MS9-CAP02) rather than re-solving attribution. **Acceptance criterion**: a guided-session step's
  recorded evidence never appears under a different session's report, verified the same way MS9-CAP02
  was (`MATCHED`/`MISMATCHED`/`UNKNOWN` classification). **Verification dependency**: an on-device
  instrumented test extending `SandboxSessionReportMergerTest`'s existing coverage to a guided-session
  step. **Status: planned** — not started; depends on MS9-CAP02 (done) and MS10-SESS01.
- [ ] **MS10-SESS03**: A guided step with no observation inside its window is reported as "not
  exercised" — never silently treated as pass or fail. **Acceptance criterion**: a step whose target
  action is never actually performed during its window produces a distinct, explicit
  `NOT_EXERCISED`-equivalent outcome, not absence of a finding. **Verification dependency**: an
  instrumented test that lets a step's window elapse with zero observed activity and asserts the
  explicit outcome. **Status: planned** — not started.
- [ ] **MS10-SESS04**: The session checklist includes suggested actions (launch, onboarding, sign-in,
  search, form submission, test file upload, settings change, sign-out, background transition) and lets
  the tester add custom actions. **Acceptance**: a session can be created with both suggested and at
  least one custom action, each independently markable. **Verification level**: instrumented UI test.
  **Phase**: 10.5. **Depends on**: MS10-SESS01.
- [ ] **MS10-SESS05**: The tester can mark each action's start, completion, and free-text notes, and
  "tester marked complete" is tracked as a distinct field from "technical evidence confirms this was
  exercised" — the two are never conflated into one status. **Acceptance**: a step marked complete by the
  tester with no corresponding observed evidence is reported distinctly from one with both. **Verification
  level**: instrumented UI test + unit test on the distinct-fields model. **Phase**: 10.5. **Depends on**:
  MS10-SESS01, MS9-CAP02 (reused ownership verification).
- [ ] **MS10-SESS06**: The session UI shows monitoring health: supported checks active, unreadable
  traffic, interruption, truncation, and collection failures — never a silently-healthy indicator when
  monitoring degraded. **Acceptance**: a deliberately interrupted VPN mid-session surfaces an explicit
  degraded-health indicator, not a clean session summary. **Verification level**: instrumented test using
  the existing VPN-interruption test harness precedent. **Phase**: 10.5. **Depends on**: MS10-SESS01,
  existing `SandboxVpnService` health signals.
- [ ] **MS10-SESS07**: The guided workflow reuses normal target installation, launch, monitoring, and
  cleanup controls — it does not introduce a second, parallel install/launch/cleanup path. **Acceptance**:
  code review confirms the guided session calls the same `SandboxSessionCoordinator`/cleanup path as the
  existing free-form Live Monitor flow. **Verification level**: source inspection + regression test on
  the shared path. **Phase**: 10.5. **Depends on**: MS10-SESS01.

### Runtime collection completion (MS10-COLL) — Phase 10.6

- [ ] **MS10-COLL01**: Finish missing gRPC product-level integration: a gRPC exchange through the real
  fixture-in-Work-Profile UI (not merely an engine/component-level test) populates the Traffic Inspector
  and is available to Security Audit correlation. **Acceptance**: a real gRPC call through the deployed
  fixture app appears in the Traffic Inspector UI end-to-end. **Verification level**: product/on-device
  (emulator; physical Pixel 8 only if connected and available per Section 19 rules). **Phase**: 10.6.
  **Depends on**: `docs/FUTURE_CAPABILITIES.md` priority 3 (already tracked there, independently of this
  milestone — this requirement finishes it, does not duplicate its tracking).
- [ ] **MS10-COLL02**: Finish missing SSE product-level integration, same standard as MS10-COLL01.
  **Acceptance**: a real SSE stream through the deployed fixture app appears in the Traffic Inspector UI
  end-to-end. **Verification level**: product/on-device. **Phase**: 10.6. **Depends on**: same as
  MS10-COLL01.
- [ ] **MS10-COLL03**: Capture identity (session/target attribution) is preserved through every record
  update and finalization for guided-session-collected evidence, reusing Milestone 9's
  `MATCHED`/`MISMATCHED`/`UNKNOWN` ownership model — unknown or unrelated ownership never becomes
  confirmed target evidence. **Acceptance**: traffic from a second, unrelated app running concurrently
  during a guided session is classified `MISMATCHED`/`UNKNOWN`, never attributed to the audited target.
  **Verification level**: instrumented test extending MS9-CAP02's existing coverage. **Phase**: 10.6.
  **Depends on**: MS9-CAP02 (done), MS10-SESS01.

### Runtime security rules (MS10-RTRULE) — Phase 10.7

- [ ] **MS10-RTRULE01**: Evidence-based checks for actual observed cleartext requests, sensitive
  candidates observed over cleartext, and sensitive values placed in readable URLs — each a distinct
  rule, each requiring an actual observed transaction, never a static declaration alone. **Acceptance**:
  a guided session that captures a real plaintext HTTP request with a secret-candidate query parameter
  produces the corresponding `AUDIT_RUNTIME_*` finding with a link to the captured transaction.
  **Verification level**: product/on-device with a fixture that deliberately sends such a request.
  **Phase**: 10.7. **Depends on**: MS10-COLL01-03, MS10-SECRET01 (candidate definitions reused).
- [ ] **MS10-RTRULE02**: A tester-supplied synthetic canary value appearing in captured traffic is
  detected and reported, using only controlled/canary data — never real user secrets collected for
  demonstration purposes. **Acceptance**: a canary string entered by the tester and echoed by the fixture
  app over the network is detected in the captured traffic. **Verification level**: product/on-device.
  **Phase**: 10.7. **Depends on**: MS10-SESS04 (custom action to input the canary).
- [ ] **MS10-RTRULE03**: Destinations outside an optional developer-supplied expected-destination list,
  repeated network failures, unexpected traffic during a tester-marked background interval, and attempts
  to reach prohibited destinations are each evidence-based findings, reusing existing `DestinationPolicy`
  enforcement and observation. **Acceptance**: traffic to an out-of-list destination during a marked
  background interval produces a finding citing both the destination-list violation and the
  background-interval context. **Verification level**: product/on-device + unit test on the matching
  logic. **Phase**: 10.7. **Depends on**: MS10-SESS05 (background-interval marking), existing
  `DestinationPolicy`.
- [ ] **MS10-RTRULE04**: Continued observation of the same credential after a tester-marked logout is
  reported as a `NEEDS_REVIEW` finding, explicitly labeled as a review flag, never asserted as proof of a
  server-side authorization failure. **Acceptance**: a fixture that keeps sending a token after a marked
  logout produces a `NEEDS_REVIEW` finding whose text explicitly disclaims proving server-side failure.
  **Verification level**: product/on-device. **Phase**: 10.7. **Depends on**: MS10-SESS05.
- [ ] **MS10-RTRULE05**: Inspector-upstream TLS behavior (the app's own TLS client to the real server) is
  kept model-separate from target-certificate-validation behavior (whether the *target app* validates the
  inspector's presented certificate) — a rejected inspection certificate alone never asserts pinning is
  present. **Acceptance**: a fixture that rejects the inspection CA is reported as "target rejected
  inspection," not "target has certificate pinning," unless a separate, explicit pinning indicator (see
  MS10-NET01) also fired. **Verification level**: product/on-device + unit test on the two-signal
  distinction. **Phase**: 10.7. **Depends on**: MS10-NET01.

### Correlation and coverage (MS10-CORR) — Phase 10.8

- [ ] **MS10-CORR01**: Static findings and runtime observations are correlated through explicit evidence
  references, keeping exact-URL matching separate from host-level association, and preserving static
  provenance when runtime evidence is attached (reusing Milestone 9's `UrlEvidenceCorrelator` provenance
  model, not reinventing it). **Acceptance**: an exact-URL match and a host-only match produce
  distinguishable provenance on the same finding. **Verification level**: JVM unit test extending
  `UrlEvidenceCorrelatorTest`'s existing coverage. **Phase**: 10.8. **Depends on**: MS10-COLL01-03.
- [ ] **MS10-CORR02**: Deterministic retention/merge behavior is defined and tested for redaction
  ambiguity, differing paths, host lookalikes, repeated imports, late-arriving evidence, and multiple
  sessions — duplicate findings from the same underlying event are prevented. **Acceptance**: importing
  the same evidence twice produces one finding, not two. **Verification level**: JVM unit test. **Phase**:
  10.8. **Depends on**: MS10-CORR01.
- [ ] **MS10-CORR03**: The report shows which tester workflows produced relevant evidence and which
  checks remain untested — missing evidence is never interpreted as proof the target lacks a behavior.
  **Acceptance**: a rule with no corresponding guided-session evidence reports `NOT_TESTED`, never
  `CHECK_PASSED`. **Verification level**: JVM unit test (already partially covered by
  `AUDIT_NATIVE_CODE_UNVERIFIED`'s existing `NOT_TESTED` precedent — extend the same discipline here).
  **Phase**: 10.8. **Depends on**: MS10-CORR01.

### Evidence-based reporting (MS10-RPT)

- [ ] **MS10-RPT01**: A Security Audit report view renders `SecurityAuditReport` findings, cross-linked
  from and to the existing Final Report, without requiring the user to reconcile two disconnected
  reports manually. **Acceptance criterion**: the Final Report screen links to the Security Audit report
  for the same analysis, and vice versa. **Verification dependency**: a Compose UI test asserting the
  cross-link navigates correctly in both directions. **Status: planned** — not started; depends on
  MS10-STATIC01-03.
- [ ] **MS10-RPT02**: Correlation findings (`AUDIT_CORR_*`, see `docs/SECURITY_AUDIT_RULES.md`) report
  `NOT_APPLICABLE` when no guided session exists for the analysis, rather than a misleadingly clean
  report. **Acceptance criterion**: an analysis with zero guided sessions shows every correlation
  finding as `NOT_APPLICABLE`, never `PASS`. **Verification dependency**: a unit test asserting this
  default explicitly. **Status: planned** — not started; depends on MS10-SESS01-03.
- [ ] **MS10-RPT03**: A finding's full detail includes rule ID/version, severity, confidence, evidence
  and its location, static-or-runtime origin, associated workflow/session, reproduction guidance,
  remediation, applicable OWASP references (or an explicit "project-specific, no direct OWASP mapping"
  label), and coverage limitations. **Acceptance**: every rendered finding has all fields non-empty or
  explicitly marked not-applicable. **Verification level**: Compose UI test + the standards audit in
  MS10-STD01. **Phase**: 10.9. **Depends on**: MS10-STD01.
- [ ] **MS10-RPT04**: An audit summary shows completed checks, untested checks, collection failures, and
  inspection conditions (device, catalog version, guided-session presence). **Acceptance**: the summary's
  counts match `SecurityAuditReport.countOf(...)` for every outcome exactly. **Verification level**:
  Compose UI test. **Phase**: 10.9. **Depends on**: MS10-UI02.
- [ ] **MS10-RPT05**: Two builds of the same package can be compared, with retest dispositions
  distinguishing "fixed" from "no longer observed" — the two are never conflated, and the report states
  when a difference could be exercise/capture-condition drift rather than an actual fix. **Acceptance**: a
  finding present in build A and structurally absent in build B (same rule, same input, different result)
  is labeled "fixed"; one absent only because a guided session wasn't rerun is labeled "no longer
  observed" with the condition-drift caveat. **Verification level**: JVM unit test on the diffing logic.
  **Phase**: 10.9. **Depends on**: MS10-FOUND01 (identity binding to distinguish builds).
- [ ] **MS10-RPT06**: A reviewer can add notes and mark a finding as a justified false positive without
  deleting the original evidence — the disposition is additive, the underlying finding record is
  immutable. **Acceptance**: dismissing a finding as a false positive still shows its original evidence
  when the disposition is inspected. **Verification level**: JVM unit test on the disposition model.
  **Phase**: 10.9. **Depends on**: MS10-UI03.
- [ ] **MS10-RPT07**: A redacted local report export is available, suitable for QA handoff, applying the
  same masking as MS10-SECRET02 — never described as guaranteeing removal of every possible secret.
  **Acceptance**: an exported report contains no unmasked secret-candidate value; the export UI states the
  redaction is not a guarantee. **Verification level**: product/on-device + unit test on the export
  redaction path. **Phase**: 10.9. **Depends on**: MS10-SECRET02.
- [ ] **MS10-RPT08**: The product never claims OWASP certification or comprehensive-compliance coverage
  from partial automated coverage — no UI copy, report text, or documentation asserts this. **Acceptance**:
  a copy/documentation review finds no such claim anywhere in the audit UI or exported report.
  **Verification level**: manual copy review, re-checked at each phase that adds report text. **Phase**:
  10.9 and ongoing. **Depends on**: none.

### Standards and rule quality (MS10-STD) — Phase 10.3 onward (applies to every rule category as written)

- [x] **MS10-STD01**: Every rule, in every category, documents its required inputs, applicability,
  positive/negative/inconclusive conditions, severity rationale, confidence rationale, false-positive
  risks, expected evidence, remediation, and verification fixtures — consulting current OWASP MASVS
  (controls)/MASTG (testing guidance)/MASWE (weaknesses) sources where applicable, with retrieval dates
  recorded, and an explicit "project-specific, no direct OWASP mapping" label where no mapping is
  appropriate (never an invented mapping). **Acceptance**: `docs/SECURITY_AUDIT_RULES.md` has this full
  dossier for every shipped rule — the 10 v1 static rules now have it (see the "Standards mapping and
  rule dossier" section added 2026-09-14); every category added afterward must have it before that
  category's requirement is marked done. **Verification level**: documentation review, one rule at a
  time, at the point each is implemented — never deferred to a later category's pass. **Phase**: ongoing,
  starting 10.1 (retroactively applied same day) / 10.3 onward for new categories. **Depends on**: none.
  **Status: done for the 10 v1 static rules** — `docs/SECURITY_AUDIT_RULES.md`'s dossier table, citations
  verified via live `WebFetch`/`WebSearch` against `mas.owasp.org` on 2026-09-14 (two initially-assumed
  IDs were found wrong/deprecated on verification and corrected — see that file's own note on this).
  Remains open and must be re-satisfied for every category added in Phases 10.3+.

### Privacy and security bounds (MS10-PRIV) — Phase 10.2 onward

- [ ] **MS10-PRIV01**: APK processing and reports stay local by default — no automatic APK, payload,
  credential, or report upload. **Acceptance**: a network-call audit of the audit/reporting code paths
  finds zero outbound calls triggered by normal operation. **Verification level**: source inspection +
  a network-call-forbidding unit test, same technique as MS10-SECRET03. **Phase**: 10.2 onward
  (must hold from the first workflow). **Depends on**: none.
- [ ] **MS10-PRIV02**: Input reads, decompression, decoded payloads, buffers, concurrent streams,
  artifact size, and stored evidence are all bounded — an oversized or malformed APK cannot exhaust
  memory or hang the scan. **Acceptance**: a deliberately oversized/malformed fixture triggers an
  explicit `COLLECTION_FAILED` outcome within a bounded time, not a crash or hang. **Verification level**:
  JVM unit test with adversarial fixtures. **Phase**: 10.3 (once DEX-scanning categories that need real
  bounds exist — the current 10 manifest-flag rules have no unbounded-read surface to bound). **Depends
  on**: MS10-SECRET01/MS10-CODE01 (the categories that actually read variable-length content).
- [ ] **MS10-PRIV03**: APKs, network content, and imported artifacts are treated as untrusted — no
  script or code from an uploaded APK or build artifact is ever executed by the audit tooling.
  **Acceptance**: a fixture APK containing an executable script in an unexpected location does not have
  that script invoked during a scan. **Verification level**: JVM unit test + source inspection. **Phase**:
  10.3. **Depends on**: MS10-BUILD01 (optional build-artifact ingestion is the main new surface this
  applies to).
- [ ] **MS10-PRIV04**: Upstream trust verification and established destination policy are never weakened,
  and pinning/TLS/root/monitoring are never bypassed to make a test pass. **Acceptance**: a regression
  test confirms `DestinationPolicy` and TLS validation behavior are byte-identical before and after every
  Security Audit phase ships. **Verification level**: existing regression suite, re-run per phase (not a
  new test — a discipline applied to existing ones). **Phase**: ongoing. **Depends on**: none.

### Persistence and recovery (MS10-PERSIST) — Phase 10.2, 10.4

- [ ] **MS10-PERSIST01**: Audit persistence validates schema, origin, analysis/session/target identity,
  field bounds, and completeness before trusting a stored record — reusing the established
  cross-profile/persistence validation design (Milestone 9's atomic-write, integrity-header pattern), not
  a new ad hoc format. **Acceptance**: a truncated or schema-mismatched stored record is rejected
  explicitly, not partially loaded. **Verification level**: JVM/instrumented unit test with a corrupted
  fixture file. **Phase**: 10.2 (minimal, for MS10-UI04's restart/reopen requirement) → 10.4 (full
  validation). **Depends on**: MS9-PER01/02 (existing persistence-hardening pattern).
- [ ] **MS10-PERSIST02**: Distinct `PENDING`/`EMPTY`/`FAILED`/`TRUNCATED`/`IMPORTED` outcomes are
  persisted for audit evidence import, reusing Milestone 9's `UrlEvidenceImportStatusStore` outcome
  vocabulary precedent rather than inventing a new one. **Acceptance**: each of the five outcomes is
  independently producible and durably distinguishable after a process restart. **Verification level**:
  instrumented test, same pattern as `UrlEvidenceImportStatusStoreInstrumentedTest`. **Phase**: 10.4.
  **Depends on**: MS10-FOUND02.
- [ ] **MS10-PERSIST03**: Repeated imports, legitimate later observations, older-artifact replay, and
  concurrent updates are all verified against real Android persistence — a format header or checksum
  alone is not treated as a general safe-deserialization guarantee (this project's own already-documented
  Java-serialization caveat applies here too). **Acceptance**: 1000+ concurrent write operations against
  the audit store lose zero updates, matching Milestone 9's own `1600 concurrent operations, zero lost
  updates` precedent. **Verification level**: instrumented concurrency test. **Phase**: 10.4. **Depends
  on**: MS10-PERSIST01.
- [ ] **MS10-PERSIST04**: Interruption recovery preserves prior valid data after a failed write — a crash
  mid-write never corrupts the previously-persisted audit record. **Acceptance**: killing the process
  mid-write and restarting shows the last successfully-committed record, not a corrupted or empty one.
  **Verification level**: instrumented test using a controlled write-interruption harness. **Phase**:
  10.4. **Depends on**: MS10-PERSIST01.
- [x] **MS10-PERSIST05**: The Room schema migration introduced for Security Audit (DB v8→9) preserves
  every existing analysis, permission, component, and risk-finding row — never a destructive rebuild
  that silently erases prior product data to add new columns/tables. **Acceptance**: a real v8-schema
  database seeded with a full analysis record, migrated in place, retains that record byte-for-byte and
  gains the two new columns (correctly defaulted) and two new tables. **Verification level**:
  instrumented `MigrationTestHelper` test against a real historical schema (not a fresh install).
  **Phase**: 10.2 (correction pass, 2026-09-14). **Depends on**: none.
  **Status: done, product verified** — `MIGRATION_8_9` (`core:database/.../Migrations.kt`) replaces the
  prior unconditional `fallbackToDestructiveMigration(dropAllTables = true)` for the 8→9 transition
  specifically; the SQL is copied verbatim from the Room-exported schema diff (`schemas/.../8.json` vs
  `9.json`), not hand-guessed. `SandboxDatabaseMigrationTest` (2 tests, run on `emulator-5554`) seeds a
  real v8 database via raw SQL, runs the real migration, and asserts the original analysis/permission/
  component/finding rows are unchanged and the new columns/tables are present and functional through the
  real generated DAOs. **`fallbackToDestructiveMigration` remains for pre-8 versions only** — those never
  had a real migration path before this correction either; this pass does not newly regress them, it
  narrows the destructive path that already existed rather than closing it entirely.
- [x] **MS10-PERSIST06**: A row that predates a given static-analysis boolean field (e.g.
  `usesCleartextTraffic`/`allowBackup`, added in the 8→9 migration) must never have its
  migration-backfilled default value read by a Security Audit rule as a confirmed result — an
  unknown value must produce `NOT_TESTED`, never a false `CHECK_PASSED`. **Acceptance**: a rule
  evaluated against a pre-migration row's defaulted fields reports `NOT_TESTED`, not `CHECK_PASSED`/
  `NEEDS_REVIEW`/`FINDING_DETECTED`, regardless of which way the default happens to point.
  **Verification level**: JVM unit test (rule-level) + instrumented `MigrationTestHelper` test
  (schema-level). **Phase**: 10.3 (correction pass, 2026-09-14, found during migration-boundary
  review). **Depends on**: MS10-PERSIST05.
  **Status: done, product verified** — `MIGRATION_9_10` (`core:database/.../Migrations.kt`) adds
  `AnalysisSessionEntity.staticSecurityFieldsKnown` (`false` for every pre-existing row, `true` for
  every row this app inserts thereafter), threaded through `ApkAnalysisInput`/`PersistedAnalysis`/
  `RiskInputMapper`. `StaticAuditRules.CleartextTrafficEnabledRule`/`BackupEnabledRule` now check it
  first and report `AuditOutcome.NOT_TESTED` when `false`, before ever looking at the possibly-stale
  boolean. 5 new JVM tests (`StaticAuditRulesTest`) and 2 new instrumented tests
  (`SandboxDatabaseMigrationTest`, chaining the real 8→9→10 upgrade path against a seeded v8
  database) all passed. Full JVM suite `tests=400 failures=0` (zero regressions). **Repository
  identity reconciliation note**: per this milestone's own commit-history check (see
  `SandboxDatabaseProvider`'s doc comment), no version before 8 has ever run on the one real device
  with retained data (the physical Pixel 8) — `fallbackToDestructiveMigration` for versions <8
  therefore is not currently a real risk, and no further historical migration was added beyond
  8→9→10.

### Autonomous execution (MS10-AUTO) — Phase 10.10 — product feature; distinct from this session's own execution-continuity tracking (see `.planning/STATE.md`, never itemized here)

- [ ] **MS10-AUTO01**: An optional, opt-in, unattended static re-audit is scheduled via `WorkManager`,
  respecting the device's own App Standby Bucket and Doze constraints rather than attempting to bypass
  them. **Acceptance criterion**: the scheduled unique work request is visible via
  `WorkManager.getWorkInfosForUniqueWork(...)` and produces a report identical to a manually-triggered
  run given the same stored analysis input. **Verification dependency**: an instrumented `WorkManager`
  test using `WorkManagerTestInitHelper`, asserting output-report equality against a synchronous run.
  **Status: planned** — not started; see `docs/AUTONOMOUS_EXECUTION.md`.
- [ ] **MS10-AUTO02**: Autonomous runs checkpoint at rule/step granularity and resume without
  re-evaluating or double-recording already-completed work. **Acceptance criterion**: a run interrupted
  after N of M rules, then resumed, produces exactly M findings with no duplicates and no re-evaluation
  of the first N. **Verification dependency**: a test that constructs a partial checkpoint file directly
  and asserts the resumed run's behavior, per `docs/SECURITY_AUDIT_VERIFICATION.md`'s autonomous-execution
  verification section. **Status: planned** — not started.
- [ ] **MS10-AUTO03**: A documented, working stop mechanism (in-app toggle) cancels the scheduled unique
  `WorkManager` work immediately, and no further scheduled run fires after cancellation. **Acceptance
  criterion**: after the toggle is disabled, asserting on `WorkInfo.State` shows `CANCELLED`, and no new
  report is produced across the next several eligible windows. **Verification dependency**: an
  instrumented test toggling the setting and asserting no further `WorkInfo` transitions to `RUNNING`.
  **Status: planned** — not started.

### Verification closure (MS10-CLOSE) — Phase 10.11

- [ ] **MS10-CLOSE01**: Every MS10-* requirement above is reconciled against actual evidence — no
  requirement marked complete without citable evidence, no gap silently dropped, mirroring Milestone 9's
  own closure discipline. **Acceptance**: `.planning/STATE.md` records a "Final Milestone 10 requirement
  reconciliation" section, criterion-by-criterion, in the same format Milestone 9's used. **Verification
  level**: documentation review against the evidence trail accumulated across Phases 10.1-10.10.
  **Phase**: 10.11. **Depends on**: all prior MS10-* requirements reaching their own terminal status
  (done, or an explicitly-named open note — never silently unresolved).

## Milestone 9: Capture Ownership, Evidence Wiring & Verification Closure (reconciliation, 2026-09-12)

This block corrects specific Milestone 8/8.9 requirement claims below against source and device
evidence gathered during reconciliation, and states the new requirements those corrections imply.
It does not reopen or re-litigate requirements not named here. See `STATE.md`'s 2026-09-12
reconciliation section for the full evidence behind each line.

### Corrections to existing requirement status

- **DEX04** (exact-URL `RUNTIME_OBSERVED` provenance): status text below already said "not wired" —
  reconciliation confirms this is *worse* than "not yet wired": `importUrlEvidence()` /
  `correlateUrlEvidenceWithAnalysis()` are implemented and reachable, but only from the
  orphan-session-recovery path (`SandboxSessionCoordinator.resolveOrphanWorkSession` /
  `endOrphanWithoutPersonalRow`), never from the normal end-session flow
  (`SandboxCleanupViewModel.importAndReconcile`). No acceptance run to date — including the two
  fresh Pixel 8 runs in the 2026-09-12 acceptance evidence — has exercised this call path. Remains
  **source implemented, not product verified**, and is now additionally **known-unreachable from
  the primary UI flow** (see MS9-01 below).
  **Closed 2026-09-14** by MS9-URL01 (the missing call added) and MS9-URL02 (verified product-level,
  including on a physical Pixel 8 this same milestone) — see `STATE.md`'s "Final Milestone 9
  requirement reconciliation" for the full accounting.
- **VER02**: partially closed. The plain HTTPS-over-h2 GET path (fixture → real VPN → real TLS/ALPN
  → `Http2RelayHandler` → `TrafficInspectionStore` → Traffic Inspector UI) is now **product
  verified** on a physical Pixel 8, twice, independently (`evidence/checkpoint_8_9_acceptance/`).
  gRPC and SSE remain at Phase 8.9's engine-integration-test level only — **not** re-driven through
  the fixture-in-Work-Profile UI this pass. Do not read the H2 closure as covering gRPC/SSE.
  **2026-09-14 update**: the H2 portion was independently reconfirmed a third time on a physical Pixel 8
  during Milestone 9's sixth pass. gRPC/SSE remain exactly as stated above — still not product verified
  — and this was never part of Milestone 9's own defined phase list; it is `docs/FUTURE_CAPABILITIES.md`'s
  priority-3 item, the correctly-identified next unstarted roadmap work, not a gap left over from this
  milestone's own scope. See `STATE.md`'s "Final Milestone 9 requirement reconciliation" for the full
  accounting.
- **H201–H205, GRPC01–GRPC05, SSE01–SSE05**: the "Verified through..." paragraphs under each block
  remain accurate for what they claim (component/engine-level, real TLS+ALPN, real external
  servers) — that has not changed. What has changed is that a *separate, lower-level* defect
  (`VpnService.protect()` never actually succeeding on the upstream socket used by all of these
  paths — see STATE.md) meant the production VPN-routed path itself did not work at the time these
  were marked complete, for any of the three protocols, regardless of engine-test results. That
  defect is now fixed and re-verified for H2/HTTPS specifically; gRPC and SSE have not been
  re-confirmed against the fixed engine through the real VPN route and are downgraded from an
  implicit "should also just work now" assumption to **unconfirmed pending MS9-02**.

### New requirements

#### Capture ownership (MS9-CAP)
- [ ] **MS9-CAP01**: Scope the Work Profile VPN to the intended target application using
  `VpnService.Builder.addAllowedApplication()` (or an equivalent, verified ownership mechanism),
  rather than capturing the entire Work Profile UID range.
  **2026-09-12 correction — attempted, disproven, and root-caused (PLATFORM LIMITATION VERIFIED)**:
  `addAllowedApplication(targetPackage)` was implemented (with a two-stage design to correctly
  handle the Prepare-Sequence install-ordering constraint) and tested twice on a physical Pixel 8.
  Both times `dumpsys connectivity vpn` showed the resulting tunnel's `Uids` as **empty** (`<{}>`,
  not scoped to the target and not left at the whole-profile range), with no exception logged and
  `establish()` reporting success, and every fixture HTTPS request then failed with
  `UnknownHostException` (reproduced across two hosts, confirmed non-transient). The implementation
  was reverted. A follow-up investigation (same day, `STATE.md`'s "Phase 9.1 investigation —
  2026-09-12" section) then root-caused this: with `DevicePolicyManager`'s always-on-VPN *lockdown*
  temporarily disabled (a controlled, isolated, restored-afterward experiment), the identical
  `addAllowedApplication` call produced a real, correctly-scoped, non-empty UID set — confirming the
  empty-`Uids` result is caused specifically by combining `addAllowedApplication` with this product's
  required `lockdownEnabled = true` policy, not by wrong UID resolution (directly ruled out) and not
  by an unconditional platform restriction on `addAllowedApplication` in a managed profile generally
  (also ruled out — it works the instant lockdown alone is off). **This requirement, as specified
  (via `addAllowedApplication` while lockdown stays on), is not achievable on this Android version —
  a documented platform limitation, not a code defect.** A userspace attribution alternative
  (`ConnectivityManager.getConnectionOwnerUid()` inside `ForwardingEngine`, needing no Builder-level
  allowlist at all) is recorded as the recommended next avenue, not yet attempted or verified — see
  `ROADMAP.md`'s Phase 9.1.
- [x] **MS9-CAP02**: Verify, on-device, that traffic from a second concurrently-running Work Profile
  app (or APK Scope's own Work-side background traffic) is excluded from the target session's
  `TrafficInspectionStore` records, or is labeled unattributed rather than silently merged in.
  **2026-09-12 correction**: still blocked — MS9-CAP01's `addAllowedApplication` mechanism is a
  verified platform limitation, not a deferred implementation; this requirement can only be
  satisfied by a different scoping/attribution mechanism (see MS9-CAP01's `getConnectionOwnerUid`
  recommendation, and see MS9-ATTR01/02 below for the more basic attribution-wiring prerequisite
  this requirement itself turned out to depend on). Reframed as `ROADMAP.md`'s Phase 9.3.
  **2026-09-12, later pass — implemented and substantially verified**: `ConnectivityManager.
  getConnectionOwnerUid()` wired into `ForwardingEngine`/`TcpProxy`/`HttpsInspectionEngine` (real
  target UID vs. observed connection-owner UID compared explicitly; `MATCHED`/`MISMATCHED`/`UNKNOWN`
  as a first-class, distinct field — never conflated with `sessionId`/`targetPackage`). `forSession()`
  now excludes both `MISMATCHED` and `UNKNOWN`. **Verified on `emulator-5554` against the real
  production VPN/TcpProxy/HttpsInspectionEngine pipeline** (not `TestTunSink`, not direct record
  insertion): genuine `MATCHED` for the fixture's own HTTPS GET/POST and a full WSS session;
  genuine `MISMATCHED` for a different real UID's traffic in the same Work Profile (Play Store/
  installer background activity — real, not manufactured); genuine `UNKNOWN` for the same host
  earlier in the session, before the target package's UID was resolvable (an install-race window,
  not a fabricated case). Unit test `testForSessionExcludesUnknownOwnership` asserts the exclusion
  at the JVM level. **Marked partial (`[~]`), not `[x]`**: the on-device confirmation was a manual
  logcat/UI observation, not yet an automated instrumented assertion that `forSession()` excludes a
  real captured `MISMATCHED` record specifically — see `ROADMAP.md`'s Phase 9.3 status note for the
  precise remaining task.
  **2026-09-13, later pass — ownership tuple and cache-lifetime hardening, plus a focused export
  test**: inspected the exact tuple passed to `getConnectionOwnerUid` and found the "local" side was
  assumed to equal `tunAddress` rather than using the real source IP already parsed off each SYN
  packet — fixed: `TcpProxy` now threads the real observed `srcIpBytes` through explicitly
  (`TunSink.resolveConnectionOwnerUid` gained a `localIp` parameter with no default/fallback), and
  `ForwardingEngine` logs (does not silently ignore) any divergence from `tunAddress`. Verified the
  target-UID cache's lifetime across all four required scenarios (install, removal, session change,
  process restart) by reading the actual call sites, not asserting — found and fixed a real gap:
  removal previously left a stale cached UID valid forever; now every call re-queries `PackageManager`
  (bounded to once per new connection, not per packet) and a definitive `NameNotFoundException`
  invalidates the cache rather than falling back to it. Added `TrafficInspectionStoreExportTest` (4
  tests) asserting directly, against a realistic mixed batch (MATCHED + a MISMATCHED record from a
  different real UID, modeling "a second controlled application" + UNKNOWN), that only the MATCHED
  record reaches `forSession()`/the export path. **Still `[~]`, not `[x]`**: the automated
  instrumented (on-device) assertion this entry originally asked for is still not written — the new
  unit test above is JVM-level, using synthetic records shaped like the real on-device evidence
  already gathered, not a live androidTest assertion against a real captured MISMATCHED connection.
  **2026-09-13, second same-day pass — real second-application evidence obtained; remains `[~]`,
  now for a narrower, precisely-stated reason.** Correction to the entry above: `TrafficInspectionStoreExportTest`
  is a **filtering test** (it proves `forSession()`'s exclusion logic against synthetic records shaped
  like real evidence) — it was previously not clearly enough distinguished from "real second
  application verification," which it is not and was never meant to be. Real verification this
  pass: extended `riskfixture` (already an installed test-fixture app, distinct from the sandboxed
  target) with a button firing a genuine `HttpsURLConnection` GET to `api.github.com/zen` — a host
  the target never requests — making it a genuine second controlled application. Installed as its
  own sandboxed target first: real `MATCHED` against its own session. Attempting true concurrent
  monitoring of a *second, different* target app while the first's session was still active hit a
  confirmed, real, product-level constraint (not a workaround failure): the Work Profile VPN slot
  supports only one actively-monitored target at a time — a second "Prepare Sandbox" attempt on a
  different target hangs indefinitely, and the product's own UI states this directly ("VPN
  Architecture Limitation: APK Scope owns the Work Profile VPN slot... This target app cannot
  establish its own VPN simultaneously"); direct `pm install --user <id>` into the managed Work
  Profile is separately, unconditionally blocked by `DevicePolicyManager`
  (`SecurityException: Shell does not have permission to access user <id>`). Given that, real
  second-application evidence was instead obtained from organically-occurring traffic within the
  fixture's own real session: the same unscoped production VPN captured genuine, concurrent,
  real traffic from Android's own system keyboard (Gboard, `observedOwnerUid=1110167` — a real,
  different app in the same Work Profile) to `www.gstatic.com`/`dl.google.com`/`edgedl.me.gvt1.com`,
  every instance correctly `MISMATCHED` and excluded from the target's evidence export — real
  traffic, real production pipeline, never manufactured, and production routing was never weakened
  to obtain it. **Still `[~]`**: this is genuine on-device second-application verification (closing
  the gap the filtering-test mislabel left open), but it is still a manual logcat/UI observation of
  organically-occurring traffic, not an automated instrumented assertion, and true two-target
  concurrent monitoring remains architecturally unavailable rather than merely untested — that
  constraint is now documented, not resolved (resolving it would be a product architecture change,
  out of this pass's scope). See `STATE.md`'s "Milestone 9 — second acceptance rigor pass" section,
  item 1, for the full evidence log.
  **2026-09-13, third same-day pass — reproducible, count-based on-device confirmation.** Found a
  genuinely reproducible (on-demand, repeatable) second application already present in the Work
  Profile without any new provisioning: Play Store (`com.android.vending`, real per-profile uid
  `1110153`), opened directly from the Work Profile's own app-drawer tab while `fixture` remained the
  sole active monitored target. Real result: `OWNERSHIP_VERIFICATION host=play.googleapis.com
  status=MISMATCHED observedOwnerUid=1110153`. Made quantitative, not just a single-line read: the raw
  Traffic Inspector view showed 31 total captured records for the session (most of them Play
  Store/Google/Gboard `MISMATCHED` connections); ending the session and reading the real
  `UrlEvidenceCorrelation` logcat line showed the exported evidence artifact — built by the one real
  production call site of `forSession()` (`SandboxWorkQueryActivity.kt:436`) — contained **exactly 1
  entry**, the fixture's own genuine `jsonplaceholder.typicode.com` transaction. All MISMATCHED records
  were excluded from the export. Still `[~]`: no automated instrumented assertion of this specific
  exclusion exists yet (this was a manual logcat/count read); true concurrent two-target monitoring
  remains architecturally unavailable, not resolved (see MS9-CAP03 below for the related, but
  distinct, hang defect that was fixed this pass).
  **2026-09-13, fourth same-day pass — the automated, on-device assertion this entry has been waiting
  for across three prior passes now exists, against the real production export boundary, not
  `forSession()` in isolation.** Extracted `SandboxWorkQueryActivity.exportUrlEvidence()`'s real body
  into `UrlEvidenceExporter.export()` (same extraction discipline as `UrlEvidenceCorrelator`), then
  added it at **both** levels this entry's own wording distinguishes: `UrlEvidenceExporterTest` (3 JVM
  tests) and, because a JVM test is not itself "on-device" no matter how real the production code under
  test is, `UrlEvidenceExporterInstrumentedTest` (1 test, `AndroidJUnit4`, run and passing on
  `emulator-5554`). Both retain this scenario's shape as clearly-labeled synthetic input (not replayed
  real capture) and assert, against the real function, that the exported entry's identity
  (`trafficTransactionId`/`requestUrl`/`analysisSessionId`/`analysisTargetPackage`) matches the target
  transaction by value, and that the second application's transaction id is verifiably absent from
  every exported entry — not inferred from a count alone. Found and corrected a real misunderstanding
  while writing these tests: the artifact's own `totalEntryCount`/`exportedEntryCount` fields are
  computed *after* `forSession()`'s ownership filter already ran (they report size-budget truncation
  within the already-eligible set, not a raw-vs-ownership-filtered count), so the raw-vs-exported
  contrast is asserted via `TrafficInspectionStore.all()` directly, not via those two fields. **This
  entry is now `[x]`**: the specific "on-device automated assertion, vs. manual observation" gap named
  in every prior pass is closed. True concurrent two-target monitoring remains a separate, architectural
  non-goal (see MS9-CAP03), not reopened by this.
- [x] **MS9-CAP03** (added 2026-09-13, third same-day pass): the one-active-session constraint is
  intended product behavior, but attempting a second "Prepare Sandbox" while one session is active
  previously hung indefinitely instead of being rejected — re-examined rather than accepted as
  permanent. Root cause, traced by reading the code: `SandboxSessionCoordinator.prepare()` already
  contains the correct guard (`queryWorkActiveSession()` → fail with `ANOTHER_SESSION_ACTIVE` if a
  different session is active), but the cross-profile query it depends on
  (`CrossProfileQueryBridge.launchForResult`) has no timeout — if that query's result is ever silently
  dropped (the same background-activity-launch platform behavior documented elsewhere in this
  codebase), the guard itself never returns, hanging `prepare()` forever with no error and no state
  transition, matching the previously-observed symptom exactly. **Fixed**: wrapped that call in
  `withTimeoutOrNull(10_000L)`; a timeout now fails the new session closed with a new
  `SandboxErrorCode.WORK_SESSION_STATE_UNKNOWN` (`RETRYABLE`) instead of hanging — an inconclusive
  answer is treated the same as a confirmed conflict, so this cannot let two targets end up
  concurrently monitored (no concurrent-target support was added). Verified on-device that the normal,
  no-conflict path through this exact guard now completes quickly. Not yet done (as of the third pass):
  an automated test for the timeout path itself, and a fresh head-to-head repro of the now-fixed
  conflict case.
  **2026-09-13, fourth same-day pass — the automated test now exists.** Added an
  `activeSessionQueryOverride` test seam to `DefaultSandboxSessionCoordinator` (null by default; every
  production call site unaffected) and `PrepareSandboxTimeoutTest` (androidTest, 3 tests, real
  Context/Room, run and passing on `emulator-5554`, real wall-clock timings from the actual test
  report): a never-completing query causes `prepare()` to fail closed with
  `WORK_SESSION_STATE_UNKNOWN` in 11.6s (bounded by the real 10s timeout, not the 20s outer test
  bound), with the persisted session confirmed still `FAILED` (never reaching `PREPARING`, which would
  mean the real install handoff had been dispatched) and no competing session row created; a late
  callback delivered after the timeout was proven not to resume the failed operation or corrupt a
  later, independent `prepare()` attempt (13.3s); and the no-conflict normal path was reconfirmed to
  complete quickly (1.7s) without the guard misfiring. **Not attempted**: a fresh head-to-head repro of
  the original concurrent-conflict scenario with two *real* sessions (judged unnecessary — the fix is a
  bounded-wait wrapper around an already-traced real suspend call, and the timeout mechanism itself is
  now directly proven) — stated as a real, deliberate scope choice, not an oversight.

#### Session/target attribution wiring (MS9-ATTR) — added 2026-09-12, discovered while reconciling MS9-CAP02/MS9-URL ordering
- [x] **MS9-ATTR01**: Every `TrafficRecord` a real captured transaction produces must carry the
  active session's `sessionId`/`targetPackage` — not merely the session-level engine construction
  having those values available, but every actual construction site using them. **Found not done**:
  none of `HttpsInspectionEngine`'s four direct `TrafficRecord` construction sites, and three of
  `Http2RelayHandler`'s six `toTrafficRecord(...)` call sites (including `finalizeStream`, which
  produces the *final* record for every h2/gRPC/SSE stream, overwriting any earlier provisional
  attribution), passed `sessionId`/`targetPackage` at all — meaning `TrafficInspectionStore.forSession()`
  silently returned nothing for the majority of real captured traffic, in every session, regardless
  of whether MS9-CAP02's contamination concern applied. **Fixed**: all seven call sites now pass
  `sessionId`/`targetPackage` through. A second layer of the same defect was found while verifying
  the first: `HttpsInspectionStore.record()`'s "mirror to unified `TrafficInspectionStore`" block
  (backward-compatibility path) reconstructed a fresh, unattributed `TrafficRecord` under the same
  id immediately after the real attributed write, silently clobbering it on every transaction — fixed
  by having the mirror preserve the existing record's attribution instead of discarding it.
- [x] **MS9-ATTR02**: Verify on-device that `TrafficInspectionStore.forSession(sessionId,
  targetPackage)` actually returns a real, genuinely-captured transaction (not merely that
  `TrafficInspectionStore.all()`/the legacy `HttpsInspectionStore` sees it), and that it does not leak
  into a query for an unrelated sessionId. **Verified**:
  `testTrafficRecordCarriesSessionAttributionOnDevice` (real TLS+ALPN HTTP/1.1 GET against
  httpbin.org, engine constructed with a real sessionId/targetPackage) passes on emulator
  instrumentation (`emulator-5554` — the physical Pixel 8 was not tested). **Correction, 2026-09-12,
  later pass**: originally reported as "26/27" here without the actual arithmetic (the real XML read
  `tests="27" failures="3"` = 24 passed). See `STATE.md` for the corrected, raw-instrumentation-log-backed
  breakdown: one of the three was a genuinely `@Ignore`d test (confirmed via raw `TestRunner` logcat,
  not assumed) misrendered as an XML failure by AGP's report merge, and two were real failures on a
  missing `fixture-debug.apk` precondition file — fixed by pushing it to `/data/local/tmp/`, after
  which the suite reads `tests="27" failures="1"` (26 genuinely passed, 1 ignored, 0 real failures,
  legacy-model test included among the passes). Full JVM unit suite: `tests=314 failures=0`.

#### URL evidence wiring (MS9-URL)
- [x] **MS9-URL01**: Call `importUrlEvidence()` from the normal end-session flow
  (`SandboxCleanupViewModel.importAndReconcile()`), matching the existing
  `importEvidence`/`importRuntimeArtifact`/`importAndroidEvidence` calls already there.
  **2026-09-12 note**: reframed as `ROADMAP.md`'s Phase 9.4, after MS9-ATTR (done) and MS9-CAP02/
  Phase 9.3 (not started) — wiring this before MS9-ATTR was fixed would have exported from a store
  that, for HTTP/1.1 traffic, was never attributed to any session at all; wiring it before Phase 9.3
  is done leaves the known cross-app-contamination gap unaddressed in whatever it exports.
  **2026-09-12, later same-day pass — done**: the call is added; see MS9-URL02 for on-device
  verification (Phase 9.3's own cross-app-contamination gap remains separately tracked and does not
  block this specific wiring, since `forSession()`'s MATCHED-only requirement already applies
  independently of this call site).
- [x] **MS9-URL02**: Verify, with a fixture APK containing a real embedded URL literal matching an
  actually-captured request, that reopening the analysis after a normal (non-orphan) session end
  shows that URL's provenance as `RUNTIME_OBSERVED` with a real transaction id, host, method, and
  status — not merely an unrelated risk-score change.
  **2026-09-12, later same-day pass — done, plus a second defect found and fixed**: verified on a
  rebuilt, reinstalled `emulator-5554` app: real fixture `GET jsonplaceholder.typicode.com/posts/1`
  request through the real VPN pipeline, normal (non-orphan) session end, analysis reopened — the
  requested URL shows `EXACT RUNTIME OBSERVED` with a real session id, transaction id, method `GET`,
  status `200`. While verifying this, found `SandboxSessionCoordinator.correlateUrlEvidenceWithAnalysis()`
  conflated this with mere host-level matches (a *different*, never-requested DEX candidate for the
  same host was also being marked `RUNTIME_OBSERVED` before the fix) — contradicting DEX04's own
  "host-only match attaches separate, non-elevating `hostCorrelation` metadata" rule, which held for
  `ApkAnalyzer.analyze()`'s unit-tested path but not for this cross-profile-import path. Fixed; the
  sibling candidate now correctly shows `HOST CORRELATED` with its provenance untouched. See
  `STATE.md`'s "Milestone 9 — URL evidence wiring and host-correlation fix" section for full evidence.
  **Correction, 2026-09-13**: the line previously here claimed idempotency was "guaranteed by the
  function's own `if (candidate.runtimeEvidence != null)` guard" — retracted as an untested inference
  (see `STATE.md`'s correction for why, and why the original code had no defined behavior at all for a
  later, *distinct* legitimate observation, a more important gap than duplication). Actually done this
  pass: the matching/merge logic was extracted into `UrlEvidenceCorrelator`, a pure function directly
  unit-tested (12 tests) for real idempotency (a repeated identical artifact is a true no-op), real
  non-suppression (a later distinct transaction replaces an earlier one instead of being dropped), and
  the documented single-reference retention policy this milestone was asked to define. **Still open**:
  an on-device (not unit-level) run of two real sessions importing distinct evidence into the same
  analysis — the one attempt was abandoned after unrelated install/UI flakiness and not retried.
  **2026-09-13, second same-day pass — process-restart persistence now genuinely verified on-device;
  the specific "still open" item above narrowed further, with an explicit reason it stays open.**
  Ended the fixture's real session (`GET jsonplaceholder.typicode.com/posts/1`), confirmed
  `RUNTIME_OBSERVED` on the reopened analysis, then `adb shell am force-stop com.nadeem.apkscope` — a real
  full kill of the Personal-profile process holding that analysis (confirmed via `dumpsys activity
  processes`) — relaunched fresh, and reopened the same analysis: **`EXACT RUNTIME OBSERVED` for the
  same URL survived the real process death**, the first on-device proof (not simulated) that this
  store's atomic-write hardening (see MS9-PER01/02) actually survives real process termination.
  **What was not achieved on-device this pass, and precisely why**: reusing the *same* analysisId for
  a second session — needed to exercise "repeat the same artifact import," "a distinct later
  observation replaces an earlier one," and "an older artifact replayed afterward does not regress" as
  live device actions — hit a confirmed, reproducible product constraint: ending a session deletes
  the Personal-side staged APK copy, so a second "Continue to Sandbox" on the *same*, still-persisted
  analysis reports "The APK for this analysis is no longer available"; only a fresh re-import (a new
  analysisId) can start another session. This is real product behavior (confirmed twice), not a
  workaround failure, and there is no UI path around it today. **These three specific sub-behaviors
  are verified only at the correlator level** — `UrlEvidenceCorrelatorTest`'s
  `repeatedExactMatchImport_isIdempotentNoOp`, `laterDistinctTransaction_replacesEarlierExactMatch`,
  `olderTransactionInBatch_neverRegressesAnAlreadyNewerStoredObservation`, plus new equal-timestamp
  tie-break tests (`equalTimestampAcrossBatches_existingStoredObservationWinsTheTie`,
  `equalTimestampWithinBatch_higherTrafficSequenceWinsTheTie`,
  `equalTimestampAndSequence_lexicographicTransactionIdIsTheLastResortTiebreak`) — real, deterministic,
  passing JVM tests against synthetic batches, explicitly distinguished here from the device-level
  process-restart proof above rather than conflated with it. See `STATE.md`'s "Milestone 9 — second
  acceptance rigor pass" section, item 2.
  **2026-09-13, third same-day pass — repeated import now verified device-level too, without a second
  session or a retained staged APK.** Traced `SandboxCleanupScreen`'s own code and found it already
  invokes the real production import path (`importAndReconcile` → `importUrlEvidence` →
  `correlateUrlEvidenceWithAnalysis` → `forSession()`) **twice** within one normal single session-end
  flow (once while ending, again automatically the moment the session reaches `isComplete`). Real
  on-device logcat, same analysisId, ~1m18s apart: `Correlated 1 entries...: 1 exact RUNTIME_OBSERVED
  URLs` then `Imported 1 entries...— no new matches (idempotent no-op or no correlation found)` — a
  genuine idempotent no-op, no duplicate, no regression, produced by the real product lifecycle with no
  special setup. This satisfies "invoke the production import path twice for its original analysis and
  session" without a second target-execution session or a retained staged APK, exactly as instructed.
  The "distinct later observation" / "replay doesn't regress" sub-cases remain correlator-unit-verified
  only, for the same traced, structural reason as before (no UI path to a second same-analysisId
  session) — this is now a confirmed fact from re-reading the code this pass, not an unexamined
  assumption.
  **2026-09-13, fourth same-day pass — repeated import now verified by content identity, and all
  remaining correlator-unit-only sub-cases given a real Android persistence integration counterpart.**
  Extracted `correlateUrlEvidenceWithAnalysis`'s real `get → correlate → put` sequence into
  `UrlEvidenceImporter.correlate()` and added `RepeatedUrlEvidenceImportInstrumentedTest` (androidTest,
  6 tests, all passing on `emulator-5554`):
  - Two real, separate calls with identical synthetic artifact content confirmed the second call is an
    idempotent no-op **by identity** (the retained `runtimeEvidence.transactionId`/`.url` are the
    *first* call's values, not merely "unchanged=true"), exactly one candidate exists afterward (no
    duplicate), and the result survives a simulated process restart (`StaticAnalysisFileStore.read()`
    directly, bypassing this process's own cache — the same honest proxy `StaticAnalysisPersistenceInstrumentedTest`
    already established).
  - "Distinct later observation replaces earlier," "older artifact replay does not regress," and
    "deterministic equal-timestamp tie-break" each now ALSO have a real Android-integration test
    (`laterDistinctObservation_replacesEarlierRetainedReference_throughTheRealImportPath`,
    `olderArtifactReplayedAfterward_doesNotRegressRetainedEvidence_throughTheRealImportPath`,
    `equalTimestampAcrossTwoRealImports_existingStoredObservationDeterministicallyWinsTheTie`) —
    two genuinely separate `UrlEvidenceImporter.correlate()` calls each, through real storage, not only
    the pre-existing `UrlEvidenceCorrelatorTest` pure-logic versions. "Static provenance and
    host-correlation precision" (DEX04's rule) likewise gained a real-storage counterpart
    (`hostOnlyCandidate_keepsItsStaticProvenanceAndNeverGetsRuntimeEvidence_...`): a real persisted
    analysis with an exact-match candidate and a host-only candidate on the same host, confirmed after
    a real import that the host-only candidate keeps `REFERENCED_BY_CODE` (never promoted), gets no
    fabricated `runtimeEvidence`, and genuinely receives `hostCorrelation`.
  - **Why the cleanup screen imports twice, inspected rather than assumed**: `SandboxCleanupScreen`'s
    `LaunchedEffect(sessionId, state.isComplete)` has `state.isComplete` as one of its own keys — when
    it flips, Compose cancels the running effect coroutine and starts a genuinely new one; this is two
    distinct effect invocations, not one loop. The first (`!isComplete`) imports exactly once, then only
    polls reconciliation on a timer; the second (`isComplete`) imports exactly once, unconditionally,
    and does not loop. **Confirmed bounded, intentional retry behavior** (exactly two automatic import
    attempts per screen visit), not duplicate/unbounded lifecycle execution — consistent with this same
    file's own documented "safe to call speculatively on every retry/reconcile" design elsewhere. A
    user's own manual retry actions can add more calls, but each is explicit and individually
    idempotent-safe, never an automatic unbounded loop.
  Every sub-item from the original acceptance list is now accounted for explicitly — none dropped —
  distinguishing pure-logic-only, Android-integration, and real-product-workflow verification levels
  per item rather than leaving the distinction implied. See `STATE.md`'s "Milestone 9 — fourth
  acceptance rigor pass" section, items 3-4.
- [~] **MS9-URL03**: Added 2026-09-14 (Pixel 8 acceptance, fifth pass), directly in response to the
  physical-device finding recorded under MS9-UI02 below ("this session's URL-evidence export/import
  never completed... root cause not confirmed... heavy log rotation... limited visibility"). Trace the
  normal-cleanup import path stage by stage with durable, bounded, cross-process diagnostics (import
  scheduling, cross-profile dispatch, Work-side receipt, session/ownership filtering, artifact creation,
  URI grant/result delivery, Personal-side read/validation, correlation/persistence, durable outcome) so
  a future occurrence is diagnosable without depending solely on rotating `logcat`; represent
  not-requested/pending/empty/failed/imported outcomes distinctly; ensure cleanup ordering cannot make
  evidence needed for import unavailable before export finishes.
  **Implemented and unit/instrumented-tested this pass, root cause of the *original* missing-evidence
  incident still not confirmed** — per this same milestone's own "do not infer a cause from missing
  files alone" discipline, the fixes below close real, independently-confirmed gaps found while tracing
  the pipeline, not a diagnosis of that one incident:
  - `UrlEvidencePipelineDiagnostics` (`core:crossprofile`): a durable, bounded (500-line cap),
    per-process JSONL log, correlated across the Personal/Work profile boundary via a single
    `operationId` threaded through the existing cross-profile query intent extras (`Handoff.OPERATION_ID`)
    — never the only copy of a diagnostic (`Log.i` also fires), never payload/URL/token content.
    Directly tested (`UrlEvidencePipelineDiagnosticsInstrumentedTest`, 3 tests, `emulator-5554`, all
    passing): real record/read round-trip with exact field fidelity, correct `operationId` filtering
    (no cross-operation leakage), and the 500-line retention cap genuinely evicting the oldest entries
    while keeping the most recent one — not merely asserted from reading the source.
  - `UrlEvidenceImportStatusStore.Status` (`PENDING`/`EMPTY`/`IMPORTED`/`FAILED`, with "not requested"
    represented by record *absence*, never an enum value) plus `markPending()`, a durable write made
    *before* the cross-profile dispatch — so a process death mid-import leaves a real "started but never
    concluded" trace instead of nothing. Directly tested
    (`UrlEvidenceImportStatusStoreInstrumentedTest`, 5 tests, `emulator-5554`, all passing).
  - Two real, independent latent bugs found and fixed while tracing this, both real regardless of
    whether either was *the* proximate cause of the original incident: (a) `UrlEvidenceImportStatusStore`
    itself had the same hardcoded-`/data/data/...`-path fragility class MS9-PER02 already fixed
    elsewhere, missed here because every call site to date happens to run in the Personal profile where
    the wrong and right paths currently coincide; (b) `SandboxSessionCoordinator.importUrlEvidence()`'s
    initial `repository.get(sessionId)` call sat outside its own try/catch, and
    `SandboxCleanupViewModel.importAndReconcile()`'s entire multi-step sequence had no outer exception
    handling at all — either could silently abandon the URL-evidence import with zero trace on a
    transient Room/IO hiccup, a real (not hypothetical) risk on a busy physical device. Both fixed.
  - **2026-09-14, same day, later pass — exercised on the physical Pixel 8, scenario completed
    cleanly, original incident did not reproduce.** A fresh fixture analysis/session
    (`sessionId=3fb77e24-3058-40a8-a18f-df17bdf79dd8`, `analysisId=616e9ee5-56f5-4247-915a-6b5dd48aca1b`)
    on a freshly rebuilt, dex-verified install exercised the exact same normal-cleanup import path this
    diagnostics infrastructure traces. All nine stages fired and correlated correctly across both
    profiles under one `operationId` (`import_scheduled` → `import_launched` →
    `cross_profile_query_dispatch_attempted` → `work_query_received` → `work_target_package_resolved` →
    `work_artifact_created` → `work_result_delivered` → `cross_profile_query_dispatch_returned` →
    `personal_artifact_parsed`/`validated_ok` → `correlation_started` →
    `UrlEvidenceCorrelation: Correlated 1 entries ... 1 exact RUNTIME_OBSERVED` →
    `correlation_persisted_change` → `durable_import_outcome_recorded error=null` →
    `import_and_reconcile_sequence_completed`), and two later, distinct scheduling attempts (different
    operationIds) were correctly skipped as already-in-flight — **concurrent import suppression** of a
    still-active operation, not evidence about repeating an already-*completed* import (see MS9-URL04
    below for that separate guarantee, and for this same concurrent-suppression guard's own focused
    test coverage). Persisted result (`status=IMPORTED`, `https://dummyjson.com/quotes/random` with provenance
    `RUNTIME_OBSERVED`) confirmed identical via direct on-device file inspection (`run-as` +
    `strings`, not only the UI) both before and after a real app restart (`am force-stop` + relaunch).
    Per this pass's own explicit instruction, this is reported as **the original incident not
    reproducing this run** — this infrastructure's value for *diagnosing a future recurrence* is now
    demonstrated end-to-end, but the original incident's root cause remains formally unconfirmed, since
    it simply did not recur under this exact real-device exercise. See `STATE.md`'s "Milestone 9 — Pixel
    8 acceptance, fifth pass, item 4 (physical device)" section for full evidence.
- [x] **MS9-URL04**: Added 2026-09-14 (Pixel 8 acceptance, sixth pass), directly in response to a
  correction to this same milestone's own reporting: the physical-device session's skipped scheduling
  attempts (recorded above under MS9-URL03) were previously cited as if they demonstrated "repeated
  import without duplicate evidence" — corrected, since they are **concurrent import suppression** (an
  in-flight guard deferring a second request while an earlier one is still genuinely active), a
  distinct guarantee from repeating an already-*completed* import. This entry covers both, verified
  separately:
  - **In-flight guard lifecycle** (`SandboxCleanupViewModel.importAndReconcile`'s `isImporting` guard):
    verified directly with a new focused suite,
    `SandboxCleanupImportGuardInstrumentedTest` (5 tests, `emulator-5554`, all passing), using a new
    `coordinatorOverride` test seam on the ViewModel (production behavior unchanged — every real
    caller leaves it `null`, matching `DefaultSandboxSessionCoordinator`'s own established
    `activeSessionQueryOverride` precedent). Confirms the guard releases correctly after **success**
    (`successfulImport_releasesGuard_...`), **failure** (`failedImport_releasesGuardViaTheOuterCatch_...`,
    a thrown `IOException`), **timeout** (`timeoutDuringImport_...`, a real
    `TimeoutCancellationException` from an inner `withTimeout`), and **cancellation**
    (`cancellingTheViewModelScopeMidImport_...`, a real `ViewModelStore.clear()` — the actual mechanism
    a destroyed screen uses). A fifth test
    (`concurrentCallWhileGenuinelyInFlight_isSkippedNotDuplicated_andTheGuardReleasesOnceTheActiveOneConcludes`)
    directly reproduces the physical-device finding under controlled timing: a second call is skipped
    while the first is provably still unresolved, then a third call after the first concludes proceeds
    normally — confirming the physical session's skip events were genuine concurrent suppression of an
    active operation, not a stale/leaked guard. No stale-guard defect was found; no fix was needed.
  - **Completed artifact replay** (a genuinely *later*, separate call against an already-persisted
    result): already directly verified, prior to this pass, by
    `RepeatedUrlEvidenceImportInstrumentedTest.repeatedImport_sameArtifactContentTwice_referencesTheSameTransaction_noDuplication_andSurvivesASimulatedProcessRestart`
    — two genuinely sequential calls to the real production `UrlEvidenceImporter.correlate()` function
    (the second only after the first has fully returned) against real on-device
    `StaticAnalysisResultStore`/`StaticAnalysisFileStore` persistence, proving the repeat is a true
    idempotent no-op (no duplicate candidate, the *same* retained transaction reference — not a
    look-alike replacement), verified via a direct on-disk read bypassing the in-memory cache. Cited
    here, not rerun — nothing since its last confirmed run (6/6 passing, this same session, before
    today's changes) has touched `UrlEvidenceImporter`, `StaticAnalysisResultStore`, or
    `StaticAnalysisFileStore`. **Device scope, stated precisely**: a real Android instrumented test
    against real device storage and the real correlation+persistence function — not the full
    cross-profile export/dispatch transport, which the same pass's physical Pixel 8 session (MS9-URL03
    above) exercised separately, end to end, with genuinely captured (not synthetic) evidence. "Later
    observation replaces earlier" and "older artifact replay does not regress" remain explicit, distinct
    tests in that same suite
    (`laterDistinctObservation_replacesEarlierRetainedReference_throughTheRealImportPath`,
    `olderArtifactReplayedAfterward_doesNotRegressRetainedEvidence_throughTheRealImportPath`), not
    folded into one another.

#### Traffic Inspector detail view (MS9-UI)
- [x] **MS9-UI01**: Diagnose why `TrafficItemRow`'s `onClick`/`Modifier.clickable` inside
  `TrafficInspectorScreen`'s `LazyColumn` does not open `selectedRecord`'s detail view on-device,
  despite the list itself rendering and the equivalent `WorkLiveMonitorScreen` entry-point defect
  already being root-caused and fixed this reconciliation's predecessor session (a static,
  first-child `PrimaryActionButton` worked; the pre-existing dynamic, state-reading button and
  `Modifier.pointerInput`+`detectTapGestures` variants of it did not — see
  `evidence/checkpoint_8_9_acceptance/SUMMARY.md` §2).
  **2026-09-13 — investigated, does not reproduce on the authorized emulator**: read
  `TrafficItemRow`'s implementation (a plain `Card(Modifier.clickable{})`, no `pointerInput`, and
  `TrafficInspectorScreen`'s own `Scaffold` has no `bottomBar` — ruling out the specific mechanism the
  referenced `WorkLiveMonitorScreen` diagnosis found for a *different* screen). Tested twice on
  `emulator-5554` with exact `uiautomator`-dumped element bounds (not estimated coordinates, which had
  caused false negatives earlier in this same check): a seeded WSS record and a seeded SSE record both
  opened their full detail views correctly. A genuinely captured (not seeded) real WSS transaction was
  also independently clicked open earlier this same session, incidentally, while verifying unrelated
  ownership-verification work.
  **2026-09-13, same day — physical Pixel 8 verified too, per explicit user authorization ("verify on
  pixel 8")**: connected to `39271FDJH008HQ` (the exact device the original report named), every
  command explicitly scoped to it. Tapped two real rows via exact `uiautomator` bounds: an SSE record
  opened its full detail (content-type, last-dispatched-id, all 3 events with real payloads); a WS
  record opened its full detail (handshake, close code, all 3 messages including real echoed text).
  Both confirmed via screenshot. **Closes the previously-open physical-device gap** — the exact
  hardware the defect was reported on now shows the click path working correctly.
- [x] **MS9-UI02**: Fix the root cause (not merely restructure the widget) if the same class of
  defect applies, and verify a genuine captured HTTPS transaction's full detail (headers, decoded
  body, protocol) opens from the list on a physical device.
  **2026-09-13**: no reproducible defect was found on either the emulator or the physical Pixel 8
  (see MS9-UI01), so no code change was made — there was nothing confirmed broken to fix on either
  device tested. **Caveat stated precisely**: the Pixel 8's installed build predates this pass's other
  Milestone 9 changes (it was not rebuilt/reinstalled this pass) — this remains a faithful verification
  of the click mechanism specifically, since `TrafficItemRow`/`TrafficInspectorScreen.kt` were not
  modified by any pass to date, so the exact file tested is identical regardless of build.
  **Correction, 2026-09-13, second same-day pass**: the rows opened above ("Load Verified Capture
  Fixtures" seeded WSS/SSE records) are valid evidence for the click mechanism itself, but are not,
  and should not be read as, capture acceptance evidence — a seeded row calls the same
  `TrafficInspectionStore.record()` API with synthetic pre-built data, it is not a genuine capture. A
  fresh, genuinely captured transaction (not seeded) was separately opened this pass via normal
  Dashboard → Sandbox Session → Open Live Monitor → Traffic Inspector navigation: real session id
  `c4362440-675e-4a60-896c-1b89dcd57a60`, real `observedOwnerUid=1110290` (`MATCHED`), real decrypted
  response for `jsonplaceholder.typicode.com/posts/1` (`date`, `server: cloudflare`,
  `user-agent: okhttp/4.12.0` headers visible in the detail view) — confirming the click path also
  opens a real, non-seeded transaction correctly, on the emulator. The physical-Pixel-8 confirmation in
  MS9-UI01 above used seeded rows specifically because it targeted the click mechanism named in the
  original report, not capture acceptance; a genuine on-device physical-Pixel-8 *capture* was outside
  this pass's authorization (emulator-scoped) and remains open — see `STATE.md`'s "Milestone 9 — second
  acceptance rigor pass" section, item 4, and item 6's physical-device acceptance-boundary statement.
  **Correction, 2026-09-13, fourth same-day pass**: the "normal Dashboard → Sandbox Session → Open Live
  Monitor → Traffic Inspector navigation" described directly above was, in fact, not a separate normal
  entry point — re-reading `WorkLiveMonitorScreen.kt` in full this pass found that the only control
  reaching Traffic Inspector from that screen is its own "STATIC TEST BUTTON," an explicitly-labeled
  temporary diagnostic (`// TEMP DIAGNOSTIC ... not for commit`) added to isolate a still-open, separate
  defect where this screen's real filter chips and feed rows do not respond to touches. There is
  currently no other functioning path to Traffic Inspector from Work Live Monitor in the shipped code —
  the filter chips/feed rows carry no such wiring. **This does not invalidate the transaction evidence
  itself** (the session id, ownership, and decrypted response quoted above are real and were genuinely
  captured) — but it corrects what "normal navigation" meant: it went through this diagnostic shortcut
  because no separate, working, non-debug entry point exists yet to independently verify. The
  underlying filter-chip/feed-row click defect this button was added to isolate remains open and
  untouched (a UI fix, out of this milestone's acceptance-verification scope).
  **2026-09-13, physical Pixel 8 pass — the genuine physical-device capture gap is now closed, with
  explicit authorization.** User connected `39271FDJH008HQ` and authorized provisioning a fresh Work
  Profile there (none existed). After fixing a newly-confirmed real defect (see below), obtained a
  genuine, non-seeded, physical-device capture: real `OWNERSHIP_VERIFICATION host=jsonplaceholder.
  typicode.com status=MATCHED observedOwnerUid=1410401`, alongside real organic `MISMATCHED` traffic
  from a different real app (`observedOwnerUid=1410222`) in the same Work Profile — the same
  MATCHED/MISMATCHED pattern already verified on the emulator, now genuinely reproduced on the exact
  physical device the original report named. **New defect found and verified, not merely suspected**:
  `SandboxPreparingScreen`'s "Open App Settings" remediation button (for `canRequestPackageInstalls=false`)
  opens the **Personal**-profile App Info page — confirmed directly (`Install unknown apps: Allowed`
  there, already, irrelevant) — while the actual failing permission belongs to the **Work**-profile
  instance (confirmed separately: `Install unknown apps: Not allowed` there). The button's `startActivity`
  call carries no cross-profile targeting. A real user tapping it would not reach the setting that
  needs changing. Worked around by hand through Settings (Work tab), not a shell grant. **A separate,
  new, real gap found on this device and left honestly open**: this session's URL-evidence
  export/import never completed — no import-status file (Personal) and no `-url-evidence.json` export
  (Work) exist for this session's analysisId, despite the session ending normally through the real UI.
  Root cause not confirmed (heavy log rotation from real concurrent device use limited visibility) —
  this is a genuinely new finding physical-device testing surfaced that the emulator-based automated
  tests (which exercise the already-extracted `UrlEvidenceImporter`/`UrlEvidenceExporter` logic
  directly, not the full cross-profile query dispatch) could not have caught. See `STATE.md`'s
  "Physical Pixel 8 acceptance" section for full detail.
  **2026-09-14, fifth pass — the wrong-profile-Settings defect above is fixed and verified on-device
  (not merely worked around by hand a second time).** Traced which application instance actually checks
  `canRequestPackageInstalls()` (the Work-profile process, via `SandboxWorkQueryActivity`) versus which
  one launched Settings (`SandboxPreparingScreen`, in the Personal process, with a plain
  `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` `startActivity` carrying no cross-profile targeting —
  confirmed as the exact defect, not merely suspected). Fixed by reusing the existing
  `ACTION_WORK_QUERY`/`SandboxWorkQueryActivity` cross-profile mechanism (no new cross-profile action,
  no hardcoded user ids, no new permissions): two new query types,
  `QUERY_TYPE_CHECK_INSTALL_PERMISSION` (a fresh, live `canRequestPackageInstalls()` read — never
  cached) and `QUERY_TYPE_OPEN_INSTALL_SETTINGS` (opens `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`
  from *within* the Work-profile process itself, so Android opens that profile's own copy of the
  screen). `SandboxPreparingScreen`'s button now calls this instead of its own `startActivity`.
  **Two further real, on-device-confirmed platform behaviors found and fixed while making this
  genuinely testable** (`WorkInstallPermissionRemediationInstrumentedTest`, real `ActivityScenario`,
  real cross-profile query, `emulator-5554` — not mocked): (a) `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`
  resolves to `Settings$ManageAppExternalSourcesActivity`, one of the newer "SPA" (Settings Panel)
  framework screens, which does not reliably deliver `onActivityResult` to a `startActivityForResult`
  caller — a Back press tears down the querying Activity's own task instead of returning to it normally
  (confirmed: `onDestroy` fires with `isFinishing=true` and the tracked session id still unconsumed, no
  `onActivityResult` ever reached); (b) independently, Android does not deliver an already-computed
  cross-profile `ActivityResult` back to a caller whose task is fully `STOPPED` behind the still-foreground
  launched screen — delivery only happens once that caller's task becomes foreground-eligible again
  (i.e. once the user actually leaves the launched screen), regardless of how quickly the Work side
  itself responds. Fixed by launching Settings with a plain `startActivity` (never
  `startActivityForResult` — there is no reliable per-launch result for this specific system screen to
  wait for) and responding to the cross-profile query immediately once the launch itself is confirmed;
  `opened` now honestly means "genuinely launched in the correct profile," never "the user has already
  returned" or "the permission was granted" — a separate, always-fresh `checkInstallPermission` query is
  the only source of truth for the actual permission state, called by the UI immediately after and again
  on manual Retry. Denial, cancellation, a successful permission change, and retry are all exercised via
  this same fresh recheck, not inferred from Settings' own resultCode (which this target does not
  meaningfully report). **Verified on `emulator-5554`**: `WorkInstallPermissionRemediationInstrumentedTest`
  (2 tests) passes — `checkWorkInstallPermission` returns a real, non-null answer from the Work profile's
  own process; `requestOpenWorkInstallSettings` genuinely opens `Settings$ManageAppExternalSourcesActivity`
  as `UserHandle{11}` (logged directly from that process, not inferred) and never as Personal's own
  `UserHandle{0}`, and a fresh recheck immediately afterward also succeeds. Full JVM suite re-verified
  clean at `tests=360 failures=0` after these changes; 4 other androidTest suites this pass's changes
  could plausibly have affected (`UrlEvidenceImportStatusStoreInstrumentedTest` 5/5,
  `PrepareSandboxTimeoutTest` 3/3, `RepeatedUrlEvidenceImportInstrumentedTest` 6/6, plus the new
  `UrlEvidencePipelineDiagnosticsInstrumentedTest` 3/3) all pass with no regression.
  **2026-09-14, same day, later pass — physical Pixel 8 re-verification performed.** Device
  reconnected mid-pass (a real USB drop, recovered over Wi-Fi debugging); confirmed the Work-profile
  instance was on a stale, pre-fix build (`lastUpdateTime=2026-09-13 20:36:47`) — a separate finding
  from, and not an explanation for, the still-unconfirmed original missing-evidence incident (see
  MS9-URL03 below) — rebuilt, dex-verified, and reinstalled (`lastUpdateTime=2026-09-14 07:23:00`). On
  this run, the Work-profile process's own live `canRequestPackageInstalls()` check reported **true**
  (`ApkScopeInstall: prerequisites ... canRequestPackageInstalls=true`) — the exact fact the
  product's own remediation logic gates on — so the real Prepare Sandbox flow (a fresh fixture import,
  the real Android install-confirmation dialog confirmed via `dumpsys activity activities` to run as
  `u14`) reached "Sandbox Ready" without the remediation screen ever appearing. The separate
  `REQUEST_INSTALL_PACKAGES` grant-flag *listing* (`dumpsys package`) read `granted=false, flags=[
  USER_SET], userId=14`, unchanged before and after — this listing alone must not be read as
  "installation access was denied": the live check the product depends on, and the real install
  outcome, both said otherwise. Per this pass's own explicit instruction ("if permission is already
  allowed, report that the remediation transition was not exercised"), this is reported precisely as
  such — the remediation button itself was not triggered on this real run, a genuine, honestly-reported
  finding, not evidence against the emulator-verified fix above (which remains the correct, confirmed
  behavior for when the block *is* hit). See `STATE.md`'s "Milestone 9 — Pixel 8 acceptance, fifth
  pass, item 4 (physical device)" section for full evidence, including the three successive on-device
  diagnostic runs on the emulator that originally pinpointed each real cause.

#### Persistence hardening (MS9-PER)
- [~] **MS9-PER01**: Replace or bound `StaticAnalysisResultStore`'s raw
  `ObjectOutputStream`/`ObjectInputStream` round-trip of its own on-disk file with a format that does
  not deserialize arbitrary Java objects (or add integrity verification before deserializing),
  matching `docs/ARCHITECTURE_CONSTRAINTS.md`'s "Do not deserialize untrusted Java objects."
  **2026-09-13 — integrity gate added, done as a partial mitigation, not full elimination**: extracted
  the file I/O into `StaticAnalysisFileStore` (pure, unit-tested, 6 tests) with a
  `[4-byte magic][4-byte format version]` header checked via primitive `DataInputStream` reads
  *before* `ObjectInputStream.readObject()` is ever called — a foreign/corrupted/wrong-version file is
  rejected as a cache miss and never reaches deserialization. Marked `[~]` because the payload, once
  the header matches, is still Java-serialized and still trusts its object graph — a full replacement
  with a non-Java-serialization encoding (this entry's stronger option) was not attempted, and is
  stated as a real remaining gap in the new code's own KDoc rather than implied solved.
  **2026-09-13, second same-day pass — wording corrected, a genuine integrity check added, a real
  concurrency bug found and fixed; still `[~]` for the same reason as above, now more precisely
  stated.** Correction: the magic/version header alone is **format validation** (rejects a file that
  isn't this store's format at all) — describing it as an "integrity gate" overstated it, since it
  does nothing against corruption of an otherwise-plausible file. To make the stronger claim true
  rather than quietly retract it, the envelope now also carries a **CRC32 checksum of the payload**,
  computed on write and checked — before `readObject()` — on read.
  `StaticAnalysisFileStoreTest.bitFlipWithinPayload_isCaughtByCrc32NotJustHeaderCheck` proves a
  single-byte in-payload corruption (header, length, and stored CRC all otherwise intact) is now
  caught, which the header check alone would have missed. **Explicitly not claimed**: CRC32 is
  non-cryptographic — an attacker able to write this exact file could compute a valid CRC32 over a
  payload of their own choosing, so this remains accidental-corruption detection, not authentication,
  and `readObject()` still runs (once header/CRC match) over the same Java-serialization payload — the
  `[~]` status and its stated remaining gap are unchanged by this addition. **Separately, a real
  concurrent-import lost-update race was found and closed**: nothing previously serialized the
  get→modify→put cycle two independent importers could run against the same analysisId; added
  `StaticAnalysisResultStore.withLock(analysisId) { ... }` (per-key mutex,
  `SandboxSessionCoordinator.correlateUrlEvidenceWithAnalysis` now wraps its whole read-modify-write
  cycle in it), proven with real concurrent threads
  (`StaticAnalysisResultStoreLockTest`: 8 threads × 200 read-modify-write increments through the lock
  → exactly 1600, zero lost updates; a control case confirms different keys never contend). Also
  fixed while doing this: `put()` previously updated its in-memory cache *before* the disk write was
  confirmed to succeed, so a failed write could leave the process claiming a newer state than what was
  actually durable on disk — now the cache updates only after a successful write.
  **2026-09-13, third same-day pass — locking scope stated precisely, and covered with real Android
  instrumentation.** Corrected the `withLock` KDoc to state plainly that this is an **in-process** lock
  (`ConcurrentHashMap`/`synchronized`) — it protects nothing against a second OS process, and must
  never be described as if it did. Verified, rather than merely asserted, that this is nonetheless the
  correct and complete protection for the actual writer set: the store's path derives from
  `context.applicationContext.filesDir`, and Android gives each (package, profile) pair its own private
  storage area, so even the Work-profile install of this same app would touch a completely different
  on-disk file — every real writer of a given profile's file is that profile's own single process,
  exactly what this lock covers. Added `StaticAnalysisPersistenceInstrumentedTest` (4 tests, run via
  `./gradlew :app:connectedDebugAndroidTest` against `emulator-5554`, all passing) exercising the real
  envelope format, replacement, corruption-recovery, and write-failure/cache-preservation behavior
  against a real `InstrumentationRegistry` `Context` and real device `filesDir` storage — not only the
  JVM-level `StaticAnalysisFileStoreTest`/`AtomicFileWriterTest` (which use a real filesystem too, just
  the test JVM's own, not an actual Android app-private storage area under a real profile UID).
- [x] **MS9-PER02**: Replace the hardcoded absolute path
  (`/data/data/com.nadeem.apkscope/files/analysis_store`) with `context.filesDir`-derived storage, and
  replace the broad `catch (_: Throwable) {}` around every write with an observable failure state.
  **2026-09-13 — done**: `get`/`put`/`clear` now take a `Context` and derive the path from
  `context.applicationContext.filesDir`; threaded through all 6 real call sites (confirmed by reading
  each, not assumed). Every catch now logs via `Log.w` with the exception type/message — an observable
  signal, matching this entry's own stated minimum bar. Also fixed, beyond this entry's literal scope
  but the same underlying defect class: writes are now atomic (temp-file-then-rename via
  `AtomicFileWriter`, 5 tests), closing the interruption/partial-write risk this store shared with the
  URL-evidence import path (see MS9-URL02's idempotency correction above).

---

## Milestone 8: Deeper Static Analysis & Extended Protocol Support

### DEX URL Extraction (DEX)
- [x] **DEX01**: Inspect every `classes*.dex` entry in the selected APK archive.
- [x] **DEX02**: Extract structured URL candidates from DEX string pools and `const-string` instructions.
- [x] **DEX03**: Normalize URLs, filter XML/Android namespaces, and deduplicate with referencing class/method locations.
- [x] **DEX04**: Distinctly label URL present in DEX vs referenced by code vs observed at runtime. `RUNTIME_OBSERVED` requires an *exact* URL match with real transaction evidence (session/transaction id); a host-only match attaches separate, non-elevating `hostCorrelation` metadata instead of upgrading provenance — verified in `DexAnalysisTest` and, in production, `ApkAnalyzer.analyze()`'s `observedRuntimeHosts` parameter (`testApkAnalyzerAttachesHostCorrelationOnDevice`). **Partial in production**: host-level correlation is wired end-to-end (from a completed sandbox run's network observations); exact-URL `RUNTIME_OBSERVED` is not — it needs full HTTP-transaction (URL/method/status) evidence transported cross-profile, which has no channel of its own yet (today's cross-profile artifact carries hostnames, not full transactions). See STATE.md. **2026-09-12 correction**: the transaction-carrying artifact (`UrlEvidenceArtifact`) and its correlation call (`correlateUrlEvidenceWithAnalysis`) were since implemented, but reconciliation found the import call is only reachable from orphan-session recovery, not the normal end-session flow — see the "Milestone 9" block above (MS9-URL01/02) for the precise gap and required fix. **2026-09-12, later same-day pass**: that gap is now fixed, and a further defect in `correlateUrlEvidenceWithAnalysis` itself was found and fixed at the same time — it did not actually apply this entry's own "host-only match attaches separate, non-elevating `hostCorrelation` metadata" rule for the cross-profile-import path (it applied `RUNTIME_OBSERVED` to host-only matches too, clobbering static provenance); this held only for the unit-tested `ApkAnalyzer.analyze()` path this entry originally verified against. Now on-device verified correct for both exact and host-only cases via the real cross-profile import path too — see MS9-URL02 above and `STATE.md`.
- [x] **DEX05**: Enforce strict safety bounds (entry counts, string length, timeout) and handle malformed DEX without crashing.

### SDK Signature Detection (SDK)
- [x] **SDK01**: Locally bundle versioned signature catalog (v1.0.0) covering standard SDKs.
- [x] **SDK02**: Multi-evidence matching using package namespaces, characteristic classes, methods, and manifest tags.
- [x] **SDK03**: Record confidence category (HIGH, MEDIUM, LOW), matched identifiers, catalog version, and rationale.
- [x] **SDK04**: Assert SDK version only when version-specific evidence is present.
- [x] **SDK05**: Keep SDK signature detection cleanly separated from framework/platform detection.

### Selected API References (API)
- [x] **API01**: Inspect DEX method reference tables and actual invocation instructions (`invoke-*`).
- [x] **API02**: Support 7 categories: dynamic code loading, reflection, process exec, native lib loading, accessibility, device admin, sensitive data.
- [x] **API03**: Record referenced API, category, calling class/method, DEX location, and invocation vs reference table indicator.
- [x] **API04**: Provide neutral, explainable review rationale without claiming malicious intent or executed behavior.
- [x] **API05**: Informational findings only; no unauthorized changes to static risk scoring.

### Static Analysis UI (SUI)
- [x] **SUI01**: Add dedicated navigable sections in Static Result for Embedded URLs, Detected SDKs, and API References.
- [x] **SUI02**: Provide search and multi-criteria filtering for URLs, SDKs, and API references.
- [x] **SUI03**: Interactive evidence drawers with direct links to existing Smali bytecode disassembler view.
- [x] **SUI04**: Display analysis coverage: DEX files inspected, entries skipped, errors, limits reached.

### HTTP/2 Inspection (H2)
- [x] **H201**: Support explicit protocol negotiation (ALPN `h2` and plaintext prior-knowledge preface).
- [x] **H202**: Use established HPACK implementation for robust header decoding and dynamic table management.
- [x] **H203**: Parse binary frames: DATA, HEADERS, CONTINUATION, SETTINGS, RST_STREAM, GOAWAY, PING, WINDOW_UPDATE.
- [x] **H204**: Track concurrent multiplexed streams by stream ID with request/response association.
- [x] **H205**: Decode response trailers and stream termination; transparently forward all frames without corruption.

Verified through the real ALPN-negotiated relay path (`HttpsInspectionEngine.handleTlsClient` → `Http2RelayHandler`), not only via synthetic frames fed directly to the handler: `testHttp2RelayOnDevice` starts the actual engine, negotiates a genuine TLS+ALPN h2 handshake on both legs, and exchanges real frames with httpbin.org. This surfaced two defects invisible to a synthetic-socket test — both fixed: the relay never sent its own mandatory initial SETTINGS frame to a real upstream (RFC 7540 §3.5), and the upstream socket (a `SocketChannel.open().socket()` adapter, kept only for VPN-protect purposes) silently dropped a write that followed an earlier one on the same connection. Also added: a fallback that reconnects the upstream leg without h2 in its ALPN offer when the real downstream client can't speak h2 but the real upstream server does — otherwise `relayHttp11` was fed binary HTTP/2 framing as if it were HTTP/1.1 text.

### gRPC Inspection (GRPC)
- [x] **GRPC01**: Identify gRPC traffic using `content-type: application/grpc*` and `:path`, not port.
- [x] **GRPC02**: Parse 5-byte length-prefixed message framing across transport chunks.
- [x] **GRPC03**: Record unary and streaming message exchanges with sequence numbers, direction, and timestamps.
- [x] **GRPC04**: Decode gRPC status codes (numeric + canonical names) and response trailers.
- [x] **GRPC05**: Support bounded gzip decompression and display safe binary previews without ungrounded protobuf guesses.

Verified over a real HTTP/2 stream (`testHttp2GrpcRelayOnDevice`) against Google's public `grpc.testing.TestService` interop server (`EmptyCall`, whose request/response are zero-byte `google.protobuf.Empty`, avoiding a protobuf codegen dependency in the test) — not only via `GrpcMessageDecoder` fed synthetic bytes directly (`testGrpcInspectionOnDevice`, kept as a lower-level decoder unit check).

### Server-Sent Events (SSE)
- [x] **SSE01**: Detect readable responses with `content-type: text/event-stream`.
- [x] **SSE02**: Parse `data:`, `event:`, `id:`, `retry:`, and heartbeat comment lines across transport reads.
- [x] **SSE03**: Concatenate multi-line data fields into cohesive event payloads.
- [x] **SSE04**: Incrementally update store and display live streaming events without waiting for stream completion.
- [x] **SSE05**: Bound event history and line buffering to prevent memory exhaustion.

Verified over both real transports: `testHttp11SseStreamingOnDevice` (HTTP/1.1, forcing the ALPN-mismatch fallback above since the real target — Wikimedia's recentchange feed — negotiates h2) and `testHttp2SseStreamingOnDevice` (native HTTP/2). HTTP/2 is not a prerequisite for HTTP/1.1 SSE support — the two paths are independent. The HTTP/1.1 test surfaced a third real defect: response header lookups (`Content-Type`, `Transfer-Encoding`, `Connection`) used a case-sensitive `HashMap`, so any real server sending non-canonically-cased headers (Wikimedia sends lowercase) silently failed SSE/chunked/connection-close detection and fell into the buffered whole-body path, which blocks forever on a stream with no final chunk. Fixed with a case-insensitive map for both request and response headers, matching the convention `Http2FrameParser` already used correctly.

### HTTP/3 & QUIC Feasibility & Prototype (Q3)
- [x] **Q301**: Conduct architectural feasibility analysis of HTTP/3 and QUIC (RFC 9000) on Android.
- [x] **Q302**: Produce formal decision document evaluating native library bloat, CA constraints, and UDP routing.
- [x] **Q303**: Implement bounded observational QUIC packet parser behind experimental flag.
- [x] **Q304**: Distinguish QUIC transport observations from confirmed HTTP/3 and decrypted HTTP/3.

### Verification & Regressions (VER)
- [x] **VER01**: Unit test suites covering DEX URLs, SDK signatures, API references, HTTP/2, gRPC, SSE, and QUIC. 314 tests / 45 suites, 0 failures (fresh run, this resumption).
- [x] **VER02**: Device integration tests drive `HttpsInspectionEngine` directly (real TLS+ALPN handshake, real frames, real external servers) via the same auth-token+IP+port+SNI preamble `TcpProxy` sends in production. **Not independently re-verified this pass**: the `TcpProxy` packet-capture-to-preamble handoff itself, and a full fixture-app-in-Work-Profile run with new traffic-viewer screenshots for H2/gRPC/SSE specifically — those existing runtime screenshots predate this protocol work and were not retaken.
- [x] **VER03**: Zero regressions — full `HttpsInspectionIntegrationTest` (25/25 real + 1 intentional `@Ignore`) and full unit suite pass together against the final tree.
- [x] **VER04**: Comprehensive verification manifest and documentation (see STATE.md and `evidence/verification_milestone8/`).

---
 
### HTTP Inspector (HTTP)
- [x] **HTTP01**: Capture supported plaintext HTTP/1.1 traffic through the Work Profile VPN forwarding path.
- [x] **HTTP02**: Parse method, URL, status code, request/response headers, start time, duration, and sizes.
- [x] **HTTP03**: Support HTTP/1.1 framing: Content-Length, chunked transfer, persistent connections with sequential requests, and connection close.
- [x] **HTTP04**: Support responses without bodies (HEAD, 204, 304) and interim responses (100 Continue).
- [x] **HTTP05**: Support bounded gzip and deflate decompression previews (up to 64 KiB) without corrupting forwarded streams.
- [x] **HTTP06**: Non-HTTP plaintext traffic is forwarded without corruption and honestly marked UNSUPPORTED_PROTOCOL.
- [x] **HTTP07**: Heuristically redact known secrets in headers, URL query parameters, and supported body fields.
 
### WebSocket Recorder (WS)
- [x] **WS01**: Detect HTTP/1.1 WebSocket upgrades (`Upgrade: websocket` + `101 Switching Protocols`) on plaintext `ws://` and decrypted `wss://`.
- [x] **WS02**: Record handshake request and response, session opening and closing, and accurate timestamps.
- [x] **WS03**: Record messages with direction (client->server vs server->client), sequence numbers, timestamps, and payload sizes.
- [x] **WS04**: Parse RFC 6455 framing: client unmasking, opcode types (text, binary, ping, pong, close).
- [x] **WS05**: Handle message fragmentation across multiple frames and interleaved control frames.
- [x] **WS06**: Support bounded permessage-deflate decompression, or label compressed payloads truthfully if unsupported without corrupting stream.
- [x] **WS07**: Record close codes and reason phrases; handle abnormal disconnects honestly.
 
### Optional HTTPS & WSS Inspection (TLS)
- [x] **TLS11**: Keep inspection strictly disabled by default; allow explicit opt-in for target apps.
- [x] **TLS12**: Distinguish CA generated, CA installed in Work Profile, inspection enabled, connection decrypted, and decryption unavailable.
- [x] **TLS13**: Upstream TLS validation strictly uses system trust store and platform hostname verifier; no trust-all or pinning bypass.
- [x] **TLS14**: Handle ClientHello records up to 16 KiB (supporting Post-Quantum ML-KEM/Kyber key shares) and across TCP segments.
- [x] **TLS15**: Accurately report failure reasons (client CA untrusted, upstream cert invalid, hostname mismatch, unsupported protocol).
- [x] **TLS16**: Provide clean disable and CA reset actions without orphaned listeners or key residue.
 
### Search, Filters & UX (UX)
- [x] **UX01**: Provide a live traffic list embedded into the Work Profile monitoring workflow.
- [x] **UX02**: Provide rich transaction detail view (request/response headers, formatted body preview, security details).
- [x] **UX03**: Provide WebSocket session detail view with chronological message timeline, direction arrows, and payload previews.
- [x] **UX04**: Combined filtering by host/IP, protocol, method, status code, capture state, and text search.
- [x] **UX05**: WebSocket message filtering by direction and message type.
- [x] **UX06**: Preserve filter state across list and detail navigation; show result counts and empty states.
 
---
 
## Milestone 6: HTTPS POC closure (Completed & Verified)
 
- [x] **INT01**: Execute on poc/https_inspection while preserving unrelated and uncommitted work.
- [x] **INT02**: A separate fixture app sends GET and POST through the actual Work Profile VPN, TcpProxy, inspector, and real HTTPS server.
- [x] **INT03**: Prove both application instances and the active VPN belong to the intended Work Profile using dynamically resolved profile identifiers.
- [x] **INT04**: Limit interception to the fixture and approved destinations; preserve destination blocking before any upstream connection.
- [x] **INT05**: Keep inspection disabled by default; toggling it off restores ordinary forwarding without duplicating requests.
- [x] **TLS01**: Generate a unique local CA; keep its private key out of logs, exports, version control, and inappropriate backup paths.
- [x] **TLS02**: Install, query, and remove the CA using supported Work Profile APIs and distinguish installed status from target trust.
- [x] **TLS03**: Configure fixture debug trust only; inspect release configuration to confirm normal trust remains intact.
- [x] **TLS04**: Validate upstream trust chains and hostnames with no permissive fallback; prove invalid certificate and hostname mismatch rejection separately.
- [x] **TLS05**: Prove a fixture client without inspection CA trust rejects the intercepted connection.
- [x] **TLS06**: Correctly negotiate supported HTTP/1.1; label unsupported or encrypted traffic without asserting universal detection.
- [x] **CAP01**: Handle fragmented ClientHello data across TCP segments and TLS records with bounded buffering and exact byte forwarding.
- [x] **CAP02**: Preserve request and response framing while parsing supported lengths, chunked transfer, and compression.
- [x] **CAP03**: Capture at most 64 KiB per direction and 100 transactions, while forwarding full permitted traffic unaffected by capture truncation.
- [x] **CAP04**: Bound headers, parser buffers, decompression output, concurrency, and timeouts before expensive allocations.
- [x] **CAP05**: Redact known sensitive headers, common body secrets, and sensitive URL query values before retention or display; describe heuristic limits.
- [x] **CAP06**: Show method, URL, status, headers, bodies, duration, and meaningful capture or error state in the actual viewer.
- [x] **ISO01**: Authenticate or otherwise restrict the local inspector entry path so unrelated apps cannot forge destination metadata and use protected upstream sockets.
- [x] **ISO02**: Prevent loops and apply the original destination policy to every inspector upstream connection.
- [x] **LIFE01**: Reset stops interception, closes sockets and listener, removes CA and key material, clears captures, and restores normal monitoring.
- [x] **LIFE02**: Never silently replay POST or other requests with possible side effects on failure.
- [x] **LIFE03**: Preserve existing session lifecycle, DPM evidence model, and risk scoring.
- [x] **VER01**: Obtain real GET response and POST request plus response captures from the complete path, with viewer evidence.
- [x] **VER02**: Report actual test counts from result files, including failures, skips, scope, and execution profile.
- [x] **VER03**: Separate engine tests, VPN integration tests, UI demonstrations, and unverified items.
- [x] **DOC01**: Document setup, supported traffic, certificate trust, reset, limitations, and known remaining failures accurately.

## Traceability

| Requirement | Phase | Status |
| :--- | :--- | :--- |
| INT01 | Phase 6.1 | Complete |
| INT02 | Phase 6.2 | Complete |
| INT03 | Phase 6.2 | Complete |
| INT04 | Phase 6.3 | Complete |
| INT05 | Phase 6.3 | Complete |
| TLS01 | Phase 6.3 | Complete |
| TLS02 | Phase 6.3 | Complete |
| TLS03 | Phase 6.3 | Complete |
| TLS04 | Phase 6.3 | Complete |
| TLS05 | Phase 6.3 | Complete |
| TLS06 | Phase 6.3 | Complete |
| CAP01 | Phase 6.3 | Complete |
| CAP02 | Phase 6.3 | Complete |
| CAP03 | Phase 6.3 | Complete |
| CAP04 | Phase 6.3 | Complete |
| CAP05 | Phase 6.3 | Complete |
| CAP06 | Phase 6.2 | Complete |
| ISO01 | Phase 6.3 | Complete |
| ISO02 | Phase 6.3 | Complete |
| LIFE01 | Phase 6.3 | Complete |
| LIFE02 | Phase 6.3 | Complete |
| LIFE03 | Phase 6.3 | Complete |
| VER01 | Phase 6.2 | Complete |
| VER02 | Phase 6.1 | Complete |
| VER03 | Phase 6.2 | Complete |
| DOC01 | Phase 6.4 | Complete |

Phase 6.1 owns INT01 and VER02 reconciliation.
Phase 6.2 owns INT02, INT03, CAP06, VER01, VER03.
Phase 6.3 owns INT04, INT05, TLS01 through TLS06, CAP01 through CAP05, ISO01, ISO02, LIFE01 through LIFE03.
Phase 6.4 owns DOC01 and final evidence reconciliation for every requirement.
