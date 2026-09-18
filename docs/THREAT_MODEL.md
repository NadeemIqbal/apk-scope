# APK Scope — Threat Model (v0.1)

## 1. Assets Protected

APK Scope is designed to protect the user's primary operating environment while analyzing potentially untrusted or malicious Android application packages (APKs). Key assets protected include:

1. **Personal Profile Data:** Private user contacts, call logs, SMS messages, account credentials, photos, personal storage files, and installed personal apps.
2. **Private Space Data:** Isolated private space profiles and credentials on supported modern Android versions (Android 15+).
3. **Local Network Perimeter:** Intranet assets, RFC 1918 private subnets (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16), link-local interfaces (169.254.0.0/16), loopback addresses (127.0.0.0/8), and cloud metadata endpoints (169.254.169.254).
4. **Device Integrity:** Prevention of persistent, silent device compromise via unauthorized administrative rights, uncontrolled app installation, or unmonitored lateral network movement.

---

## 2. Trust Boundaries & Components

```text
┌─────────────────────────────────────────────────────────────┐
│                      Android Operating System               │
│                                                             │
│   ┌──────────────────────────┐  Intent Handoff  ┌──────────┴───────────────┐
│   │     Personal Profile     ├─────────────────►│   Managed Work Profile    │
│   │                          │                  │  (User ID 11 / Sandbox)   │
│   │  • APK Scope App       │                  │                           │
│   │  • Static Analyzer       │                  │  • APK Scope DPC        │
│   │  • Risk Engine           │◄─────────────────┤  • Sandbox Worker Service │
│   │  • Personal Room DB      │  Report Import   │  • Work Evidence Store    │
│   │  • User Reports & UI     │                  │                           │
│   └──────────────────────────┘                  │  • Always-On VPN Service  │
│                                                 │    (Forwarding & Policy)  │
│                                                 └───────────┬───────────────┘
│                                                             │ TUN Interface
│                                                             ▼
│                                                 ┌───────────────────────────┐
│                                                 │       Target APK          │
│                                                 │  (Isolated Sandboxed App) │
│                                                 └───────────┬───────────────┘
│                                                             │ Monitored Packets
│                                                             ▼
│                                                 ┌───────────────────────────┐
│                                                 │         Internet          │
│                                                 │   (Public Egress Only)    │
└─────────────────────────────────────────────────┴───────────────────────────┘
```

### Trust Boundary 1: Personal Profile vs. Work Profile
- **Boundary Mechanism:** Android Enterprise Managed Profile isolation (`UserManager.isManagedProfile`).
- **Separation:** Processes in the Work Profile run under distinct Linux UIDs and a distinct Android User ID. They cannot access Personal profile content providers, private directories (`/data/user/0`), or SQLite databases.
- **Cross-Profile Transport:** Only explicitly permitted Intents via `CrossProfileApps` and `DevicePolicyManager.addCrossProfileIntentFilter` are allowed. All file payloads are exchanged via a private `FileProvider` with short-lived `FLAG_GRANT_READ_URI_PERMISSION` and bounded copy validation.

### Trust Boundary 2: Work Profile Sandbox vs. Target APK
- **Boundary Mechanism:** Standard Android application sandbox (UID isolation, SELinux policies) within the managed user profile, augmented by DPC restrictions.
- **Controls Applied:**
  - `UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES` (controlled via DPC)
  - `DevicePolicyManager.PERMISSION_POLICY_PROMPT`
  - Ephemeral runtime lifecycle (app stopped, data cleared via `clearApplicationUserData`, and package uninstalled upon session termination).

### Trust Boundary 3: Target APK vs. Network
- **Boundary Mechanism:** Always-On VPN with Lockdown (`dpm.setAlwaysOnVpnPackage(admin, pkg, true)`).
- **Enforcement:** Fail-closed TUN interface. If the VPN service is inactive or crashes, Android's OS kernel blocks all network egress for apps in the managed profile.
- **Destination Policy:** The forwarding engine parses IPv4 packets and unconditionally blocks connections targeting RFC 1918, link-local, loopback, multicast, or cloud metadata ranges (`POLICY_DENIED`). IPv6 is blocked to prevent unmonitored egress bypass.

---

## 3. Attacker Model

We assume the target APK may be authored by an adversary with the following capabilities:
- Can execute arbitrary native (C/C++) or Java/Kotlin bytecode within its sandbox.
- Can attempt to establish direct TCP/UDP sockets to external servers or local LAN resources.
- Can attempt to discover host network topology via raw socket probes or DNS lookups.
- Can attempt to query, probe, or abuse accessible IPC interfaces and exported components.
- Can attempt to persist across app launches, process death, or device reboots.
- Can attempt to refuse termination or uninstall.

---

## 4. Isolation Assumptions & Defenses

| Threat Vector | Defense Mechanism | Residual Risk / Limitation |
| :--- | :--- | :--- |
| **Data Exfiltration from Personal Profile** | Work Profile process separation prevents reading personal profile storage, contacts, or photos. | Target APK can access files explicitly created within its own work profile storage. |
| **Lateral Intranet Scanning / Attack** | DestinationPolicy drops RFC 1918 / link-local / loopback packets and records `POLICY_DENIED`. | Target can contact public Internet IP addresses unless external firewall rules apply. |
| **Bypassing Network Monitor via Direct IP** | All IPv4 traffic is forced through the TUN interface. Connections without prior DNS resolution are detected and flagged as `RUNTIME_RAW_IP_CONNECTION`. | Target app can communicate with public IP addresses directly (though factually recorded). |
| **Bypassing Network Monitor via IPv6** | IPv6 traffic is dropped by the forwarding engine. | Target cannot communicate over IPv6. |
| **Unauthorized Background Persistence** | Session teardown enforces app suspension, `clearApplicationUserData`, and Android package removal. | If user cancels uninstall confirmation, app remains installed until cleanup is retried. |
| **Spoofing Evidence / Tampering** | Evidence is multi-sourced: wire traffic from VPN TUN and OS-level audit logs from Android `DevicePolicyManager` network logging. | Wire traffic and DPM logs can differ in timing due to OS asynchronous log batching. |

---

## 5. Explicit Out-of-Scope Threats & Non-Goals

1. **Work Profile is NOT a Virtual Machine (VM):**
   - The Work Profile relies on the Android Linux kernel and Android runtime (ART).
   - It does **not** protect against kernel-level privilege escalation (local root exploits) or hardware-level microarchitectural attacks (Rowhammer, Spectre).
2. **Encrypted Payload Inspection (TLS MITM):**
   - APK Scope preserves standard end-to-end TLS encryption. It does **not** install custom root CA certificates, modify app network security configs, or bypass certificate pinning.
   - HTTP URL paths, headers, and request/response JSON bodies are **not visible**.
3. **Automated User Gesture Exploitation:**
   - Package installation and uninstallation require Android system confirmation dialogs. APK Scope does not use Accessibility APIs to bypass user consent.
