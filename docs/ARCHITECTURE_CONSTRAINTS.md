# Architecture Constraints

These constraints govern implementation and documentation. They are requirements, not assertions that every existing code path already satisfies them. An exception requires an explicit architectural decision and user authorization where scope or permissions change.

## Execution and privilege

1. The supported application must require no root, custom ROM, privileged installation, hidden API access, or runtime shell permission grants.
2. ADB may support development and testing. Record its use and do not present shell only operations as normal application capabilities.
3. Do not claim kernel telemetry, arbitrary process instrumentation, private file inspection, or systemwide monitoring from ordinary app APIs.
4. Treat the target as untrusted. Work Profile execution is not a guarantee against platform vulnerabilities.

## Profiles and lifecycle

Use the Managed Work Profile and Profile Owner design. Profile IDs must be discovered dynamically. Do not hardcode test device user numbers.

Do not assume provisioning is available merely because the APK installs. Existing management, OEM behavior, user restrictions, and profile state can prevent the intended workflow. Private Space is separate and does not establish Work Profile compatibility.

Keep Personal and Work storage separate. Use narrowly scoped, documented communication channels. Android restricts cross profile intents and requires appropriate file sharing mechanisms. See [Work Profile behavior](https://developer.android.com/work/managed-profiles).

Honor Android confirmation and authentication requirements in the supported install, uninstall, and unlock flows. Do not bypass prompts to make acceptance tests pass. If an API offers different behavior under another management role, that does not change this product's supported role without review.

Cleanup must be recoverable and truthful. Target data clearing, target removal, capture retention, CA removal, and Work Profile deletion are different operations. Normal session cleanup does not delete the entire profile.

## VPN scope and policy

Android allows one active VPN service per user or profile. The monitoring VPN can conflict with a target that needs that same VPN slot. Do not silently surrender monitoring to make the target work. See [Android VPN guidance](https://developer.android.com/develop/connectivity/vpn).

Define and verify which applications enter the tunnel. Session labels do not prove network ownership. Capture and policy tests must include unrelated traffic and inspector generated upstream traffic.

Apply destination rules before connection and to the actual resolved destination, including alternate addresses and reconnects. Preserve the established IPv6 blocking policy until separately designed and verified support replaces it. No silent routing bypass is acceptable.

Protect every applicable upstream socket before connecting and check the result. Failure must close the socket and produce an observable error. Protection exists to avoid VPN recapture; see [VpnService.protect](https://developer.android.com/reference/android/net/VpnService#protect(java.net.Socket)).

Preserve stream bytes and protocol semantics. Truncating a stored copy must not truncate traffic forwarded to either peer. Recording backpressure must remain bounded and have an explicit failure policy.

## TLS and readable payloads

Metadata mode must not claim encrypted paths, headers, cookies, or bodies are readable. Payload inspection must be optional, off by default, and clearly disclosed for the session.

Installing a CA does not force every target to trust it. Apps targeting API 24 or later do not trust user added CAs by default under the platform configuration. Custom trust code and pinning can impose further restrictions. See [Network security configuration](https://developer.android.com/privacy-and-security/security-config).

Do not bypass target pinning, alter third party APK trust settings, install a system CA, or disable upstream chain or hostname verification. A compatible fixture may explicitly trust the test CA; record that configuration.

Treat the upstream and downstream TLS connections separately. Record negotiated protocol on each when diagnosing routing. A deliberate protocol fallback must preserve trust validation and must not duplicate application requests. Reconnection after sending request bytes needs an explicit retry design, especially for POST and streaming traffic.

Protect CA keys and control endpoints. Do not export private keys with reports. Define rotation, reset, certificate removal, and consequences for existing sessions. Deleting an internal key does not prove removal of an installed trust anchor.

## Cross profile evidence contract

Transfer the minimum data required for a defined purpose. URL correlation must not require wholesale transfer of headers or bodies.

Every artifact needs a schema version, analysis/session linkage, target identity, source references, entry limits, byte limits, and explicit completeness information. Treat timestamps as evidence metadata, not authentication.

Validate the intended provider and grants as well as content. Reject mismatched sessions or targets, invalid types, oversized fields, duplicate conflicts, unsupported versions, and malformed input. Do not deserialize untrusted Java objects.

Bound reads and allocations before parsing large content. Count encoded bytes, not characters. Truncation counts must describe what is actually known; if total omitted records cannot be determined, report that uncertainty.

Apply redaction before transport and validate it at the receiving boundary. Use idempotent imports and atomic or transactional persistence. Preserve concurrent evidence updates. Surface pending, imported, truncated, rejected, and failed outcomes in durable application state.

## Evidence integrity

Keep extraction provenance and runtime correlation as separate facts. Match hosts by parsed identity, never arbitrary substring containment. Document URL normalization and retain matching precision after redaction.

Do not label a full URL observed from DNS, SNI, an IP connection, or a redacted representation that has lost distinguishing values. Unknown ownership must remain unknown.

DPM is an independent, asynchronous evidence source, not the VPN's output. Profile Owner network logging is available from API 31; feature gate it separately from the historical API 30 app minimum. See [DPM network logging](https://developer.android.com/work/dpc/logging).

## Performance and malformed input

Set measurable budgets for APK size, decompression, DEX entries, parser work, TCP buffering, connections, streams, decoded payloads, captures, storage, and retention before expanding a capability.

Do not block the main thread for analysis or protocol processing. Support cancellation. Measure sustained memory, CPU, battery impact, and UI responsiveness on the declared device scope.

Reject or explicitly limit unsupported input without crashes, unbounded allocation, silent corruption, or unsupported success claims. A small ClientHello buffer is a compatibility limit, not a guarantee that all fragmented handshakes are supported.

## Privacy and offline operation

Analyze APKs and store reports locally by default. Do not add automatic uploads of APKs, payloads, identifiers, or reports.

The target may access external services during dynamic execution. Never advertise that no data leaves the device merely because analysis is local.

Bound and redact captures, logs, exports, and diagnostics. Redaction is not a guarantee that arbitrary payloads contain no secrets. Define access, retention, deletion, and backup behavior for sensitive stores. Diagnostic mode must not remain broadly enabled after troubleshooting.

Static analysis and saved reports should work offline. Catalog updates, if introduced, must be explicit, versioned, and optional for basic analysis. Failure to reach a test server must not be reported as a protocol parser failure without diagnosis.

## Compatibility and change control

Read SDK, Gradle, AGP, Kotlin, and signing configuration from the current repository. Do not copy historical version numbers into release claims.

Verify actual OS/API/build properties rather than trusting emulator names. A passing Pixel test is evidence for that configuration, not universal Android or OEM compatibility.

Keep `main` unchanged and preserve uncommitted work. These documents do not authorize commits, pushes, merges, publication, risk rule changes, or expansion into deferred capabilities.
