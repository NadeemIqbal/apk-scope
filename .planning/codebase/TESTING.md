# Testing Patterns

**Analysis Date:** 2026-09-10

## Test Frameworks

**Unit Testing:**
- JUnit 4 / JUnit 5
- MockK for Kotlin mocking and stubbing
- Kotlin Coroutines Test (`runTest`, `StandardTestDispatcher`)
- Google Truth / JUnit Assertions

**Instrumentation & UI Testing:**
- AndroidX Test Runner (`AndroidJUnitRunner`)
- Compose Testing (`androidx.compose.ui:ui-test-junit4`)
- Integration tests in `:app/src/androidTest/`

## Run Commands

```bash
# Run all unit tests across all modules
./gradlew test

# Run unit tests for a specific module
./gradlew :core:network:test
./gradlew :core:risk:test
./gradlew :core:staticanalysis:test

# Run connected Android instrumentation tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Build test fixtures
./gradlew :fixture:assembleDebug
./gradlew :riskfixture:assembleDebug
```

## Test File Organization

- Unit Tests: Co-located in `<module>/src/test/kotlin/` mirroring package hierarchy.
- Instrumentation Tests: Co-located in `<module>/src/androidTest/java/` or `<module>/src/androidTest/kotlin/`.

## Fixtures & Test Apps

- `:fixture`: Benign test app declaring common permissions, performing standard network requests to verify VPN capture and DNS resolution.
- `:riskfixture`: High-risk synthetic test app designed to trigger multiple static and runtime risk engine detection rules for verification.

---

*Testing analysis: 2026-09-10*
