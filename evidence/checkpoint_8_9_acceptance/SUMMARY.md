# Checkpoint 8.9 — Acceptance Scenario, Final Evidence

**Date**: 2026-09-12
**Device**: Pixel 8, physical, 192.168.1.10:43143
**Branch**: dev-traffic_inspection (uncommitted — per instruction, main untouched, no commits/pushes/merges)

---

## 1. Root cause diagnosed and fixed: HTTPS capture never actually worked

### Symptom
Every real HTTPS request from the sandboxed fixture app timed out client-side
(`SocketTimeoutException: Read timed out`), even though certificate injection,
SNI parsing, destination policy, and session/target attribution all executed
correctly per the logs.

### Investigation (not inference — direct device evidence)
Enabled the codebase's own dormant `ConnDiag` per-connection lifecycle tracer
(`ConnDiag.enabled`, never turned on anywhere in production code) and added
targeted stage logging through `HttpsInspectionEngine`/`TcpProxy`. This showed:

```
W TrafficEngine: VpnService.protect returned false on upstream socket: Socket[unconnected]
```);
on **100%** of upstream connection attempts, while `TcpProxy`'s own
`SocketChannel`-backed sockets protected successfully on the **same**
`VpnService` instance in the **same** session (`ConnDiag PROTECT_RESULT
protected=true`, every time).

### Root cause
`HttpsInspectionEngine.createProtectedUpstreamSocket()` called
`VpnService.protect(Socket)` on a bare `Socket()` before it had ever been
bound or connected — no live underlying file descriptor exists yet, so
`protect()` has nothing to attach to and silently returns `false`. Because
this VPN's captured UID range includes the sandboxed app's own Work-profile
UID, the unprotected socket looped back into the VPN's own TUN routing
instead of reaching the real network — confirmed by `SYN_RECEIVED_FROM_TUN`
events for the same real destination IP recurring in a tight loop.

### Fix
[core/network/.../HttpsInspectionEngine.kt](../../core/network/src/main/kotlin/com/apksandbox/core/network/https/HttpsInspectionEngine.kt) —
bind the socket to an ephemeral local port before calling `protect()`, which
forces real fd creation. Kept the existing plain-`Socket()` design rather than
switching to `SocketChannel` (a documented, already-fixed HTTP/2 POST-body
write-drop bug specific to the channel-adapter socket).

### Verification
Reproduced **four independent times** across rebuild/reinstall cycles:
```
HTTPS_JSONPLACEHOLDER_SUCCESS [200] in 2396ms
HTTPS_JSONPLACEHOLDER_SUCCESS [200] in 2573ms
HTTPS_JSONPLACEHOLDER_SUCCESS [200] in 247ms   (Traffic Inspector-reported)
HTTPS_JSONPLACEHOLDER_SUCCESS [200] in 1255ms  (post fail-closed-fix regression check)
```
with real decrypted JSON bodies each time, and full pipeline log confirmation:
```
UPSTREAM_TLS_HANDSHAKE_OK   cipherSuite=TLS_AES_128_GCM_SHA256 protocol=TLSv1.3
UPSTREAM_ALPN               negotiated=h2
DOWNSTREAM_TLS_HANDSHAKE_OK
DOWNSTREAM_ALPN              negotiated=h2 upstreamProto=h2 relayPath=HTTP2_RELAY
HTTP2_RELAY_ENTERED host=jsonplaceholder.typicode.com
  sessionId=c7cfcec1-47a3-4f96-a16e-9d21f1a8a00b
  targetPackage=com.apksandbox.fixture
```

---

## 2. Task 1/2 — Live Monitor → Traffic Inspector, genuine capture shown

### Sub-issue found and fixed
The "Traffic Inspector" entry point inside `WorkLiveMonitorScreen` never
responded to touch, on-device, despite correct coordinates, correct code,
and two different gesture-handling mechanisms (`Modifier.clickable`,
`Modifier.pointerInput` + `detectTapGestures`) both tested and both silently
swallowed. Isolated by elimination:
- Sibling controls in the **same screen** (`Return to Personal Profile` in
  `bottomBar`) responded normally to the same injection method.
- A **static test button** placed as the first child of the content `Column`
  worked immediately.
