# HTTPS Traffic Inspection Proof-of-Concept (POC)

This document describes the experimental HTTPS traffic inspection Proof-of-Concept implemented on the `poc/https_inspection` branch of APK Scope.

---

## 1. Overview & Goals

The goal of this POC is to prove that APK Scope can transparently inspect HTTPS requests and responses inside the Android Managed Work Profile on-device, using a locally generated CA certificate and a compatible target app (such as the included test fixture).

### Key Principles & Scope Boundaries
- **Opt-in & Disabled by Default:** Inspection is strictly disabled until explicitly enabled via the POC toggle in the UI or configuration (`HttpsInspectionConfig.isEnabled`).
- **No Global Bypass Claims:** This POC does **not** bypass certificate pinning, patch APKs, require root access, or claim universal decryption of production third-party apps. Target apps must explicitly trust user-added or admin-installed CA certificates in their network security configuration.
- **Single VPN Pipeline:** Uses the existing Work Profile `VpnService` and userspace TCP forwarding engine. No second VPN service or virtual network adapter is created.
- **Dual-Leg TLS Architecture:** Two independent TLS handshakes:
  1. Client (Test App) $\leftrightarrow$ Local Inspection Engine (Loopback TLS server).
  2. Local Inspection Engine $\leftrightarrow$ Upstream HTTPS Server.
- **Standard Hostname & Trust Verification:** Upstream server certificates and hostnames are validated normally using Android platform trust stores and `HttpsURLConnection.getDefaultHostnameVerifier()`. Trust-all managers and hostname verification bypasses are strictly prohibited.
- **Destination Policies Preserved:** RFC 1918 private IP blocking and destination allow/deny rules are enforced before upstream connections are opened.

---

## 2. Architecture & Implementation

```
+-------------------------------------------------------------------------------+
|                            Android Work Profile                               |
|                                                                               |
|  +--------------------+                     +------------------------------+  |
|  | Target App/Fixture |                     | Work Profile VpnService      |  |
|  | (debug trust config)                     |                              |  |
|  +---------+----------+                     +--------------+---------------+  |
|            | TCP SYN (Port 443)                            |                  |
|            +---------------------------------------------->| TUN Interface    |
|                                                            |                  |
|                                                     +------v---------------+  |
|                                                     | TunSink & TcpProxy   |  |
|                                                     +------+---------------+  |
|                                                            |                  |
|  +---------------------------------------------------------+                  |
|  | Intercepted TCP Stream (Port 443 & Inspection Enabled)                     |
|  | Preamble: [4-byte IP][2-byte Port][2-byte SNI Len][SNI bytes]              |
|  v                                                                            |
|  +-------------------------------------------------------------------------+  |
|  | HttpsInspectionEngine (Bound to 127.0.0.1)                              |  |
|  |                                                                         |  |
|  |  Leg 1 (Client):                                                        |  |
|  |  - Reads preamble, retrieves SNI host.                                  |  |
|  |  - Serves dynamic leaf certificate signed by local CA (SAN matching).  |  |
|  |  - Direct Conscrypt native socket handshake on real FD.                 |  |
|  |                                                                         |  |
|  |  Leg 2 (Upstream):                                                      |  |
|  |  - Checks DestinationPolicy (RFC 1918 private blocking).                |  |
|  |  - Connects to upstream server IP & verifies certificate / hostname.    |  |
|  |  - Protected socket via VpnService.protect() to bypass loop.            |  |
|  |                                                                         |  |
|  |  HTTP/1.1 Decoding & Decompression:                                     |  |
|  |  - Decodes Request/Response headers & bodies.                           |  |
|  |  - Transparently unchunks chunked transfer encoding for preview.       |  |
|  |  - Decompresses gzip payloads into human-readable text.                 |  |
|  |  - Enforces 64 KiB truncation per direction.                            |  |
|  |  - Redacts sensitive headers and fields before persistence.             |  |
|  +-------------------------------------------------------------------------+  |
|                                    |                                          |
|                                    v                                          |
|                       +--------------------------+                            |
|                       | HttpsInspectionStore     |                            |
|                       | (100-item ring buffer)   |                            |
|                       +------------+-------------+                            |
|                                    |                                          |
|                                    v                                          |
|                       +--------------------------+                            |
|                       | Compose Traffic Viewer   |                            |
|                       +--------------------------+                            |
+-------------------------------------------------------------------------------+
```

