# Platform Limitations & Operational Boundaries

APK Scope operates under standard, unprivileged Android platform APIs and Android Enterprise capabilities. It makes deliberate trade-offs to remain rootless, secure, and respectful of Android OS constraints.

Before evaluating or deploying APK Scope, understand the following architectural boundaries and limitations.

---

## 1. Environment & Provisioning Limitations

1. **Work Profile Requirement**:
   - APK Scope requires a Managed Work Profile to create its isolated testing container.
   - Most consumer Android devices support only **one** active Work Profile. If the device already has a corporate Work Profile configured (e.g., via Microsoft Intune or Google Workspace), provisioning another Work Profile is not permitted by Android.
2. **Android Enterprise & Play Protect Gates**:
   - Work Profile provisioning via `ACTION_PROVISION_MANAGED_PROFILE` is gated by Android OS policies and Google Play Protect.
   - On certain OEM distributions, unmanaged or sideloaded Device Policy Controllers (DPCs) may be restricted from provisioning without explicit user authorization or developer setup.
3. **Coexistence with Private Space**:
   - On Android 15+, Private Space (`android.os.UserManager.USER_TYPE_PROFILE_PRIVATE`) is distinct from a Managed Work Profile (`android.os.UserManager.USER_TYPE_PROFILE_MANAGED`). While they can coexist, Private Space does not provide DPC administrative hooks or network logging.

---

## 2. Containment & Sandbox Boundaries

1. **Not a Virtual Machine**:
   - APK Scope is **not** a hypervisor, virtual machine, or emulator. It runs applications directly on the host device's Linux kernel within an Android multi-user sandbox (`uid` isolation).
2. **Kernel Isolation**:
   - The isolation boundary is enforced by Android UID separation, SELinux policies, and permission sandboxes.
   - It does not protect against local privilege escalation (LPE) exploits targeting Linux kernel zero-days or system-level vulnerabilities.
3. **No Silent Installation or Removal**:
   - In compliance with Android security guarantees, installing or uninstalling an APK requires explicit system user confirmation via `PackageInstaller` dialogs.
   - APK Scope cannot silently install or uninstall third-party packages without user interaction.

---

## 3. Network Observation Limitations

1. **No TLS MITM / Decryption**:
   - APK Scope does **not** install custom root CA certificates or perform man-in-the-middle (MITM) decryption of HTTPS traffic.
   - HTTPS request paths, query parameters, headers, and request/response bodies are **invisible**. Only network metadata (destination IP, port, SNI where parsed, protocol, timing, and byte volume) is recorded.
2. **Certificate Pinning**:
   - Because no MITM decryption is performed, certificate pinning bypass is neither attempted nor supported.
3. **Encrypted DNS (DoH / DoT / Private DNS)**:
   - System-wide Private DNS (DNS-over-TLS) or in-app DNS-over-HTTPS bypasses standard UDP/53 DNS parsing. Queries over TCP/853 or HTTPS/443 appear as encrypted transport connections.
4. **IPv6 Fail-Closed**:
   - In v0.1, IPv6 traffic is dropped fail-closed at the VPN interface (`tun0`) to prevent leakage over unmonitored routes. Applications requiring pure IPv6 may experience connection failures.
5. **VPN vs. DPM Discrepancies**:
   - `ObservedBehavior` (from the local VPN forwarder) and `AndroidEvidence` (from Android DPM network logging) can differ slightly. For example, system DNS resolver caches or transport retries may result in differing packet counts.

---

## 4. DPM Network Logging Asynchrony

1. **Asynchronous Batching**:
   - Android OS controls the delivery cadence of `onNetworkLogsAvailable`. In normal production operations, the OS batches logs and delivers them asynchronously (often requiring 90–120 seconds or several minutes depending on device state, power, and buffer limits).
2. **Testing vs. Production**:
   - While developer testing can force log batch retrieval using `adb shell dpm force-network-logs`, production users must wait for Android to deliver batches naturally, or finalize sessions after a reasonable grace period.

---

## 5. Risk Scoring & Behavioral Interpretation

1. **Score is Not Malware Probability**:
   - The risk score (0–100) produced by `combined-v1` is a deterministic, heuristic metric reflecting declared capabilities and observed network surface area.
   - **It is not a statistical probability or a guarantee that an APK is benign or malicious.**
2. **Dynamic Analysis Coverage**:
   - Dynamic network observation records only the behaviors triggered during the active session. If an APK contains dormant triggers (e.g., time bombs, geofences, or unexercised UI flows), those behaviors will not appear in the runtime report.
3. **Absence of Evidence is Not Evidence of Absence**:
   - A score of 0 / "Low Risk" does not guarantee an application is safe. It merely indicates that no high-risk capabilities or suspicious network behaviors were declared or observed during the session.