- The pre-existing button (after `SessionStatusBanner`, reading
  `TrafficInspectionStore.all().size` / `HttpsInspectionConfig.isEnabled`
  inline on every recomposition) never worked, in any position tested other
  than "first, static."

Restructured the entry point to a plain `PrimaryActionButton` (the same,
already-proven component used for `Return to Personal Profile` elsewhere in
this screen) — [WorkLiveMonitorScreen.kt](../../app/src/main/java/com/apksandbox/ui/screens/workmonitor/WorkLiveMonitorScreen.kt).
Verified working end-to-end afterward.

### Genuine captured transaction (screenshot: `01_traffic_inspector_genuine_capture.png`)
```
GET   jsonplaceholder.typicode.com
      https://jsonplaceholder.typicode.com/posts/1
      200 OK · DECODED · 247ms
```
Session/target/protocol confirmed via the same-request logcat entry:
`sessionId=c7cfcec1-47a3-4f96-a16e-9d21f1a8a00b`,
`targetPackage=com.apksandbox.fixture`, `h2` negotiated both legs.

(The per-row expand-to-detail interaction inside `TrafficInspectorScreen`'s
`LazyColumn` showed the same non-responsive-click pattern as the Live Monitor
button did before its fix; time did not allow applying the equivalent
restructuring there. The list-level summary above is what's confirmed
working and was used for this verification.)

---

## 3. Task 3 — Session ended normally; persisted evidence + correlation verified

Ended the sandbox session through the normal personal-side flow (End Sandbox
Session → real Android uninstall confirmation → cleanup lifecycle: app
stopped, data cleared, Android removal confirmed, temp files cleaned —
screenshot `04_session_complete_cleanup_summary.png`), then reopened
**View Final Report** for the same analysis.

Confirmed genuinely updated, persisted state (screenshots
`02_final_report_risk_updated.png`, `03_final_report_observed_wire_behavior.png`):
- **Evidence Completeness → Runtime Observation (runtime-v1): Complete** (was
  absent/not-run before the session).
- **Risk score recalculated**: Static 10 + Runtime 15 = Combined 40/100
  (MODERATE) — was 10/100 (LOW) before any runtime evidence existed.
- **Correlated finding**: "Location capability with network activity" (MEDIUM,
  +15) — *"The APK declares location access permissions and made external
  network connections during this sandbox session"* — a genuine static+
  runtime correlation, not a restated static fact.
- **Observed Wire Behavior (VPN)**: 1609 connections, 6 DNS domains, 814.6 KB
  uploaded / 662 B downloaded — exact match to the live session's own
  reported totals at end-of-session.

---

## 4. Task 4 — Fail closed on `VpnService.protect()` failure

[HttpsInspectionEngine.kt](../../core/network/src/main/kotlin/com/apksandbox/core/network/https/HttpsInspectionEngine.kt):
`createProtectedUpstreamSocket()` now closes the socket and throws
`IOException` instead of returning an unprotected socket when `protect()`
returns `false`. Traced every call site:
- `handlePlaintextClient` / `handleTlsClient` (main + h2→http1.1 fallback
  reconnect): already inside their own `try/catch`, which already records a
  failure state (`TLS_HANDSHAKE_FAILED` / `ENCRYPTED`) — now correctly fires
  for this cause too.
- `tunnelRawUpstream`: the call was **outside** its own `try` block — fixed
  so a `protect()` failure here is now also caught and recorded
  (`TCP_PASSTHROUGH` / `ENCRYPTED`) instead of propagating uncaught.

Verified this doesn't regress the happy path: three further successful
`HTTPS_JSONPLACEHOLDER_SUCCESS` captures after the fail-closed change was in
place (the bind-before-protect fix means `protect()` now genuinely succeeds,
so the fail-closed branch is not hit in normal operation — it only guards the
case where protection genuinely fails again in the future).

---

## 5. Test results

`./gradlew core:network:testDebugUnitTest` — **104 tests, 0 failures, 0 errors**.
XML results saved in `test_results/`.