### SNI Preamble Protocol for Android Conscrypt
Android uses **Conscrypt** (BoringSSL). When wrapping a `Socket` with custom Java stream wrappers, Conscrypt loses access to the underlying native Linux file descriptor (`FileDescriptor`), causing native SSL handshakes to block waiting for peeked bytes.

To preserve direct file descriptor access so BoringSSL handshakes complete reliably without custom stream socket wrappers:
1. `TcpProxy` parses the Server Name Indication (SNI) extension directly from the initial TCP SYN/payload packet on the TUN interface.
2. `TcpProxy` forwards an in-band binary preamble over the loopback socket to `HttpsInspectionEngine`:
   - `[4 bytes destination IPv4]`
   - `[2 bytes destination Port]`
   - `[2 bytes SNI length]`
   - `[N bytes UTF-8 SNI hostname]`
   - Followed immediately by the original, untouched TLS `ClientHello` bytes.
3. `HttpsInspectionEngine` reads only the fixed preamble header and passes the raw native client socket directly to `SSLSocketFactory.createSocket(clientSocket, null, clientSocket.port, false)`.
4. Conscrypt executes the handshake directly against the underlying socket file descriptor.

---

## 3. Certificate Lifecycle & Work Profile Trust

### Local CA Generation
- **Algorithm:** RSA 2048-bit keypair with SHA256withRSA signature.
- **Subject:** `CN=APK Scope Inspection CA, O=APK Scope POC, OU=Work Profile Inspection`.
- **Validity:** 30 days.
- **Storage:** Stored strictly inside protected app private storage (`context.filesDir/poc_ca/ca_cert.der` and `ca_key.der`). Private keys are never committed, exported, or logged.
- **Dynamic Leaf Certificates:** Generated on-the-fly for each requested SNI host with Subject Alternative Names (SAN `dNSName` and `iPAddress`). Leaf certificates are cached in memory.

### DevicePolicyManager (DPM) CA Installation Flow
In an Android Managed Work Profile, the Profile Owner app can programmatically manage CA certificates without rooting:
- **Status Check:** `dpm.hasCaCertInstalled(adminComponent, certDerBytes)`
- **Installation:** `dpm.installCaCert(adminComponent, certDerBytes)`
- **Uninstallation:** `dpm.uninstallCaCert(adminComponent, certDerBytes)`

### Target App Certificate Trust Requirements
Because Android 7.0+ (API 24+) does not trust user or admin CAs for secure connections by default, apps must configure a Network Security Config to trust user/admin anchors.

> [!IMPORTANT]
> **Debug-Only Trust Scope & Certificate Pinning Boundaries**:
> In the test fixture (`fixture/src/main/res/xml/network_security_config.xml`), trust overrides are declared strictly inside `<debug-overrides>`:
> ```xml
> <?xml version="1.0" encoding="utf-8"?>
> <network-security-config>
>     <debug-overrides>
>         <trust-anchors>
>             <certificates src="user" />
>             <certificates src="system" />
>         </trust-anchors>
>     </debug-overrides>
> </network-security-config>
> ```
> Android security policy applies `<debug-overrides>` **only when the application is debuggable** (`android:debuggable="true"` in its manifest). Standard release builds do **not** inherit these overrides and strictly adhere to system trust anchors unless explicitly configured by the developer.
> 
> **Certificate Pinning is NOT Bypassed by CA Trust**:
> Installing a CA into the Work Profile or trusting user/admin certificates in `network_security_config.xml` **does NOT bypass certificate pinning**. If an application uses declarative pinning (`<pin-set>` in network security config) or programmatic pinning (e.g. OkHttp `CertificatePinner`, TrustKit, or custom X509TrustManager validation), the client verifies the exact SPKI sha256 pin of the server leaf or intermediate certificate. In such cases, the connection will fail-closed and be rejected by the client (`TLS_HANDSHAKE_FAILED`). Bypassing in-app certificate pinning requires runtime binary hooking (e.g., Frida/Xposed) or reverse-engineering bytecode patches, which APK Scope does not attempt or perform.

---

