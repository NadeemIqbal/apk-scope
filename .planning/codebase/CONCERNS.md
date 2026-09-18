# Codebase Concerns

**Analysis Date:** 2026-09-10

## Tech Debt & Platform Constraints

**1. Work Profile Provisioning:**
- Problem: Creating a Managed Work Profile and setting APK Scope as `ProfileOwner` requires device-level permissions. On standard retail devices, this requires initial ADB setup (`dpm set-profile-owner`) or managed provisioning.
- Impact: Non-technical users cannot enable runtime isolation with a single tap without provisioning prerequisites.
- Mitigation/Path: Clear onboarding instructions and readiness status checks in the dashboard UI.

**2. HTTPS Inspection POC Trust Anchors:**
- Problem: Since Android 7.0 (API 24), apps do not trust user-installed CA certificates unless their `network_security_config.xml` explicitly allows user CAs or the app is run in debuggable mode. Commercial apps with certificate pinning will fail to connect under TLS interception.
- Files: `core/network/src/main/kotlin/com/nadeem/apkscope/core/network/https/`, `docs/HTTPS_INSPECTION_POC.md`
- Impact: HTTPS interception POC is strictly applicable to compatible debug builds or test fixture apps, not arbitrary hardened commercial APKs.
- Mitigation/Path: Documented as an opt-in POC; default analysis remains non-intercepting socket wire observation.

**3. Memory Limits for Large APKs:**
- Problem: Unpacking and parsing very large APK files (100MB+) entirely in memory can trigger `OutOfMemoryError` on low-end Android devices.
- Files: `core/staticanalysis/`
- Recommendation: Ensure streaming ZIP parsing and partial DEX header reading rather than loading full APK archives into byte arrays.

**4. VPN Egress & Network Resilience:**
- Problem: Local VPN forwarding must handle connection drops, high throughput bursts, and DNS timeouts gracefully without locking worker threads.
- Files: `core/network/src/main/kotlin/com/nadeem/apkscope/core/network/ForwardingEngine.kt`
- Recommendation: Add automated stress testing with the fixture app under continuous network traffic.

---

*Concerns audit: 2026-09-10*
