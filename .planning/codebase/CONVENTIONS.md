# Coding Conventions

**Analysis Date:** 2026-09-10

## Naming Patterns

**Files:**
- Kotlin classes & files: PascalCase (`AnalysisScreen.kt`, `RiskEngine.kt`, `SandboxVpnService.kt`).
- Android XML resources: snake_case (`activity_main.xml`, `ic_shield.xml`).

**Functions & Methods:**
- Standard functions: camelCase (`calculateRiskScore()`, `parseDnsPacket()`).
- Jetpack Compose components: PascalCase (`AnalysisScreen()`, `RiskBadge()`, `EvidenceCard()`).

**Variables & Properties:**
- Variables: camelCase (`sessionStatus`, `observedCount`).
- Constants: UPPER_SNAKE_CASE (`DEFAULT_TIMEOUT_MS`, `MAX_PACKET_SIZE`).
- Backing properties: Leading underscore (`_uiState`).

**Types & Classes:**
- Classes, Interfaces, Enums: PascalCase (`SessionCoordinator`, `DeclaredCapability`, `RiskLevel`).
- Sealed hierarchies: Base class in PascalCase, subclasses in PascalCase.

## Code Style & Idioms

**Jetpack Compose:**
- State Hoisting: Screens accept a state data class and event lambda callbacks (`onAction: (Action) -> Unit`).
- ViewModel Integration: Expose state via `StateFlow<ScreenState>`, collected using `collectAsStateWithLifecycle()`.
- Modifiers: First optional parameter on reusable composables (`modifier: Modifier = Modifier`).

**Coroutines & Concurrency:**
- Explicit Dispatchers: Never use `Dispatchers.Main` for heavy I/O, parsing, or DB operations; inject or explicitly dispatch to `Dispatchers.IO` or `Dispatchers.Default`.
- Structured Concurrency: Scope coroutines to `viewModelScope`, `lifecycleScope`, or managed `CoroutineScope`.

**Error Handling:**
- Deterministic errors: Model domain errors as sealed classes or Kotlin `Result<T>`.
- Avoid silent swallowing of exceptions in network packet loops or parsing routines.

**Immutability:**
- Domain models and evidence objects must be immutable (`val` properties on data classes).

---

*Convention analysis: 2026-09-10*