## 4. Supported Traffic & Protocol Scope

| Capability | Status | Details |
| :--- | :--- | :--- |
| **HTTP/1.1 over TLS** | Supported | Full bidirectional request/response decoding, header parsing, body inspection. |
| **Gzip Decompression** | Supported | Decodes `Content-Encoding: gzip` payloads into readable text. |
| **Chunked Framing** | Supported | Transparently unchunks `Transfer-Encoding: chunked` bodies for preview. |
| **HTTP/2 & HTTP/3 / QUIC** | Outside Coverage | HTTP/2 binary framing, multiplexing, and HTTP/3 over QUIC (UDP) are outside supported decoding coverage for this POC. |
| **WebSockets** | Outside Coverage | WebSocket frame inspection is outside supported decoding coverage for this POC. |
| **Target Filter** | Supported | Intercepts port 443 traffic when inspection toggle is enabled. |

---

## 5. Security Controls, Privacy & State Machine

### Explicit Transaction States
Every connection through the engine is assigned an explicit state in the traffic viewer:
1. `DECODED`: HTTP/1.1 request and response successfully intercepted and parsed.
2. `ENCRYPTED`: Inspection disabled or passthrough mode active; payload passed through raw.
3. `TLS_HANDSHAKE_FAILED`: TLS handshake rejected (e.g. client does not trust inspection CA, upstream certificate invalid, hostname mismatch).
4. `UNSUPPORTED_PROTOCOL`: Protocol not supported for decoding (e.g. non-HTTP/1.1 traffic).
5. `TRUNCATED`: Request or response body exceeded the 64 KiB buffer limit.

### Redaction & Privacy
Before persisting transactions in `HttpsInspectionStore`:
- **Sensitive Headers Redacted:** Values of `Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization`, `X-Api-Key`, `X-Auth-Token` are replaced with `[REDACTED]`.
- **Sensitive JSON/Body Keys Redacted:** Regex patterns scan bodies for keys such as `password`, `token`, `secret`, `access_token`, `api_key`, `credential`, redacting their values.
- **Truncation Cap:** Payloads are capped at 64 KiB per direction.
- **In-Memory Retention:** Transactions are kept in an in-memory ring buffer of 100 items and never written to permanent disk storage.

---

## 6. Verification & Test Evidence

### Automated Unit Tests
Executed via `./gradlew testDebugUnitTest` across all modules:
- Total Unit Tests: **263 passed, 0 failures, 0 errors, 0 skipped** (35 test suites)
  - `app`: 69 passed
  - `core/network`: 65 passed (including `CaManagerTest`, `EngineHardeningTest`, `DestinationPolicyTest`, `PacketValidationFuzzTest`, `HttpsInspectionPolicyTest`, `RootCauseGateTest`, `HttpsTlsValidationTest`, `HttpsInspectionStoreTest`, `TlsClientHelloParserTest`, `DnsMessageTest`)
  - `core/risk`: 82 passed
  - `core/model`: 40 passed
  - `core/common`: 5 passed
  - `core/staticanalysis`: 2 passed

### On-Device Instrumented Tests
Executed on device `emulator-5554` (`Pixel_10_Pro_XL(AVD) - 17`, Android 17 / API 35, Work Profile User 12):
```bash
adb shell am instrument -w -r -e class com.nadeem.apkscope.sandbox.HttpsInspectionIntegrationTest com.nadeem.apkscope.test/androidx.test.runner.AndroidJUnitRunner
```

