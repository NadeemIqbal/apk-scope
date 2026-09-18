# Verification Strategy

## Completion standard

A capability is implemented for users only when its production path satisfies measurable acceptance criteria within a documented scope. Source presence, compilation, parser tests, engine tests, device installation, and product acceptance are different claims.

Use these statuses: planned, source implemented, component verified, product verified, release supported, blocked, or deferred. Preserve partial results without promoting them to a stronger status.

## Required acceptance contract

Before implementation, define:

1. The capability and supported variants.
2. Device, API, profile, target trust, permission, and network prerequisites.
3. Fixture inputs and expected outputs, including counts, contents, attribution, and ordering.
4. Negative cases and explicit unsupported outcomes.
5. Numeric byte, entry, time, concurrency, storage, and resource budgets where applicable.
6. Required verification levels and evidence locations.
7. Persistence, cleanup, recovery, and privacy expectations.
8. The exact claim allowed after each gate passes.

Do not invent universal performance thresholds. Set them for the capability and record measured results against them.

## Verification levels

| Level | Required evidence | Boundary of the claim |
| --- | --- | --- |
| JVM tests | Deterministic inputs and assertions for parsers, matching, rules, bounds, and malformed data | Logic works without proving Android integration |
| Android instrumentation | Tests of actual Android storage, TLS providers, lifecycle, and API behavior | Only the exercised Android path |
| Engine protocol integration | Real client and server exchange through the production relay, with byte and status assertions | Engine path, not necessarily VPN routing |
| Fixture application | Separately installed target produces known behavior without injecting inspector records | Real target generation |
| Work Profile integration | Correct target/profile, real VPN route, capture, UI, import, and cleanup | The product workflow on that configuration |
| Physical device | Relevant acceptance path on a real supported device | Tested device and OS scope |
| Process death and recovery | Controlled termination at relevant lifecycle boundaries and subsequent restoration | Tested interruption behavior |
| Malformed input | Invalid, oversized, fragmented, and adversarial input within a bounded campaign | Tested robustness limits |
| Regression | Focused tests for discovered defects plus required project gates | Protection against known regressions |

Not every documentation change needs every level. Select gates by the behavior changed. Network routing, profile transport, and lifecycle changes require corresponding integration evidence; a pure parser change still cannot establish product support by itself.

## Real product network acceptance

1. Build the intended app and fixture from recorded source. Verify the fixture package is `com.apksandbox.fixture`; a filename is insufficient.
2. Analyze that fixture and record the analysis and session identifiers privately.
3. Complete the normal Work Profile install flow and verify the target is installed and running in that profile.
4. Verify actual VPN configuration and target scope. Enable optional inspection through its normal controls and record fixture CA trust where required.
5. Trigger a small controlled request with a distinctive nonsensitive marker. Prefer a controlled endpoint; public services are supplemental smoke tests.
6. Trace the request through routing, policy, upstream protection, TLS if applicable, protocol relay, store insertion, and Traffic Inspector.
7. Assert URL precision, method, response status, bounded content, protocol evidence, session, and target. Correlate the client, server when available, and inspector observations.
8. End the session normally, complete cleanup, import evidence, and reopen the same analysis to verify persisted correlation.
9. Repeat import and verify no duplicates. Verify another session and unrelated application cannot contaminate the result.
10. Retain evidence sufficient to reproduce the result without publishing private device or payload data.

A fixture timeout is a failed exchange requiring diagnosis. A fixture HTTP 200 proves client success, not store insertion. A relay entry log proves entry, not complete capture. A viewer screenshot proves displayed state only when its genuine source is established.

## Protocol gates