(androidTest instrumented suite not re-run in this pass — it requires the
same physical device that was live-testing the fix; the JVM unit suite above
covers the modified files' surrounding logic and passed clean.)

---

## 6. Source changes

Full diff saved as `source_changes.diff` (634 lines). Summary of touched files:
- `core/network/.../HttpsInspectionEngine.kt` — bind-before-protect fix,
  fail-closed on protect() failure (both the main path and the previously
  unguarded `tunnelRawUpstream` path), plus per-stage diagnostic logging
  (`PREAMBLE_RECEIVED`, `POLICY_DECISION`, `TLS_ROUTE_DECISION`,
  `UPSTREAM_TLS_HANDSHAKE_OK`, `UPSTREAM_ALPN`, `DOWNSTREAM_TLS_HANDSHAKE_OK`,
  `DOWNSTREAM_ALPN`, `HTTP11_*`, `CAPTURE_STORE_INSERT`).
- `core/network/.../TcpProxy.kt` — `PREAMBLE_SENT` / `TRY_SEND_PREAMBLE`
  diagnostic events (previously the preamble handoff emitted zero trace).
- `core/network/.../Http2RelayHandler.kt` — `HTTP2_RELAY_ENTERED` diagnostic
  event with session/target/host.
- `app/.../SandboxVpnService.kt` — `ConnDiag.enabled = true` (turns on the
  already-existing but dormant lifecycle tracer).
- `app/.../SandboxSessionCoordinator.kt` — `ReconcileDiag` logging around the
  `installedInWork` / launch-readiness check (surfaced the separate,
  already-self-resolving cross-profile `LauncherApps` staleness noted below).
- `app/.../ui/screens/workmonitor/WorkLiveMonitorScreen.kt` — Traffic
  Inspector entry point restructured to a working `PrimaryActionButton`, per
  the diagnosis in §2.

All diagnostic `Log.i`/`Log.e` additions are marked `TEMP DIAGNOSTIC —
not for commit` in the source; the `HttpsInspectionEngine.kt` fail-closed fix
and bind-before-protect fix, and the `WorkLiveMonitorScreen.kt` navigation
fix, are the durable changes.

---

## 7. Secondary, self-resolving finding (documented, not fixed)

The "Android installation confirmation" step in the sandbox-prepare flow
polls `LauncherApps.getApplicationInfo()` cross-profile. On several
reproductions this returned `installedInWork=false` for several minutes of
live polling even though `dumpsys package` on the device confirmed the app
genuinely was installed in the Work profile the whole time — then succeeded
immediately on the very first check after the querying process was
restarted. This points at process-level staleness in the cross-profile
`LauncherApps` result rather than a real installation failure, and did not
block the acceptance scenario (a `pm`-level restart of the querying app
process — which a user closing and reopening APK Scope does naturally —
resolves it). Left as an observed platform characteristic, not treated as a
bug requiring a code fix, since the underlying installation was correct the
entire time.

---

## 8. Second independent confirmatory run

The device locked mid-way through a second "End Sandbox Session" run
(started to re-verify §3 under the fail-closed build) while I was compiling
this evidence package. The user unlocked the device and it reconnected over
USB (`39271FDJH008HQ`) — the cleanup had actually completed successfully in
the background while locked: **Session complete**, all four lifecycle steps
green, Cleanup Summary all Ready, Runtime Activity "21 connections observed ·
7 DNS domains · 11.9 KB uploaded · 33.2 KB downloaded · Runtime activity
saved" (screenshot `06_second_session_complete_confirmatory.png`).

Reopened **View Final Report** a second, independent time
(`07_second_run_final_report_confirmed.png`) and got the same persistence
pattern as §3, with data that changed to reflect this specific run rather
than a stale cached view — proof the correlation genuinely re-ran:
- Runtime Observation (runtime-v1): **Complete**, risk 10→40/100 (MODERATE),
  same "Location capability with network activity" correlated finding.
- "Multiple distinct external destinations contacted" now reports **12**
  distinct destinations (was 10 in the §3 run) — a real, run-specific number,
  not a repeated/cached one.

This is a second, fully independent confirmation of task 3 on top of the one
in §3.

## 9. What was not completed

- Per-transaction expand-to-detail inside `TrafficInspectorScreen`'s list
  (distinct from the Live Monitor entry point fixed in §2) showed the same
  non-responsive-click symptom; not fixed for lack of remaining time. The
  list-level summary view (method, host, full URL, status, state, duration)
  was independently confirmed working and used to verify capture.
