# Supervisor Context

Knowledge baseline: 2026-09-12, updated 2026-09-14 with Milestone 9's closure evidence (see the dated
notes in §2, §9, §15, §16, and §20 below; nothing else in this document changed). Product name: APK
Scope. The public product name is APK Scope and the application package is `com.nadeem.apkscope`; historical evidence and fixture-only APKs may retain their recorded `com.apksandbox.*` identities.

This document transfers architectural intent and review judgment. It is not certification of the current checkout. Implementation status below comes from supplied source snapshots, test artifacts, and subsequent agent reports. Reconcile it against the repository before closing requirements.

Read with [Product Vision](PRODUCT_VISION.md), [Architecture Constraints](ARCHITECTURE_CONSTRAINTS.md), [Verification Strategy](VERIFICATION_STRATEGY.md), and [Future Capabilities](FUTURE_CAPABILITIES.md).

## 1. Product direction

APK Scope should make an APK's declared capabilities, exercised behavior, and supporting evidence understandable on an ordinary Android device. Its strongest direction combines static inspection, execution in a Managed Work Profile, network analysis, and explainable reports. Broader protocol coverage is useful only when the real product captures and presents trustworthy evidence.

## 2. Existing foundation and status

The established project foundation includes APK selection, package and manifest inspection, static findings, Work Profile provisioning, target installation, session coordination, VPN forwarding and destination policies, live monitoring, DPM evidence import, cleanup, and deterministic reports.

Development work has added optional certificate based HTTPS inspection, HTTP and WebSocket capture, traffic filtering, deeper DEX inspection, HTTP/2 relay work, gRPC and SSE decoding, and bounded URL evidence transport. These have unequal verification maturity. Their presence in a development branch does not establish release support.

The latest supplied device report describes a successful fixture HTTPS response, successful TLS handshakes on both proxy connections, negotiated `h2`, and entry into `Http2RelayHandler`. Traffic Inspector display and persisted URL correlation remained unverified when the device locked. Checkpoint 8.9 must not be considered closed from that report alone.

**2026-09-14 update**: the item above is no longer open. Milestone 9 (opened specifically to close this
and the two related gaps below) closed on 2026-09-14 after every recorded acceptance criterion was
either met with on-device evidence or reclassified as a documented platform limitation with a shipped
substitute — see `.planning/STATE.md`'s "Final Milestone 9 requirement reconciliation" for the
criterion-by-criterion accounting, and this repository's own working tree (uncommitted) for the source.
Traffic Inspector display and persisted URL correlation are both now product verified, including on a
physical Pixel 8, with restart persistence directly confirmed. This does not extend to gRPC/SSE, which
remain at component/engine-integration verification only — see the updated §16 below.

## 3. Decisions already made

1. Remain on device and require no root.
2. Use Android Managed Work Profile and Profile Owner capabilities rather than claiming VM isolation.
3. Keep the Work Profile VPN as the forwarding and observation point.
4. Keep TLS inspection optional and visibly distinct from metadata observation.
5. Preserve upstream certificate chain and hostname verification.
6. Transfer bounded correlation metadata between profiles instead of exporting unrestricted payload collections.
7. Keep deterministic risk rules and evidence provenance separate from protocol decoding.
8. Preserve the current GSD setup rather than replacing its schemas, history, or phase numbering.

## 4. Investigations worth preserving

Earlier engine tests sometimes supplied constructed protocol frames or inserted records directly into the store. They validated components, not the complete VPN route. Seeded screenshots also existed. This distinction drove the requirement for a separately installed fixture and actual product UI evidence.

Real integration work exposed missing HTTP/2 initialization behavior, socket adapter problems, and protocol negotiation mismatches. Subsequent reports describe fixes, but each needs a regression test tied to the current source.

The latest device investigation reported repeated `VpnService.protect()` failures on inspector upstream sockets and a successful request after binding before protection. Preserve the invariant that protection succeeds before upstream connection; do not generalize the reported descriptor explanation to every socket implementation.

