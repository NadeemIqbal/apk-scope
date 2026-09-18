# HTTP/3 & QUIC Protocol Interception Feasibility & Architecture Decision

## Executive Summary

This document presents an architectural assessment of supporting **HTTP/3 (RFC 9114)** and **QUIC (RFC 9000)** traffic inspection within APK Scope on Android. 

While HTTP/1.1, WebSocket, and HTTP/2 operate over TCP (allowing straightforward loopback TLS socket interception via standard Android platform APIs and Conscrypt), QUIC operates over **UDP datagrams**, embeds TLS 1.3 cryptographic state machines directly into transport packet encryption, and uses QPACK (RFC 9204) dynamic header tables across dedicated unidirectional streams.

**Core Decision**:
1. **Full Decryption & Interception is Deferred to Future Milestones**: Implementing transparent MITM decryption of HTTP/3 on Android requires bundling a native C/Rust dual-leg QUIC engine (e.g. Cloudflare Quiche or Microsoft MsQuic) across 4 Android ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`), adding 15–30 MB of native binary overhead and replacing the current lightweight `UdpNat` forwarding path with an asynchronous UDP datagram proxy.
2. **Observational Telemetry Prototype Implemented (Milestone 8)**: A non-intrusive, zero-latency observational parser (`QuicPacketParser`) is integrated behind an experimental flag into APK Scope. It inspects UDP port 443 datagrams to identify QUIC packet headers, version negotiations, and connection IDs without altering forwarding semantics or blocking UDP traffic.
3. **No Unsound Forcing**: QUIC traffic is **not** deliberately blocked or dropped to force HTTP/2 fallbacks. Doing so would produce inaccurate latency and security metrics.

---

## 1. Technical Differences: HTTP/2 vs HTTP/3 / QUIC

| Architectural Layer | HTTP/2 (RFC 7540 / 9113) | HTTP/3 / QUIC (RFC 9000 / 9114) |
|---|---|---|
| **Transport Layer** | TCP (Stream-oriented, byte-stream delivery) | UDP (Datagram-oriented, unordered packet delivery) |
| **TLS Integration** | TLS 1.2 / 1.3 layered strictly *below* HTTP/2 frames | TLS 1.3 handshake integrated *inside* QUIC transport frames |
| **Packet Encryption** | TLS Record Layer encrypts byte stream; TCP headers visible | Packet headers and payload encrypted with derived keys; only minimal header flags visible |
| **Multiplexing & Head-of-Line** | Streams multiplexed in single TCP byte-stream; TCP packet loss blocks all streams | Independent stream flow control; packet loss on one stream does not stall other streams |
| **Header Compression** | HPACK (In-order dynamic table synchronized with TCP) | QPACK (Out-of-order dynamic table managed via unidirectional control streams) |
| **Connection ID & Migration** | Identified by 4-tuple (IP:Port); network change drops TCP connection | Connection IDs (DCID/SCID) decouple session from IP:port, enabling seamless network migration |

---

## 2. Platform & Library Assessment for Android

### Candidate Android Libraries

| Library | Language / Runtime | License | Capabilities | Limitations on Android |
|---|---|---|---|---|
| **Cloudflare Quiche** | Rust (C / JNI bindings required) | BSD 2-Clause | Full RFC 9000 QUIC, RFC 9114 HTTP/3, and QPACK engine | Requires compiling BoringSSL + Rust binaries for 4 Android ABIs (~20 MB APK size increase). |
| **Microsoft MsQuic** | C | MIT | Highly optimized QUIC transport engine | HTTP/3 layer not built-in (transport only); requires OpenSSL/BoringSSL JNI bridge. |
| **Netty Incubator QUIC** | Java + Netty JNI (libquiche) | Apache 2.0 | High-level Netty event-loop QUIC API | Native shared libraries (`.so`) packaged for desktop Linux/macOS; Android ABI packaging is unofficial and fragile. |
| **Google Cronet / Chromium Network Stack** | C++ | BSD 3-Clause | World-class QUIC/HTTP/3 client stack | **Client-only stack**. Cannot run as a local terminating QUIC *server* to accept incoming connections from the target app. |
| **Kwik** (ptrd/kwik) | Pure Java | LGPL 3.0 / Commercial | Pure Java QUIC client/server implementation | LGPL licensing conflicts with standard Apache/MIT app distribution; incomplete QPACK and HTTP/3 support. |

### CA & Certificate Trust Constraints

On Android:
- For HTTP/2 and HTTPS over TCP, the local proxy presents an inspection CA-signed certificate via standard `SSLSocket` or Conscrypt SSL engine. Android's network security config allows trusting user/admin certificates installed in the Work Profile.
- For QUIC, TLS 1.3 cryptographic handshakes occur inside QUIC `CRYPTO` frames within Initial and Handshake packets. A proxy cannot terminate TLS 1.3 without a complete QUIC state machine that manages packet acknowledgments, Loss Detection, Congestion Control (BBR/Cubic), and key schedule derivation.
- An installed CA alone **cannot decrypt QUIC traffic** without this terminating QUIC server leg.

---

## 3. Required Architecture Changes for Full Interception

To implement active HTTP/3 decryption in a future milestone, the following architectural components would be required:

```
+-----------------------------------------------------------------------------------+
| Work Profile Target App                                                           |
+-----------------------------------------------------------------------------------+
       |  UDP Port 443 (QUIC Datagrams)
       v
