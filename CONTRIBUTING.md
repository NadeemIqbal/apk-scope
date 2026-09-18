# Contributing to APK Scope

Thank you for your interest in contributing to APK Scope! We welcome issues, pull requests, and discussions from the community.

---

## 1. Prerequisites & Toolchain

APK Scope is pinned to a modern, reproducible Android build toolchain:

- **Java Development Kit (JDK)**: OpenJDK 17 (LTS)
- **Android Gradle Plugin (AGP)**: 9.4.0
- **Gradle**: 9.7.1 (use `./gradlew`)
- **Kotlin**: 2.4.10
- **Android SDK Requirements**:
  - `compileSdk`: 37
  - `targetSdk`: 36
  - `minSdk`: 30 (Android 11+)

> [!NOTE]
> Never commit machine-specific paths (such as `local.properties`). Build scripts and test suites must resolve dependencies and the Android SDK dynamically via standard environment variables (`ANDROID_HOME`, `ANDROID_SDK_ROOT`).

---

## 2. Core Architectural Principles & Boundaries

Every contribution must respect the core security and isolation boundaries of APK Scope:

1. **Strict Evidence Provenance Separation**:
   - Never conflate `DeclaredCapability` (static analysis), `ObservedBehavior` (VPN layer), and `AndroidEvidence` (DPM OS logging).
   - Each evidence type must maintain its distinct schema, database table, and provenance tag.
2. **No Fabricated or Synthetic Observations**:
   - Production code must never synthesize network events, fake DNS responses, or inject mock data into production Room databases.
3. **No Hidden APIs or Root Exploits**:
   - All functionality must use public, documented Android APIs and standard Android Enterprise capabilities.
   - Do not invoke private framework APIs via reflection or rely on `su`/root escalation.
4. **Zero Feature Creep for v0.1**:
   - TLS decryption/MITM, custom CA certificate injection, certificate pinning bypass, and HTTP payload inspection are explicitly out of scope.
5. **Explainable Risk Scoring**:
   - Risk scoring must remain deterministic and traceable to concrete evidence. Never present scores as "malware confidence" or "infection probability".

---

## 3. Building & Testing Locally

### Clean Build & JVM Unit Tests
```bash
# Clean project
./gradlew clean

# Run all JVM unit tests (core, app, analysis, vpn, risk engines)
./gradlew test

# Run Android Lint
./gradlew lintDebug
```

### Release Build Assembly
```bash
./gradlew assembleRelease
```

### Instrumented Tests (Requires Emulator or Physical Device)
Ensure an active Android 11+ device or emulator is connected via `adb`:
```bash
./gradlew connectedAndroidTest
```

### Hardening & Fuzz Tests
Run the dedicated networking hardening suites:
```bash
./gradlew :core:vpn:test --tests "com.nadeem.apkscope.vpn.PacketValidationFuzzTest"
./gradlew :core:vpn:test --tests "com.nadeem.apkscope.vpn.EngineHardeningTest"
./gradlew :core:vpn:test --tests "com.nadeem.apkscope.vpn.DestinationPolicyTest"
./gradlew :core:vpn:test --tests "com.nadeem.apkscope.vpn.DnsMessageTest"
```

---

## 4. Pull Request Guidelines

1. **Branch Naming**: Use clear prefixes, e.g., `fix/vpn-leak`, `docs/architecture-update`, `refactor/room-migration`.
2. **Test Coverage**: Any functional change or bug fix must include corresponding unit tests in `test/` or instrumented tests in `androidTest/`.
3. **Commit Cleanliness**:
   - No committed secrets, API keys, personal paths, or temporary debug logs.
   - Gated debug test activities behind `BuildConfig.DEBUG`.
4. **Continuous Integration**: Ensure all CI checks (clean build, unit tests, and lint) pass without warnings or errors before requesting review.
