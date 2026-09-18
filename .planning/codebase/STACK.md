# Technology Stack

**Analysis Date:** 2026-09-10

## Languages

**Primary:**
- Kotlin 2.4.10 — Used across all modules (`:app`, `:core:*`, `:fixture`, `:riskfixture`)
- Java 17 — Java bytecode compatibility target

## Runtime

**Environment:**
- Android OS (API 30 / Android 11 minimum, target API 36 / Android 16, compile API 37)
- Java Runtime: OpenJDK 17

**Build Tool / Package Manager:**
- Gradle 8.x / AGP 9.4.0
- Kotlin Gradle Plugin 2.4.10
- KSP (Kotlin Symbol Processing) 2.3.11

## Frameworks

**UI & Presentation:**
- Jetpack Compose (BOM 2025.09.00)
- Material3 (`androidx.compose.material3:material3`)
- Compose Icons Extended (`androidx.compose.material:material-icons-extended`)
- Navigation Compose 2.9.6
- Lifecycle ViewModel Compose 2.9.4

**Core & Concurrency:**
- AndroidX Core / Core-KTX 1.19.0
- Kotlin Coroutines Android 1.10.2

**Persistence & Data:**
- AndroidX Room 2.8.4 (KSP code generation, SQLite on-device storage)
- Kotlinx Serialization 2.4.10 (JSON serialization)

**Testing:**
- JUnit 4 / JUnit 5
- MockK / Mockito for mocking
- AndroidX Test Core & AndroidJUnitRunner
- Compose UI Testing (`androidx.compose.ui:ui-test-junit4`)

## Key Dependencies

**Critical:**
- Android Device Administration (`android.app.admin.DevicePolicyManager`): Manages the work profile and ingests OS security telemetry.
- Android VPN Subsystem (`android.net.VpnService`): Routes isolated Work Profile IP packets to local user-space sockets.
- Room SQLite: Stores analysis sessions, extracted capabilities, raw network logs, and deterministic risk reports.

## Configuration

**Build:**
- Root: `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`
- Per-module: `app/build.gradle.kts`, `core/*/build.gradle.kts`

**Platform Requirements:**
- Development: Android Studio Ladybug / Meerkat or CLI with Android SDK (API 37 SDK platform and build-tools installed), JDK 17+.
- Execution: Physical Android device or emulator running Android 11+ (API 30+) with Managed Profile support.

---

*Stack analysis: 2026-09-10*