**Results (13 tests passed, 0 failures, 0 skipped):**
```text
INSTRUMENTATION_STATUS: test=testRedactionAndTruncationOnDevice - PASSED
INSTRUMENTATION_STATUS: test=testDestinationPolicyEnforcementOnDevice - PASSED
INSTRUMENTATION_STATUS: test=testConscryptHandshakeOnLoopback - PASSED
INSTRUMENTATION_STATUS: test=testResetLifecycleOnDevice - PASSED
INSTRUMENTATION_STATUS: test=testUnauthorizedInspectorAccessRejectedOnDevice - PASSED
INSTRUMENTATION_STATUS: test=testTargetAppRejectionOfInspectionCaOnDevice - PASSED
INSTRUMENTATION_STATUS: test=testInspectionDisabledPassthroughOnDevice - PASSED
INSTRUMENTATION_STATUS: test=testUpstreamHostnameMismatchRejectionOnDevice - PASSED
INSTRUMENTATION_STATUS: test=testEngineInterceptionWithRealPublicHttpsGetOnDevice - PASSED
INSTRUMENTATION_STATUS: test=testEngineInterceptionWithRealPublicHttpsPostOnDevice - PASSED
INSTRUMENTATION_STATUS: test=testCaGenerationOnDevice - PASSED
INSTRUMENTATION_STATUS: test=testEngineLifecycleAndBinding - PASSED
INSTRUMENTATION_STATUS: test=testInvalidUpstreamCertificateRejectionOnDevice - PASSED

Time: 16.914s
OK (13 tests)
```

### Live Public API Demonstrations
- `https://httpbin.org/get`: Real public HTTPS GET returning JSON; decoded status 200 with readable origin and headers.
- `https://httpbin.org/post`: Real public HTTPS POST sending dummy JSON `{"sender":"apk-scope-fixture","action":"poc_test"}`; decoded status 200 with response echoing JSON body.
- Target App Rejection: Connecting with standard system trust store without local CA correctly aborted TLS handshake and logged `TLS_HANDSHAKE_FAILED`.
- Unauthorized Listener Rejection: Rogue client attempting preamble without valid authentication token rejected immediately.

---

## 7. Setup & Reproduction Steps

### Step 1: Install & Set Up Work Profile
1. Build and install the debug APK:
   ```bash
   ./gradlew :app:assembleDebug
   adb install -r -t app/build/outputs/apk/debug/app-debug.apk
   ```
2. Provision APK Scope as Profile Owner of the Work Profile if not already provisioned:
   ```bash
   adb shell dpm set-profile-owner --user 10 com.nadeem.apkscope/.receiver.SandboxDeviceAdminReceiver
   ```

### Step 2: Generate & Install CA Certificate
1. Open APK Scope inside the Work Profile.
2. Navigate to **Live Monitor** $\rightarrow$ **HTTPS Inspector** (or tap the HTTPS Inspection banner).
3. Tap **Generate Inspection CA** if not already generated.
4. Tap **Install CA in Work Profile**. Android's `DevicePolicyManager` registers the certificate as an authorized user/admin credential in the Work Profile.

### Step 3: Run the Test Fixture
1. Build and install the fixture APK:
   ```bash
   ./gradlew :fixture:assembleDebug
   adb install -r -t fixture/build/outputs/apk/debug/fixture-debug.apk
   ```
2. Start monitoring in APK Scope (tap **Start Monitoring** to engage the Work Profile VPN).
3. Ensure **Enable HTTPS Inspection** toggle is switched **ON** in the HTTPS Inspection screen.
4. Launch the **APK Scope Fixture** app in the Work Profile.
5. Tap **HTTPS GET (JSON)** or **HTTPS POST (Dummy JSON)**.
6. Return to APK Scope $\rightarrow$ **HTTPS Inspector**.
7. View the decoded transaction with HTTP 200/201 status, request/response headers, and formatted JSON body.

### Step 4: Reset & Cleanup
To completely stop inspection and purge cryptographic material:
1. Tap **Reset & Delete CA** in the HTTPS Inspector screen.
2. The engine uninstalls the CA cert from the Work Profile DPM, deletes the private key and cert files from disk, clears all in-memory captured transactions, and closes any open inspection sockets.

---

## 8. Limitations & Non-Goals

1. **Certificate Pinning:** Apps that employ certificate pinning (e.g. OkHttp `CertificatePinner`, TrustKit, or custom network security configs) will explicitly reject the local CA. This POC does not bypass pinning or patch APK bytecode.
2. **HTTP/2 & HTTP/3:** HTTP/2 binary framing and HTTP/3 (QUIC/UDP) multiplexing are not decoded in this POC. Connections negotiating HTTP/2 will show as `UNSUPPORTED_PROTOCOL`.
3. **Release Target Apps:** Production third-party APKs downloaded from Google Play rarely include `<debug-overrides>` trusting user/admin CAs on API 24+.
4. **WebSocket Decoding:** WebSockets are not parsed into individual frames in this POC.
