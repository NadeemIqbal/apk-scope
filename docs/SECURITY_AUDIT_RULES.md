# Security Audit Rule Catalog

Versioned, human-readable catalog for the Security Audit engine (`security-audit-v1`, implemented in
`core/risk/src/main/kotlin/com/nadeem/apkscope/core/risk/audit/`). See
[Security Audit](SECURITY_AUDIT.md) for the product-level overview and
[Security Audit Verification](SECURITY_AUDIT_VERIFICATION.md) for how each rule's status is verified
and what evidence it requires.

> [!IMPORTANT]
> This catalog is independent of the existing `static-v1` / `runtime-v1` / `combined-v1` risk score
> (see [Risk Scoring](RISK_SCORING.md)). Every audit rule below produces three **independent** values —
> **outcome**, **severity**, and **confidence** — never a single numeric weight. The two systems may
> share underlying facts (e.g. both look at `debuggable`) but never share a rule ID, and a change to
> one never changes the other's output.

## Outcome, severity, and confidence are independent axes

A rule's outcome answers "what did the check find?"; severity answers "how bad, if found?"; confidence
answers "how sure are we?" Conflating these (an earlier draft of this catalog used a single PASS/WARN/
FAIL value) loses the difference between "this app is fine" and "this catalog could not check that."

### Outcome vocabulary

| Outcome | Meaning |
| :--- | :--- |
| `FINDING_DETECTED` | The rule's trigger condition was met — something this catalog considers worth surfacing was found. |
| `CHECK_PASSED` | The rule was evaluated and did not fire, within its actual scope. A real, reportable outcome — not the absence of a check. |
| `NEEDS_REVIEW` | The condition is real but common enough, or has enough legitimate uses, that a human should decide rather than the catalog asserting a clean pass or a flagged finding on its own. |
| `NOT_TESTED` | This rule was not evaluated for this analysis (e.g. requires guided-session evidence that does not exist yet). The rule *could* apply — it just was not run. |
| `NOT_APPLICABLE` | This rule's precondition does not hold for this specific APK — not merely unrun, genuinely not meaningful here. |
| `COLLECTION_FAILED` | The rule attempted to evaluate but could not obtain reliable input (parse failure, truncated artifact, bounds limit reached). Distinct from a clean pass. |

A passed check always reports `Severity.INFO` — a rule that found nothing must never carry a
non-trivial severity (enforced by `StaticAuditRulesTest.everyPassedCheck_carriesInfoSeverity`).

### Severity

