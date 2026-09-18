# Architecture & System Design — APK Scope

## 1. Overview & Dual-Persona Architecture

APK Scope operates as a strictly local, unprivileged Android application divided across two distinct user personas provided by Android Enterprise:

1. **Personal Profile (User 0)**:
   - Primary user interface, static analysis engine, central Room database (`sandbox.db`), risk evaluation pipelines, and persistent reporting history.
   - Holds no elevated device administration privileges.
   - User-controlled selection of APK files via Android Document Picker (`ACTION_OPEN_DOCUMENT`).

2. **Work Profile (Managed User, e.g., User 10/11)**:
   - Contains an instance of APK Scope designated as **Profile Owner (DPC)**.
   - Possesses administrative control restricted strictly to the managed work environment.
   - Hosts the sandboxed target application under test.
   - Enforces a dedicated local VPN service (`WorkProfileVpnService`) with fail-closed destination routing.
   - Collects OS-level Device Policy Manager (DPM) network logging batches.

```
+-------------------------------------------------------------------------------+
|                             PERSONAL PROFILE (User 0)                        |
|                                                                               |
|  +------------------+     +--------------------+     +---------------------+  |
|  |  Document Picker | --> |   Static Analyzer  | --> |  Static Risk Engine |  |
|  +------------------+     +--------------------+     +---------------------+  |
|                                                                  |            |
|                                                                  v            |
|  +------------------+     +--------------------+     +---------------------+  |
|  |   Final Report   | <-- |   Combined Risk    | <-- | Session Coordinator |  |
|  |  Reconciliation  |     |   Engine v1        |     |   (Room DB v6)      |  |
|  +------------------+     +--------------------+     +---------------------+  |
|           ^                         ^                            |            |
|           | ACK                     | Import                     | Handoff    |
+-----------|-------------------------|----------------------------|------------+
            |                         |                            |
 Cross-Profile Intent Grants          | Cross-Profile FileProvider |
            |                         |                            |
+-----------|-------------------------|----------------------------|------------+
|           v                         |                            v            |
|  +------------------+     +--------------------+     +---------------------+  |
|  | Work Lifecycle   |     | AndroidEvidence &  |     | Work Profile DPC    |  |
|  | Coordinator      |     | Observation Export |     | (Profile Owner)     |  |
|  +------------------+     +--------------------+     +---------------------+  |
|           |                                                      |            |
|           v                                                      v            |
|  +-------------------------------------------------------------------------+  |
|  |                      Work Profile VPN & DPM Logging                     |  |
|  |  - WorkProfileVpnService (Local tun0, Explicit DNS, RFC1918 Deny)       |  |
|  |  - DevicePolicyManager Network Logging (DnsEvent, ConnectEvent)        |  |
|  +-------------------------------------------------------------------------+  |
|                                   |                                           |
|                                   v                                           |
|  +-------------------------------------------------------------------------+  |
|  |                     Isolated Target Application                         |  |
|  |  (Installed via PackageInstaller with explicit user confirmation)       |  |
|  +-------------------------------------------------------------------------+  |
|                                                                               |
|                              WORK PROFILE (User 11)                           |
+-------------------------------------------------------------------------------+
```

---

## 2. Component Breakdown

### 2.1 Static Analysis Engine
- **Implementation**: Pure Kotlin DEX/Manifest parser (`core:analysis`).
- **Function**: Parses raw APK binaries without executing target code or using dynamic reflection. Extracts package metadata, requested and dangerous permissions, declared components (Activities, Services, Receivers, Providers), URL/IP strings, and intent filters.
- **Output**: Generates `DeclaredCapability` records persisted to Room.

### 2.2 Profile Owner (DPC) & Work Profile Lifecycle
- **Receiver**: `SandboxDeviceAdminReceiver` registered with `android.app.action.DEVICE_ADMIN_ENABLED` and `android.app.action.PROFILE_PROVISIONING_COMPLETE`.
- **Capabilities**:
  - Sets network logging: `setNetworkLogging(admin, true)`.
  - Configures VPN enforcement: `setAlwaysOnVpnPackage(admin, packageName, true)`.
  - Performs lifecycle cleanup: `clearApplicationUserData(...)`, PackageInstaller uninstalls.
  - Manages cross-profile intent filtering to prevent target leakage.