| Capability | Minimum targeted assertions |
| --- | --- |
| HTTP/1.1 | GET and POST, request/response pairing, framing, chunked and fixed bodies, connection reuse, errors, timing definitions, bounded capture |
| HTTPS | Compatible CA trust succeeds; untrusted CA, invalid upstream chain, and hostname mismatch fail appropriately; disabled inspection is distinguished |
| WebSocket and WSS | Upgrade, both message directions, timestamps, fragmentation, control events, close behavior, binary handling, and stated extension limits |
| HTTP/2 | Actual negotiated protocol, preface and SETTINGS, HPACK, concurrent stream separation, flow control, POST fidelity, reset and closure |
| gRPC | Framed messages, boundaries across DATA frames, metadata, trailers, gRPC status distinct from HTTP status, supported compression and streaming modes |
| SSE | Events appear before stream closure; multiline data, identifiers, comments, UTF-8 boundaries, cancellation, and documented reconnect behavior |
| QUIC classification | Known valid samples, random UDP negatives, version limits, incomplete packets, and no automatic HTTP/3 claim |

Use [HTTP/2](https://www.rfc-editor.org/rfc/rfc9113), the [gRPC wire protocol](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md), and the [SSE specification](https://html.spec.whatwg.org/multipage/server-sent-events.html) to define precise supported subsets.

For protocol fallback, record both connections and whether any application bytes were sent. Use a server side request counter or equivalent evidence to prove a POST was not replayed. Do not infer HTTP/2 from port 443 or HTTP 200.

## Static analysis and correlation gates

Use known fixture APKs with multiple DEX files, unused strings, code referenced URLs, negative SDK matches, selected API references, malformed entries, and scan limits. Assert expected provenance and class/method references, not just a nonempty result.

Test obfuscated or partial signatures and unknown versions. Never require every SDK fixture to match if the catalog does not support it; never silently change expected results to obtain a pass.

For correlation, test the same host with different paths, subdomain lookalikes, redacted queries, encoding distinctions, another package, another session, late records, missing attribution, repeated imports, and concurrent report updates. Assert that host evidence cannot produce an exact URL claim.

## Lifecycle, isolation, and security gates

Exercise interruption during relevant install, running, stopping, import, and report stages. Verify the same session resumes or ends explicitly without a duplicate session, incorrect target, silent evidence loss, or orphan monitoring state.

Test unauthorized listener clients, stale tokens, invalid artifacts, bounded reads, CA reset behavior, policy enforcement on reconnect, and failed socket protection. Ensure no failure continues through an unprotected upstream connection.

Test capture limits with ongoing traffic to establish that stored truncation does not corrupt forwarded bytes. Verify retention and deletion through the actual stores, not UI labels alone.

## Test accounting and evidence provenance

Use original runner output and XML for the exact run. Record discovered tests, executed tests, passes, failures, errors, and skips with the runner's definitions. An ignored test is not a pass; an assumption failure must be classified according to the actual runner result.

Do not combine stale XML from different devices or runs into a green total. Do not manually create or edit a passing XML report. Keep failed runs and label later reruns separately.

An allegedly unrelated failure remains a failure. Reproduce and investigate it or explicitly report its impact on the release gate. Stale device state is a hypothesis until demonstrated.

Each evidence package must identify branch, commit, dirty source diff, build/fixture identity, commands, OS/API/build, profile setup, relevant configuration, raw results, and requirement mapping. Hash artifacts where useful. Preserve actual local evidence paths.

Synthetic records are allowed for isolated UI tests, clearly labeled. They cannot establish network capture. Keep synthetic media separate from genuine runtime evidence.

## Closure and autonomous continuation

For each requirement, report expected behavior, observed behavior, test/evidence location, limitations, and status. Update existing GSD state without changing its schema or hiding failed work.

If the device locks, request physical unlock and save the next exact step. If an endpoint fails, diagnose or use an approved controlled endpoint. Time pressure, context limits, difficult navigation, or unfamiliar persistence code do not justify a completion claim.

Stop expanding tests once the relevant risks and required gates are resolved. Do not repeat unrelated provisioning or broad engineering campaigns for documentation edits. Do not commit, push, or merge as a side effect of verification.