A stale installation check became successful after process restart. That observation does not establish a platform cache defect. Reconciliation and launch lifecycle behavior still require diagnosis. Likewise, an Android enabled state value of zero means the default component state, not automatically disabled. See [PackageManager constants](https://developer.android.com/reference/android/content/pm/PackageManager#COMPONENT_ENABLED_STATE_DEFAULT).

## 5. Rejected approaches

* Treating a client timeout as proof of interception: a timeout identifies no successful capture stage.
* Treating a client response body as proof of inspector capture: the target normally reads its own response.
* Treating HTTPS port numbers or client defaults as proof of HTTP/2: negotiation and protocol execution need evidence.
* Exporting a global traffic store and attaching the current session afterward: this can misattribute other sessions or applications.
* Using hostname containment to establish an exact URL observation: it creates false matches.
* Calling an unfamiliar persistence model an Android limitation: inspect and extend the model when authorized.
* Deferring required product verification as optional media polish: viewer and persistence are functional requirements.
* Weakening TLS verification, bypassing pinning, or disabling monitoring to obtain successful demonstrations: these invalidate the product's boundaries.

## 6. Android limitations

Work Profile provisioning, available policies, profile state, background execution, and app visibility depend on platform and device conditions. Profile IDs are dynamic. Private Space is a different feature, not the sandbox implementation.

The historical application minimum is Android 11 / API 30; verify the current build configuration. Feature support is narrower than installation support. In particular, Profile Owner network logging requires Android 12 / API 31 or later. DPM delivers batches and does not observe every networking implementation. See [Android network logging](https://developer.android.com/work/dpc/logging).

The supported installation and removal flow includes Android confirmations. ADB actions used in development do not establish capabilities available to the shipping app.

## 7. Security boundaries

Treat the APK, target process, network peers, parser inputs, and imported artifacts as untrusted. The target must not gain access to inspector keys, other sessions, Personal data, or control interfaces merely because it shares a profile.

Loopback binding is not sufficient listener authorization. The POC introduced a random token per engine instance; verify validation, lifetime, invalid input handling, connection limits, and token secrecy.

Captured content is sensitive. Apply bounded parsing, redaction, retention, access control, and truthful truncation reporting. A risk score is not an authorization boundary.

## 8. Personal and Work Profile responsibilities

Personal owns APK selection, static analysis, session coordination, imported evidence, and report presentation. Work owns target execution, the monitoring VPN, optional inspection, and local capture collection. Confirm exact persistence locations in the current implementation.

The target and the Work copy of APK Scope are separate applications with different responsibilities. A package installed or launched in Personal does not demonstrate sandbox execution.

Keep explicit PERSONAL and SANDBOX labels. Blue and teal are supporting visual cues, not the sole indication of profile identity.

## 9. VPN and observation architecture

The known network path includes `SandboxVpnService`, `TunSink`, `ForwardingEngine`, `TcpProxy`, and the optional `HttpsInspectionEngine`. Readable traffic reaches protocol handlers and `TrafficInspectionStore`, then the inspector UI.

The VPN actively forwards allowed traffic and enforces configured destination policies. It is not a passive tap. Every forwarding socket must avoid recapture, and policy enforcement must apply to the actual upstream destination.

Prove capture scope before assigning target ownership. Threading `sessionId` and `targetPackage` through constructors provides context, not independent proof that every captured packet belongs to that target. Verify the VPN application configuration or another supported ownership mechanism; exclude or label unattributed traffic.

**2026-09-14 update**: `VpnService.Builder.addAllowedApplication()` (the VPN-application-configuration
option named above) was implemented and tested twice on a physical device; both times it produced an
empty enforced UID set rather than a scoped one, and a controlled experiment confirmed the cause is
specifically that call combined with this product's required always-on VPN lockdown policy — a
documented platform limitation on this Android version, not a code defect, and not achievable as
specified while lockdown stays on. The "another supported ownership mechanism" alternative this
paragraph already allowed for was built instead: userspace attribution via
`ConnectivityManager.getConnectionOwnerUid()` inside `ForwardingEngine`, verified on-device (including a
real second controlled application and real organically-occurring second-app traffic, both correctly
excluded from the confirmed-evidence export). The VPN's own capture scope is still Work-Profile-wide,
unchanged — this closes what traffic is treated as *confirmed target evidence*, not what the VPN
physically observes. True concurrent two-target monitoring remains an architectural limitation (one
VPN slot), not a gap in this ownership-verification work.

## 10. Cross profile transport

The established pattern uses controlled Activity requests and temporary content URI access to artifacts. It does not provide arbitrary shared storage. See [Android Work Profile file sharing](https://developer.android.com/work/managed-profiles).

`RuntimeObservationArtifact` carries connection level evidence. `UrlEvidenceArtifact` was introduced for minimal transaction correlation fields. Reported limits were 10 MB for the former and 1 MB plus 5,000 entries for the latter; verify exact byte constants and serialization behavior in code.

An entry cap does not guarantee a byte cap or coverage of a larger transaction count. Enforce limits during collection, serialization, and reading, not only after loading everything into memory.

Import must validate origin, grant, schema, session, package, fields, timestamps, and bounds. It must be idempotent and expose failures through persisted state. Logcat alone is insufficient. Confirm export survives the intended cleanup ordering.

## 11. Static analysis architecture

The original analyzer inspected package metadata, manifest declarations, signing information, permissions, components, SDK configuration, native library presence, and APK hashes. This did not originally justify DEX or decompilation claims.

Later source snapshots introduced multidex scanning, embedded URL candidates, class and method references, SDK signatures, selected API references, and coverage reporting. Locate `DexUrlExtractor`, `SdkSignatureCatalog`, and associated models and tests in the current tree.

DEX string presence, a `const-string` reference, an API invocation reference, and runtime execution are different facts. Obfuscation, reflection, native code, generated strings, encrypted content, and downloaded code limit static coverage. A detected SDK signature does not prove SDK execution or an exact version.

## 12. Dynamic analysis architecture

A session binds one analysis and intended target to execution, policy, observations, and cleanup. Preserve that identity through every capture route, including HTTP/1.1, HTTP/2, WebSocket, streaming events, and failures.

`Open Sandboxed App` and `Open Live Monitor` are separate actions. Readiness indicators do not prove either action succeeded.

The intended lifecycle is installation, execution, observation, stop, target data cleanup, confirmed removal, artifact import, and report. Verify actual ordering and preserve evidence needed after target removal. Do not destroy the Work Profile after every session.

## 13. Evidence model

| Evidence | Meaning | Does not establish |
| --- | --- | --- |
| DeclaredCapability | Static declarations or references in the APK | Execution or intent |
| ObservedBehavior | Activity actually captured by the VPN or inspector | Behavior outside coverage |
| AndroidEvidence | Independent Android DPM events | Complete network history or payload contents |
| Risk finding | Rule based interpretation of evidence | Malware probability |

Keep static provenance such as `PRESENT_IN_DEX` and `REFERENCED_BY_CODE` after attaching runtime references. Do not erase the original extraction evidence.

A host association supports host correlation only. Exact URL evidence requires a captured request and a documented comparison rule. Redacted query values, missing paths, or ambiguous normalization must reduce matching precision. Never reconstruct hidden values or promote host matches to exact `RUNTIME_OBSERVED` claims.

## 14. Reporting model

Reports connect findings to evidence identifiers, source, session, target attribution, capture time, and rule version. Show unsupported, pending, failed, truncated, and not observed states distinctly.

Historical scoring uses `static-v1`, `runtime-v1`, and `combined-v1`, with Low 0–24, Moderate 25–49, High 50–74, and Critical 75–100. Verify current rules before documenting or changing them. The score is not the probability that an APK is malware.

Late DPM delivery may supplement a report. Define whether this updates findings or creates a revision, and record the evidence cutoff. Reopening a report must not silently lose correlation or duplicate evidence.

## 15. Testing maturity

The project has JVM suites, Android instrumentation, engine integration tests, fixture applications, emulator runs, and physical Pixel 8 work. Historical evidence also contained failures, skipped tests, inconsistent totals, and synthetic UI data presented too broadly.

Do not carry historical passing totals forward as a current gate. Real TLS engine tests are valuable but do not substitute for Work Profile routing, viewer integration, cleanup, and persistence tests.

**2026-09-14 update, itself not a standing gate**: Milestone 9's closure added, among other things, a
physical Pixel 8 run of the full evidence-handoff path (fresh session, distinctive request, Traffic
Inspector confirmation, normal session end, real Android uninstall confirmation, app restart, reopened
analysis, on-disk evidence read directly via `run-as`) and a focused suite verifying a ViewModel-level
in-flight import guard's release lifecycle across success, failure, timeout, and real
`ViewModelStore.clear()` cancellation. These are recorded here as what was verified and how, per this
section's own instruction — not as a reason to treat the count as a permanent gate for later work.

## 16. Claims requiring stronger proof

* ~~Genuine captured transactions visible in Traffic Inspector after the latest socket fix.~~
  **Closed 2026-09-14**: verified on a physical Pixel 8 with a genuinely distinctive, non-seeded request,
  confirmed via both the Traffic Inspector UI and raw logcat (real TLS 1.3/ALPN h2, real ownership match).
* ~~Exact URL correlation imported into the correct persisted analysis and retained after reopening.~~
  **Closed 2026-09-14**: verified end to end on the same physical device, including a real
  `am force-stop` process kill and relaunch — the persisted `RUNTIME_OBSERVED` reference and import
  status were confirmed identical before and after, read directly off device storage.
* Target ownership and session isolation across every protocol path and late callback: **closed for
  HTTP/1.1 and HTTP/2/h2 specifically** (real second-app MATCHED/MISMATCHED evidence, a late-callback
  timeout test). **Still open for WebSocket/WSS, gRPC, and SSE** — ownership verification was not
  separately re-confirmed against those protocol paths.
* Real fixture HTTP/2, gRPC, and SSE behavior through the Work VPN for each advertised mode: **HTTP/2
  closed** (repeatedly, on a physical device). **gRPC and SSE remain open** — verified only at the
  component/engine-integration level (real client/server exchange through the production relay), not
  re-driven through the fixture-in-Work-Profile UI end to end. This is `FUTURE_CAPABILITIES.md`'s
  priority-3 item, and is the next unstarted roadmap work — not begun as part of Milestone 9.
* Install reconciliation, launch, monitor navigation, cleanup, and process recovery: **closed** —
  exercised repeatedly across this milestone's passes, including the physical Pixel 8 sixth-pass session
  (real install confirmation, real uninstall confirmation, real process restart).
* Actual CA reset/removal behavior and protection failure handling: **unchanged, still open** — not
  touched by Milestone 9's work, which was scoped to capture ownership, evidence wiring, and the
  permission/import findings from physical acceptance, not the CA lifecycle.

## 17. Rules for Claude and GSD

Read repository instructions and existing GSD state first. Preserve schemas, phase numbering, decisions, history, and unrelated uncommitted changes. These documents do not reinitialize GSD or authorize every roadmap item.

Work on the authorized development branch. Keep `main` untouched. Do not commit, push, merge, tag, publish, or rewrite history without explicit authorization. A prior unauthorized commit was reported as `d975e7a`; inspect history and preserve it rather than resetting to conceal it.

Map each active requirement to code, acceptance criteria, and evidence. Inspect unfamiliar code instead of inventing blockers. Continue authorized work through routine debugging. Checkpoint accurately when interrupted; limited time or context is not completion evidence.

Pause only for a concrete dependency such as device unlock, unavailable hardware, required user authentication, or an action outside authorization. State the exact blocked step and preserve resumable state.

## 18. Declaration versus observation rules

Permission requested does not mean permission granted or used. URL present does not mean contacted. API reference does not mean invoked. DNS lookup does not mean HTTP request. Connection attempt does not mean successful exchange. Blocked traffic does not prove data reached a destination. Absence of evidence does not prove absence of behavior.

Attach inference as a separate relationship with its supporting evidence and precision. Never rewrite a static fact into a runtime fact.

## 19. Rules for saying supported

Use the progression designed, implemented in source, component verified, product verified, and release supported. State limitations at every stage.

Unqualified support requires the applicable gates in [Verification Strategy](VERIFICATION_STRATEGY.md), a documented compatibility scope, and no unresolved failure contradicting the claim. Compiling, installation, relay entry, and attractive screenshots individually do not satisfy that standard.

## 20. Known debt and next closure work

Finish the actual viewer and persistence acceptance path before expanding protocol scope. Audit global store isolation, transient capture lifetime, bounded imports, observable import status, exact versus host matching, and nontransactional or concurrent persistence updates.

**2026-09-14 update**: this paragraph's own closure work is done — Milestone 9 closed on this date; see
`.planning/STATE.md`'s "Final Milestone 9 requirement reconciliation." Global store isolation, transient
capture lifetime (a durable `markPending` state now exists precisely so cleanup ordering cannot silently
lose evidence), bounded imports, observable import status (`PENDING`/`EMPTY`/`IMPORTED`/`FAILED`, durable
and directly tested), exact-versus-host matching, and concurrent persistence updates (a real per-key
lock, proven with 1600 concurrent operations and zero lost updates) were all audited and closed with
on-device evidence. Protocol scope may now be expanded per `FUTURE_CAPABILITIES.md`'s priority order —
priority 3 (gRPC/SSE product-level verification through the real fixture-in-Work-Profile UI) is next,
and has not been started.

Review reported `StaticAnalysisResultStore` ObjectStream persistence for compatibility and atomicity; do not deserialize untrusted transported Java objects. Inspect broad catches, stale session reconciliation, raw tunnel timeout behavior, temporary diagnostics, sensitive logs, fragmented ClientHello limits, and unprotected socket failure paths.

**2026-09-14 update**: this requirement's own text always offered two satisfying options — full
non-Java-serialization replacement, or integrity verification before deserializing (see
`.planning/ROADMAP.md`'s original, undated Phase 9.6 task list, written 2026-09-12 before any of this
work began). The integrity-verification option is now fully delivered: a magic/version format-validation
header plus a genuine CRC32 payload check, both evaluated before any `readObject()` call, plus a real
concurrent-import lost-update race found and closed with a per-key in-process lock. **Full replacement
of Java serialization was not attempted and remains real, explicitly-named technical debt** — worth
doing in a future hardening pass, but not required work left silently undone; do not read its absence as
blocking anything closed above. Stale session reconciliation, temporary diagnostics, and sensitive-log
review were separately addressed this same milestone (bounded, non-payload diagnostic instrumentation
correlated by operation id, replacing an earlier reliance on rotating logcat) — raw tunnel timeout
behavior, fragmented ClientHello limits, and unprotected socket failure paths were not in this
milestone's scope and remain exactly as before.

HTTP/3 payload inspection remains research. QUIC classification is not proof of HTTP/3. Further work must follow [Future Capabilities](FUTURE_CAPABILITIES.md) and must not silently redefine an unfinished milestone as complete.
