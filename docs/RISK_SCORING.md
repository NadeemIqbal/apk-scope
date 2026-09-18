# Risk Scoring Framework & Heuristic Specification

## 1. Philosophical Foundation

The APK Scope risk engine produces a transparent, deterministic, and fully explainable evaluation of an application's declared capabilities and observed runtime behaviors.

> [!IMPORTANT]
> **Risk Score is NOT Malware Probability.**
> The score (0–100) does not represent a machine learning confidence level, a signature match probability, or an absolute determination of malware vs. benign software. It is an algorithmic summary of potential attack surface, privacy exposure, and anomalous network patterns.

---

## 2. Risk Levels & Score Bands

All individual engines (`static-v1`, `runtime-v1`) and the unified pipeline (`combined-v1`) map final numerical scores into four standardized risk tiers:

| Score Range | Risk Level | Description |
| :---: | :---: | :--- |
| **0 – 24** | **Low** | Standard consumer behavior. Minimal sensitive permissions and standard public Internet connectivity. |
| **25 – 49** | **Moderate** | Elevated permissions or noteworthy network behaviors (e.g., location access, direct raw IP connections). |
| **50 – 74** | **High** | Potentially invasive capabilities (e.g., background recording, SMS access, unauthorized RFC1918 private network scanning). |
| **75 – 100** | **Critical** | Highly dangerous combinations of privileges (e.g., device admin requests, boot persistence, accessibility hooks, massive policy violations). |

---

## 3. Engine Breakdown

### 3.1 Static Risk Engine (`static-v1`)
Evaluates the declared manifest attributes, requested Android permissions, component visibility, and hardcoded network security configurations.

| Rule ID | Weight | Trigger Condition | Rationale |
| :--- | :---: | :--- | :--- |
| `STATIC_PERM_INTERNET` | 10 | Requests `android.permission.INTERNET` | Baseline external network capability. |
| `STATIC_PERM_LOCATION` | 20 | Requests `ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION` | Geolocation privacy exposure. |
| `STATIC_PERM_BACKGROUND_LOCATION` | 25 | Requests `ACCESS_BACKGROUND_LOCATION` | Continuous tracking capability without active UI. |
| `STATIC_PERM_CAMERA` | 20 | Requests `android.permission.CAMERA` | Sensor access for visual surveillance. |
| `STATIC_PERM_RECORD_AUDIO` | 20 | Requests `android.permission.RECORD_AUDIO` | Eavesdropping / microphone recording. |
| `STATIC_PERM_SMS` | 30 | Requests `READ_SMS`, `SEND_SMS`, or `RECEIVE_SMS` | Financial fraud / 2FA interception risk. |
| `STATIC_PERM_CONTACTS` | 20 | Requests `READ_CONTACTS` or `WRITE_CONTACTS` | Address book data exfiltration risk. |
| `STATIC_PERM_STORAGE` | 15 | Requests `READ_EXTERNAL_STORAGE` or `MANAGE_EXTERNAL_STORAGE` | Arbitrary file system access. |
| `STATIC_PERM_BOOT` | 15 | Requests `RECEIVE_BOOT_COMPLETED` | Persistent autostart without user invocation. |
| `STATIC_CLEARTEXT_TRAFFIC` | 25 | `android:usesCleartextTraffic="true"` | Allows unencrypted HTTP communication. |
| `STATIC_EXPORTED_COMPONENTS` | 15 | Unprotected exported Activities/Services/Receivers | Inter-app surface vulnerable to intent injection. |

*Subtotal is clamped to `[0, 100]`.*

---

### 3.2 Runtime Risk Engine (`runtime-v1`)
Evaluates dynamic network interactions observed through the local VPN interface (`tun0`) and OS-level `AndroidEvidence` delivered by DPM.

| Rule ID | Weight | Trigger Condition | Rationale |
| :--- | :---: | :--- | :--- |
| `RUNTIME_DIRECT_RAW_IP` | 25 | Outbound TCP/UDP connection to a public IP without prior DNS lookup | Common C2 (command & control) evasion tactic. |
| `RUNTIME_RFC1918_ATTEMPT` | 35 | Attempted connection to private LAN (`10.0.0.0/8`, `192.168.0.0/16`) | Lateral movement or local router exploit attempt. |
| `RUNTIME_HIGH_DATA_EXFIL` | 20 | Upload volume exceeds 10 MB in a single session | Potential mass data exfiltration. |
| `RUNTIME_CLEAR_HTTP_ACTIVE` | 20 | Outbound plaintext HTTP observed on port 80/8080 | Active transmission of unencrypted payload. |
| `RUNTIME_UNRESOLVED_DNS_FLOOD` | 15 | > 20 failed or NXDOMAIN DNS lookups | Potential DGA (domain generation algorithm) activity. |
| `RUNTIME_NON_STANDARD_PORT` | 15 | Outbound TCP connection to ports other than 80, 443, 8080 | Custom protocol tunneling or C2 communication. |

*Subtotal is clamped to `[0, 100]`.*

---

### 3.3 Combined Risk Engine (`combined-v1`)
Synthesizes static capabilities and runtime observations, applying **double-count prevention** and **correlation multipliers**:

1. **Correlation Rules**:
   - `COMBINED_INTERNET_WITHOUT_PERMISSION`: Runtime network activity observed for an app that lacked standard permissions (Security bypass). **+50**
   - `COMBINED_DECLARED_AND_EXERCISED_NETWORK`: Both static Internet permission declared and active outbound connections observed. Reconciles static risk without double-counting.
   - `COMBINED_STEALTH_EXFILTRATION`: High background data transfer without active foreground activity. **+30**
2. **Double-Count Prevention**:
   - If a risk is identified in both static analysis (e.g., cleartext traffic enabled) and runtime observation (e.g., cleartext HTTP traffic executed), the engine deduplicates the penalty by applying an adjusted delta rather than a simple sum.
3. **Clamping & Final Score**:
   $$\text{Final Score} = \min(100, \max(0, \text{Static Subtotal} + \text{Runtime Subtotal} + \text{Correlation Delta}))$$

Every point added to the score links directly to an underlying `DeclaredCapability`, `ObservedBehavior`, or `AndroidEvidence` record in the database.
