# HTTPS POC implementation handoff

## Reported implementation

Source: attached agent output, Pasted text(20260910-145120).txt. None of the following was independently code reviewed while preparing this pack.

Branch: poc/https_inspection.

Reported integration: TcpProxy extracts TLS SNI and sends an internal binary preamble containing destination IPv4 address, port, SNI length, and SNI bytes to an inspector on loopback. The engine reads that preamble and uses the native socket for TLS handling. This replaced an unsuccessful PushbackSocket approach.

Reported files: CaManager, CaInstaller, HttpsInspectionEngine, HttpsInspectionConfig, HttpsInspectionStore, HttpsTransaction, HttpsCaptureState, TlsClientHelloParser, HttpsInspectionScreen, and HttpsInspectionIntegrationTest. Existing TunSink, TcpProxy, ForwardingEngine, SandboxVpnService, work UI, and fixture were changed.

Reported CA: RSA 2048, validity 30 days, filesDir/poc_ca, with DPM install/query/removal. Confirm protections, backup exclusion, lifecycle, and failure handling.

Reported libraries: org.bouncycastle:bcprov-jdk18on:1.80 and org.bouncycastle:bcpkix-jdk18on:1.80 for certificates. These are reported dependencies, not a current version recommendation. Verify resolved dependencies, license notices, and compatibility locally.

Reported TLS: dynamically generated certificates, platform upstream trust, hostname verification, and socket protection against VPN loops. Verify actual server mode, certificate identity handling, validation, ALPN behavior, and error paths.

Reported parser: HTTP/1.1, chunked transfer, gzip, 64 KiB capture per direction, 100 transactions. Verify capture limits operate separately from forwarding and all preallocation paths are bounded.

Reported fixture: debug trust of user and system CAs, GET to httpbin.org/get, POST to jsonplaceholder.typicode.com/posts, and a trust rejection action. These public services are not controlled by the project. Prefer deterministic test infrastructure for negative TLS cases and record endpoint availability limitations.

Reported capture states: DECODED, ENCRYPTED, TLS_HANDSHAKE_FAILED, UNSUPPORTED_PROTOCOL, TRUNCATED. Prove when each is assigned. A handshake failure does not prove pinning.

## Evidence that was supplied

A report of eight device integration tests passing on an Android 14 emulator. Names cover redaction/truncation, destination policy, loopback handshake, target CA rejection, disabled passthrough, public GET through the engine, CA generation, and lifecycle/binding.

No raw result files, implementation diff, viewer screenshot, or complete VPN POST evidence was supplied. The pack records report content, not an independent PASS.

## Specific review concerns

1. Tests executing with a Work Profile present are not necessarily running inside it.
2. Direct engine socket tests do not prove integration through the TUN and TcpProxy.
3. Port 443 interception alone does not establish fixture package or destination restrictions.
4. A loopback listener is not inherently private to one app. Assess forged preambles and protected upstream connection misuse.
5. SNI is untrusted input and can be absent or unavailable; preserve original destination policy regardless of hostname.
6. An inspection CA installed in a profile does not force arbitrary apps to trust it.
7. Debug trust overrides apply to debuggable builds; they do not enable trust in ordinary release builds.
8. Capturing only 64 KiB must not truncate a body delivered to a peer or corrupt framing.
9. Redaction patterns are heuristics. Check headers, URL queries, and supported body forms without claiming complete secret detection.
10. HTTP/2, QUIC, or WebSocket detection claims require evidence; unsupported decoding alone does not imply implemented detection.

## Immediate goal

Keep and assess the existing work, fix only relevant defects, and obtain complete route evidence with negative TLS validation and clean lifecycle. Do not restart the POC or move on to the backlog.