`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `INFO` — meaningful only for `FINDING_DETECTED`/`NEEDS_REVIEW`.

### Confidence

`HIGH`, `MEDIUM`, `LOW` — independent of severity. A `HIGH`-severity finding read directly off a flag
(e.g. `debuggable`) carries `HIGH` confidence; a `HIGH`-severity finding inferred from a declaration
that does not itself prove runtime behavior (e.g. `usesCleartextTraffic`, which permits but does not
prove a plaintext transaction occurred) carries `MEDIUM` confidence until a guided-session rule
corroborates it.

## v1 static audit rules

Evaluated entirely from `ApkAnalysisInput` — the same pure, declared-facts-only snapshot `core:risk`'s
existing static engine consumes (see `core/model/.../ApkAnalysisInput.kt`).
`usesCleartextTraffic` and `allowBackup` were added to this snapshot specifically for this catalog
(sourced from `ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC`/`FLAG_ALLOW_BACKUP` in `ApkAnalyzer`,
mirroring how `debuggable` is already read) — no other new static extraction was added.

| Rule ID | Outcome when triggered | Severity | Confidence | Trigger condition | Rationale |
| :--- | :---: | :---: | :---: | :--- | :--- |
| `AUDIT_DEBUGGABLE_BUILD` | FINDING_DETECTED | HIGH | HIGH | `android:debuggable="true"` | A debuggable release should never reach end users; a debugger can attach and the platform's release-build data protections do not apply. |
| `AUDIT_SIGNATURE_INTEGRITY` | FINDING_DETECTED | CRITICAL | HIGH | APK signature could not be verified | Every other finding in the report describes content whose origin and integrity are otherwise unconfirmed. |
| `AUDIT_EXPORTED_SURFACE_RATIO` | NEEDS_REVIEW | MEDIUM | HIGH | More than half of declared components are exported | An exported component alone is not a vulnerability — worth a human review of each one's own access control, not an automatic finding. |
| `AUDIT_ACCESSIBILITY_OVERLAY_TAPJACKING` | FINDING_DETECTED | HIGH | HIGH | Declares both `BIND_ACCESSIBILITY_SERVICE` and `SYSTEM_ALERT_WINDOW` | The specific combination behind overlay/tapjacking-style attacks. |
| `AUDIT_SMS_INTERNET_EXFIL_SURFACE` | NEEDS_REVIEW | MEDIUM | MEDIUM | Declares SMS read/receive/send **and** `INTERNET` | The pairing needed to intercept and exfiltrate SMS content — but also legitimately used for OTP autofill, hence review rather than an automatic finding. |
| `AUDIT_BOOT_PERSISTENCE_NETWORK` | NEEDS_REVIEW | LOW | MEDIUM | Declares `RECEIVE_BOOT_COMPLETED` **and** `INTERNET` | Can start on every device boot without user action and has a network path — common for legitimate background services. |
| `AUDIT_OUTDATED_TARGET_SDK` | NEEDS_REVIEW | LOW | HIGH | `targetSdkVersion` < 29 (Android 10) | Opts out of scoped storage, background-location prompts, and other privacy defaults tightened from API 29 onward. |
| `AUDIT_NATIVE_CODE_UNVERIFIED` | NOT_TESTED | INFO | HIGH | Declares one or more native library ABIs | States honestly that this catalog's static checks cannot see inside native code; untested, not "checked and clean," until a guided runtime session observes it. |
| `AUDIT_CLEARTEXT_TRAFFIC_ENABLED` | FINDING_DETECTED | HIGH | MEDIUM | `android:usesCleartextTraffic` resolves to `true` | Permits plaintext HTTP by declaration. Confidence is MEDIUM — this confirms the declaration, not an observed transaction (see `AUDIT_RUNTIME_CLEARTEXT_OBSERVED` below). |
| `AUDIT_BACKUP_ENABLED` | NEEDS_REVIEW | LOW | HIGH | `android:allowBackup` resolves to `true` (including the platform default when unspecified) | App data may be extracted via `adb backup` or platform auto-backup — legitimate for many apps, so review rather than an automatic finding. |

**Coverage note:** these 10 rules are Phase 10.1's static catalog — manifest and platform
configuration checks only (Section 9(a) of the Security Audit specification). Secret-candidate
scanning (9b), network trust indicators (9c), code-pattern detection (9d), and build-protection/
dependency checks (9e) are separate, larger pieces of work, not yet started — see
`.planning/REQUIREMENTS.md`'s Milestone 10 block and `.planning/STATE.md` for their exact status.

## Planned, not yet implemented

Listed here so the catalog's intended shape is visible before the phases/requirements that build them —
these do **not** exist in `security-audit-v1` yet and must not be reported as implemented until their
own work closes with evidence.

| Planned rule ID | Category | What it needs |
| :--- | :--- | :--- |
| `AUDIT_CORR_DECLARED_NOT_EXERCISED` | Correlation | A declared dangerous capability with no corresponding guided-session evidence — `NOT_APPLICABLE` until a session exists, then `NEEDS_REVIEW` if still unexercised after one. |
| `AUDIT_CORR_EXERCISED_CONFIRMED` | Correlation | Guided-session evidence corroborating a declared capability actually being used — `CHECK_PASSED`-equivalent confirmation once observed. |
| `AUDIT_RUNTIME_CLEARTEXT_OBSERVED` | Runtime (guided) | An actual plaintext HTTP transaction observed during a guided session — the runtime counterpart to `AUDIT_CLEARTEXT_TRAFFIC_ENABLED`'s static declaration, raising its confidence to HIGH once corroborated. |
| `AUDIT_RUNTIME_PRIVATE_NETWORK_PROBE` | Runtime (guided) | An observed connection attempt to an RFC 1918 destination during a guided session, mirroring `runtime-v1`'s `RUNTIME_RFC1918_ATTEMPT` but as an individually reportable audit finding tied to a specific guided step. |
| Secret-candidate rules | Static (DEX/resources/assets) | DEX/resource/asset scanning for private keys, credentials, tokens — contextual confidence, masked output, public-identifier differentiation. Section 9(b), not started. |
| Network trust indicator rules | Static (manifest + resources) | Network security config presence/pin declarations, trust-manager/hostname-verifier/pinning code references. Section 9(c), not started. `ApkMetadata.networkSecurityConfigPresent` is currently hardcoded `null` — a real prerequisite gap, not a rule-catalog gap. |
| Code-pattern rules | Static (DEX bytecode) | A documented, bounded crypto/WebView pattern catalog distinguishing reference-present from reachable-behavior. Section 9(d), not started. |
| Build-protection/dependency rules | Static (DEX + optional build artifacts) | Obfuscation indicators, SDK-version-backed vulnerability matching with cited advisory source/catalog version. Section 9(e), not started. |

## Versioning

Catalog version: **`security-audit-v1`** (`SECURITY_AUDIT_ENGINE_VERSION` in `AuditModels.kt`). Bump this
constant whenever a rule is added, removed, or has its trigger condition changed after this catalog has
a real consumer (a persisted report, a shipped release) — not for same-pass revisions during initial
construction, which this document's own history (8 rules → 10 rules, PASS/WARN/FAIL → outcome/severity/
confidence, all before any report was ever persisted under the name) is. A persisted `SecurityAuditReport`
records the engine version it was produced under so it can never be silently reinterpreted as if a later
catalog produced it (same discipline as `core:risk`'s `RISK_ENGINE_VERSION` / `RUNTIME_ENGINE_VERSION` /
`COMBINED_ENGINE_VERSION`).

## Standards mapping and rule dossier (v1 static rules)

Retrieved 2026-09-14 via live fetch/search against `mas.owasp.org` (OWASP MASVS v2 / MASTG / MASWE
current site) — not from memorized IDs. Two IDs initially assumed from training-data recollection were
found **wrong** on verification (`MASTG-TEST-0025` recalled as "debuggable" is actually "Testing for
Injection Flaws"; `MASTG-TEST-0035`/`MASTG-TEST-0029`, initially assumed current, are **deprecated** on
the live site) — corrected below to the site's actual current IDs. This is exactly the failure mode
Section 15 exists to prevent; do not add a citation to this table without a live check.

| Rule ID | Evidence basis | Applicability | Confidence rationale | Limitations | Remediation | Official reference |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `AUDIT_DEBUGGABLE_BUILD` | `ApplicationInfo.FLAG_DEBUGGABLE`, read directly, not inferred | Any APK | HIGH — a platform flag, not a heuristic | Says nothing about whether debug symbols/logging were also stripped (separate concern) | Remove/omit `android:debuggable` (or set `false`) for release builds | **MASTG-TEST-0039** "Testing whether the App is Debuggable" (MASVS-RESILIENCE); **MASTG-BEST-0007** "Debuggable Flag Disabled in the AndroidManifest" |
| `AUDIT_SIGNATURE_INTEGRITY` | This tool's own signature-verification result (`SigningResult`) | Any APK | HIGH — verification either succeeds or reports a named failure reason | Verifies *this analysis's* input integrity, not a property of the target app's own security design | Re-obtain the APK from a trusted source; do not analyze or trust results from an unverifiable artifact | **Project-specific** — no direct MASVS control grades "was the artifact I'm analyzing tamper-evident," since MASVS assesses the target app's own implementation, not the auditor's input-handling |
| `AUDIT_EXPORTED_SURFACE_RATIO` | `ComponentDescriptor.exported` counts from the manifest | Any APK with ≥1 component | MEDIUM — a ratio heuristic, not an assessment of what each exported component actually does | Does **not** assess whether each exported component enforces its own access control (permission/signature check) — a low ratio with one badly-exposed component is missed | Review each exported component's own protection; add `android:permission` or an explicit signature-level check where public exposure isn't required | **Related, not exact**: **MASTG-TEST-0007** "Determining Whether Sensitive Stored Data Has Been Exposed via IPC Mechanisms" (MASVS-PLATFORM) covers IPC/exported-component exposure of *sensitive data* specifically, narrower than this rule's coarse ratio; official Android reference: [`android:exported`](https://developer.android.com/guide/topics/manifest/activity-element#exported) attribute docs |
| `AUDIT_ACCESSIBILITY_OVERLAY_TAPJACKING` | Manifest permission co-declaration | Any APK declaring either permission | HIGH for the declaration; the combination itself is a heuristic correlation | Declaring both permissions does not prove either is used together at runtime for a tapjacking-style interaction — a static co-declaration signal only | Avoid requesting both unless both are functionally required; if both are needed, document why and consider `HIDE_OVERLAY_WINDOWS`/`setHideOverlayWindows` (API 31+) | **MASWE-0056** "Tapjacking Attacks" (MASVS-PLATFORM); **MASTG-KNOW-0022** "Overlay Attacks" knowledge page. Note: `MASTG-TEST-0035` (the older overlay test) is **deprecated** on the live site — do not cite it. The specific *accessibility+overlay co-declaration* correlation is **project-specific**, layered on top of the general tapjacking weakness reference. |
| `AUDIT_SMS_INTERNET_EXFIL_SURFACE` | Manifest permission co-declaration | Any APK declaring SMS + INTERNET | MEDIUM — legitimate OTP-autofill use is common | A static co-declaration only; does not observe whether SMS content is actually read and transmitted (that requires a guided-session runtime rule, not yet implemented) | Scope SMS access with the more restrictive SMS Retriever API where OTP autofill is the actual goal, avoiding broad `READ_SMS` | **Project-specific** permission-combination heuristic, mirroring `core:risk`'s existing `COMBINED_SMS_NETWORK` design precedent — no single MASVS/MASTG test names this exact pairing; the individual permissions' general sensitivity falls under MASVS-PLATFORM-1's spirit |
| `AUDIT_BOOT_PERSISTENCE_NETWORK` | Manifest permission co-declaration | Any APK declaring both | MEDIUM — common for legitimate background services | Static co-declaration only; does not observe actual boot-time network activity | Document the legitimate need for auto-start + network; consider deferring network access until explicit user interaction | **Project-specific**, same reasoning as `AUDIT_SMS_INTERNET_EXFIL_SURFACE` — mirrors `core:risk`'s `COMBINED_BOOT_PERSISTENCE_NETWORK` precedent |
| `AUDIT_OUTDATED_TARGET_SDK` | `targetSdkVersion`, read directly | Any APK | HIGH for the value itself; MEDIUM for the specific severity of the consequences at this project's chosen threshold | This project's threshold (API 29) is its own choice for general privacy-hardening defaults, distinct from the specific API-24 threshold MASTG discusses for Network Security Config trust-anchor behavior — the two thresholds address different platform behaviors, not the same one | Raise `targetSdkVersion` to the current supported range; re-test after raising it, since raising target SDK can itself change runtime behavior (e.g. Network Security Config defaults) | **Related, not exact**: **MASTG-BEST-0010** "Use Up-to-Date minSdkVersion" (about `minSdkVersion`, not `targetSdkVersion`, though the underlying rationale — missing platform hardening on older API levels — is the same); official Android reference: [Meeting Google Play's target API level requirements](https://developer.android.com/google/play/requirements/target-sdk) |
| `AUDIT_NATIVE_CODE_UNVERIFIED` | Presence of native library ABIs | Any APK with native libs | HIGH for presence; the finding is explicitly a coverage disclosure, not a vulnerability claim | This is a statement about this catalog's own blind spot, not a security assessment of the native code itself | Not applicable as "remediation" — the actionable follow-up is exercising native-code paths via a guided runtime session, not changing the app | **Project-specific** coverage-limitation disclosure — MASTG's binary-analysis techniques (disassembly, native debugging) address native code review generally, but no single MASTG test corresponds to "did our static-only catalog have visibility into native code" |
| `AUDIT_CLEARTEXT_TRAFFIC_ENABLED` | `ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC`, read directly | Any APK | MEDIUM — confirms the declaration permits cleartext, not that a request actually used it | A `PASS` here does not prove no cleartext request will ever occur via other means (e.g. a WebView loading an explicit `http://` URL that Network Security Config per-domain rules don't cover); an observed-traffic runtime counterpart is planned (`AUDIT_RUNTIME_CLEARTEXT_OBSERVED`, not yet implemented) | Set `android:usesCleartextTraffic="false"` (or omit it on API 28+, where `false` is the default) and use HTTPS exclusively | **MASTG-TEST-0235** "Android App Configurations Allowing Cleartext Traffic" (MASVS-NETWORK) — direct match for this rule's exact static-declaration scope. Its dynamic counterpart, **MASTG-TEST-0236** "Cleartext Traffic Observed on the Network," is the correct future citation for the planned runtime rule. |
| `AUDIT_BACKUP_ENABLED` | `ApplicationInfo.FLAG_ALLOW_BACKUP`, read directly | Any APK | HIGH for the flag; the finding is a precursor signal, not a content assessment | This rule flags the *declaration* only — it does not inspect actual backup content for sensitive data, which is `MASTG-TEST-0009`'s fuller, dynamic scope (requires actually producing and inspecting a backup archive) | Set `android:allowBackup="false"`, or scope backup content with `android:fullBackupContent`/`backup_rules.xml` to exclude sensitive files | **MASTG-TEST-0009** "Testing Backups for Sensitive Data" (MASVS-STORAGE) — this rule implements that test's static precondition check; **MASTG-TEST-0262** "References to Backup Configurations Not Excluding Sensitive Data" is the closer match for a future rule that inspects `backup_rules.xml` content specifically |

**Verification method for the citations above**: `WebFetch`/`WebSearch` against `mas.owasp.org` on
2026-09-14 (this session). Re-verify before relying on these IDs in a future session — OWASP
periodically renumbers and deprecates MASTG test IDs (as the two corrections above demonstrate), and
this table does not self-update.

---
*Catalog defined: 2026-09-14, Phase 10.1. Implementation:
`core/risk/src/main/kotlin/com/nadeem/apkscope/core/risk/audit/`. Tests:
`core/risk/src/test/kotlin/com/nadeem/apkscope/core/risk/audit/` (33 tests, see
`.planning/phases/10.1-static-audit-engine/10.1-01-SUMMARY.md` for the run this was verified against).
Outcome/severity/confidence model adopted the same day, replacing an initial PASS/WARN/FAIL draft,
before any report was persisted under either version.*
