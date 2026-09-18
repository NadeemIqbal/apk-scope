<!-- refreshed: 2026-09-10 -->
# Architecture

**Analysis Date:** 2026-09-10

## System Overview

```text
┌───────────────────────────────────────────────────────────────────────────┐
│                          PERSONAL PROFILE (Host UI)                       │
├────────────────────────┬─────────────────────────┬────────────────────────┤
│     Jetpack Compose    │       ViewModels        │  Session Coordinator   │
│      `app/ui/...`      │   `app/viewmodel/...`   │  `core/sandbox/...`    │
└───────────┬────────────┴────────────┬────────────┴───────────┬────────────┘
            │                         │                        │
            ▼                         ▼                        ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                               CORE SERVICES                               │
├────────────────────────┬─────────────────────────┬────────────────────────┤
│    Static Analyzer     │     Risk Engine         │     Room Database      │
│ `core/staticanalysis/` │     `core/risk/`        │    `core/database/`    │
└────────────────────────┴────────────┬────────────┴────────────────────────┘
                                      │ (IPC / Intents)
                                      ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                     MANAGED WORK PROFILE (Isolation Boundary)             │
├────────────────────────┬─────────────────────────┬────────────────────────┤
│   Target Application   │       Local VPN         │   OS DPM Telemetry     │
│   (Isolated Execution) │    `core/network/`      │ `android.app.admin`    │
└────────────────────────┴─────────────────────────┴────────────────────────┘
```

## Component Responsibilities

| Component | Responsibility | Module |
|-----------|----------------|--------|
| `:app` | Compose UI, user interactions, navigation, profile status | `app/src/main/` |
| `:core:model` | Immutable domain models, evidence types, risk records | `core/model/` |
| `:core:staticanalysis` | Manifest parsing, DEX header extraction, signing validation | `core/staticanalysis/` |
| `:core:sandbox` | Work Profile provisioning, installation, lifecycle management | `core/sandbox/` |
| `:core:network` | VpnService, packet forwarding, DNS parsing, HTTPS proxy | `core/network/` |
| `:core:risk` | Deterministic rule evaluation (`static-v1`, `runtime-v1`, `combined-v1`) | `core/risk/` |
| `:core:database` | Room persistence for sessions, evidence, and risk reports | `core/database/` |
| `:core:report` | Report synthesis, JSON/Markdown exports | `core/report/` |

## Pattern Overview

**Overall:** Layered clean architecture with unidirectional data flow (UDF) in UI and event-driven evidence ingestion.

**Key Characteristics:**
- **Boundary Isolation:** Code running inside the Work Profile does not have direct memory or database access to the Personal Profile.
- **Evidence Immutability:** Evidence records (`DeclaredCapability`, `ObservedBehavior`, `AndroidEvidence`) are write-once and preserved verbatim.
- **Deterministic Rules:** All scoring logic is transparent and stateless; identical evidence sets always yield identical scores.

## Data Flow

### Primary Analysis Lifecycle

1. **APK Selection:** User selects APK file via system picker (`AnalysisScreen.kt`).
2. **Static Extraction:** `StaticAnalyzer` inspects headers and manifest, populating `DeclaredCapability` records (`StaticAnalyzer.kt`).
3. **Session Provisioning:** `SessionCoordinator` sends intent to install and configure target inside Work Profile (`SessionCoordinator.kt`).
4. **Dynamic Execution:** Target APK runs inside Work Profile while `SandboxVpnService` captures wire traffic and `DevicePolicyManager` logs network events (`SandboxVpnService.kt`).
5. **Evidence Aggregation:** Raw observations and OS telemetry are saved into Room database (`core/database/`).
6. **Risk Evaluation:** `RiskEngine` computes deterministic scores across `static-v1`, `runtime-v1`, and `combined-v1` tiers (`RiskEngine.kt`).
7. **Report Presentation:** UI renders evidence cards and risk badges; user can export report as JSON/Markdown.

---

*Architecture analysis: 2026-09-10*