+-----------------------------------------------------------------------------------+
| VpnService (tun0)                                                                 |
+-----------------------------------------------------------------------------------+
       |  IP Packets
       v
+-----------------------------------------------------------------------------------+
| ForwardingEngine (Packet Dispatcher)                                              |
| - Identifies UDP destination port 443                                             |
+-----------------------------------------------------------------------------------+
       |
       +---> [Current: Non-interfering Observational Telemetry (QuicPacketParser)]
       |      - Decodes Long/Short headers, versions, connection IDs
       |      - Telemetry published to TrafficInspectionStore
       |      - Datagram forwarded unmodified via UdpNat
       |
       +---> [Future Milestone: Native Dual-Leg QUIC Engine (Quiche / JNI)]
              - Leg 1 (Local Server): Accepts QUIC handshake with Inspection CA cert
              - Leg 2 (Upstream Client): Initiates protected QUIC session to remote host
              - Stream Bridge: Relays QPACK-decoded requests and responses
```

---

## 4. Grounded Classification & Honesty Rules

In accordance with APK Scope integrity guidelines:
1. **QUIC Transport Observations**: A packet identified by `QuicPacketParser` with valid QUIC headers is labeled `TrafficProtocol.QUIC_OBSERVED`.
2. **Confirmed HTTP/3**: Only traffic where HTTP/3 ALPN (`h3`) or QPACK control streams have been explicitly negotiated and verified may be labeled `HTTP_3`.
3. **Decrypted HTTP/3 Transactions**: Only connections where requests, responses, headers, and status codes are fully decrypted and parsed are displayed as `DECODED`.
4. **No Artificial Blocking**: Some interception tools drop UDP port 443 to force browsers and apps to fall back to HTTP/2. APK Scope strictly prohibits this deceptive behavior: real QUIC packets are forwarded with fidelity.

---

## 5. Milestone 8 Prototype Summary

- **Component**: `com.nadeem.apkscope.core.network.traffic.QuicPacketParser`
- **Location**: `core/network/src/main/kotlin/.../QuicPacketParser.kt`
- **Capabilities**:
  - RFC 9000 Header Form decoding (Long vs Short header).
  - Version identification (QUIC v1 RFC 9000, QUIC v2 RFC 9369, Google QUIC Q043-Q050, drafts).
  - Packet type extraction (Initial, 0-RTT, Handshake, Retry, Version Negotiation, 1-RTT).
  - Dynamic connection ID parsing (DCID and SCID) up to 20 bytes.
  - Zero memory bloat and zero latency impact on forwarding.