### 2.3 Network Observation Engine
- **Service**: `WorkProfileVpnService` (`core:vpn`).
- **Routing**: Binds a local `tun0` virtual network interface via `VpnService.Builder`.
- **Loopback & Stack**: Implements user-space TCP/UDP routing.
  - IPv6 is blocked fail-closed (dropped without DNS AAAA synthesis).
  - RFC1918 private subnets (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`) are blocked fail-closed with explicit policy denial audit records.
  - Explicit UDP port 53 DNS parsing extracts host queries, answers, and query types.
  - Sockets are protected via `VpnService.protect(socket)` to route legitimate WAN traffic outside `tun0`.
- **Live Stream**: Streams real-time `ObservedBehavior` records (timestamps, endpoints, protocols, byte counters, policy decisions) to the UI and memory queue.

### 2.4 Device Policy Manager (DPM) Network Logging
- **Platform Capability**: Android Enterprise OS-level network auditing (`android.app.admin.DevicePolicyManager`).
- **Data Model**: `NetworkEvent` stream containing:
  - `DnsEvent`: Hostname, IP addresses, timestamp, package UID.
  - `ConnectEvent`: Destination `InetAddress`, port, protocol, timestamp, package UID.
- **Batch Processing**: Android asynchronously batches network logs. The DPC receives `onNetworkLogsAvailable(context, intent, batchToken)`.
- **Target Filtering**: The raw Android DPM batch captures all traffic within the managed profile. The filtering engine deterministically attributes events to the target UID/package, filtering out VPN self-traffic (`com.nadeem.apkscope`) and pre-installed system apps (`com.android.vending`, `com.android.chrome`). Filtered events are stored as `AndroidEvidence`.

---

## 3. Cross-Profile Transport & Coordination

Communication between Personal and Work profiles relies strictly on standard Android IPC mechanisms without custom sockets or background daemons:

1. **Activity Handoffs**: Explicit intents directed to `CrossProfileHandoffActivity` with URI read permissions.
2. **File Sharing**: Secured via `FileProvider` with temporary read grants:
   - Personal transfers APK to Work: `Intent.FLAG_GRANT_READ_URI_PERMISSION`.
   - Work copies APK into isolated app storage before triggering `PackageInstaller`.
   - Work transfers `RuntimeObservationArtifact` and `AndroidEvidenceArtifact` back to Personal via one-time content URIs.
3. **Acknowledgment Protocol (ACK)**:
   - Personal imports artifact into `sandbox.db`.
   - Personal sends an explicit ACK intent back to Work.
   - Work deletes temporary export JSON files and cleans up session state upon receiving ACK.

---

## 4. Evidence Taxonomy & Scoring Pipeline

APK Scope maintains a strict ontological separation across evidence types:

| Evidence Type | Provenance | Description | Example |
| :--- | :--- | :--- | :--- |
| **`DeclaredCapability`** | Static APK Parser | Capabilities declared in `AndroidManifest.xml` or extracted from DEX binaries. | `android.permission.INTERNET`, `RECEIVE_BOOT_COMPLETED` |
| **`ObservedBehavior`** | Work VPN Interface | Real-time network events recorded at the user-space VPN layer (`tun0`). | TCP connection to `93.184.216.34:443`, 1.4 KB sent |
| **`AndroidEvidence`** | Android OS Kernel / DPM | OS-level audit events delivered by `DevicePolicyManager` network logging. | `DnsEvent(hostname=example.com, uid=10150)` |
| **`Inferred/Correlated Risk`** | Risk Engines | Deterministic heuristic rules correlating static and runtime evidence. | Capability declared + runtime socket active = escalated score |

### Risk Evaluation Stages:
1. **`static-v1`**: Evaluates declared permissions, exported components, and cleartext traffic declarations (Score: 0–100).
2. **`runtime-v1`**: Evaluates live network behaviors, unverified DNS resolutions, raw IP connections, and RFC1918 access attempts (Score: 0–100).
3. **`combined-v1`**: Correlates static declarations with runtime evidence, applying double-count prevention and clamping final score between 0 and 100.
