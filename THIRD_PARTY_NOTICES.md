# Third-Party Software Notices and Information

This project contains software and dependencies licensed under open-source licenses.

---

### 1. AndroidX Libraries (Google LLC)
- **Artifacts:** `androidx.core:core-ktx`, `androidx.activity:activity-compose`, `androidx.lifecycle:*`, `androidx.navigation:navigation-compose`, `androidx.room:*`, `androidx.compose.*`, `androidx.test:*`
- **License:** Apache License 2.0
- **URL:** https://developer.android.com/jetpack/androidx

### 2. Kotlin & KotlinX Libraries (JetBrains s.r.o.)
- **Artifacts:** `kotlin-stdlib`, `kotlinx-coroutines-android`, `kotlinx-coroutines-test`, `kotlinx-serialization-json`
- **License:** Apache License 2.0
- **URL:** https://github.com/Kotlin/kotlinx.coroutines, https://github.com/Kotlin/kotlinx.serialization

### 3. JUnit (JUnit Team)
- **Artifacts:** `junit:junit:4.13.2`
- **License:** Eclipse Public License 1.0 (EPL-1.0)
- **URL:** https://junit.org/junit4/

### 4. JSON-in-Java (JSON.org)
- **Artifacts:** `org.json:json:20240303`
- **License:** Public Domain / JSON License
- **URL:** https://github.com/stleary/JSON-java

---

### Implementation Provenance Note

All network forwarding, packet parsing, state tracking, and policy enforcement engines in `core:network` (including `ForwardingEngine`, `TcpNat`, `UdpNat`, `PacketParser`, `DestinationPolicy`, and `DnsMessage`) were independently authored in Kotlin based on public IETF specifications (RFC 791, RFC 793, RFC 768, RFC 1035, RFC 1918) and the standard Android `VpnService` API. No proprietary or copyleft source code from third-party sandboxing, interception, or VPN projects was copied.
