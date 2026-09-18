# Security Policy

## Supported Versions

Only the latest release of APK Scope receives security updates and bug fixes:

| Version | Supported |
| :--- | :--- |
| 0.1.x | :white_check_mark: |
| < 0.1 | :x: |

---

## Reporting a Vulnerability

We take the security of APK Scope seriously. If you discover a potential vulnerability—particularly around sandbox escapes, unintended cross-profile leaks, unvalidated intent processing, or credential exposure—please report it responsibly.

### How to Report

1. **Do NOT open a public GitHub issue** for sensitive security vulnerabilities.
2. Use **GitHub Private Vulnerability Reporting** via the repository's **Security** tab:
   - Navigate to `Security` -> `Advisories` -> `Report a vulnerability`.
3. If private vulnerability reporting is unavailable, please open a minimal issue requesting a private security communication channel with maintainers without disclosing exploit details.

### What to Include in Your Report

To help us investigate and triage the issue quickly, please provide:
- A clear description of the vulnerability and its potential impact.
- Exact device model and Android version tested (e.g., Pixel 8 running Android 15).
- APK Scope version (e.g., v0.1.0) and build type (Debug / Release).
- Step-by-step reproduction instructions.
- A minimal proof-of-concept (PoC) APK or command sequence, if applicable.
- Any relevant `logcat` output (ensuring no sensitive personal tokens or secrets are included).

---

## Security Response Commitment

- We will acknowledge receipt of your report within 72 hours.
- We will provide an assessment of the vulnerability and coordinate disclosure timelines.
- Public disclosure will occur following the release of a patch or mutually agreed mitigation period.
