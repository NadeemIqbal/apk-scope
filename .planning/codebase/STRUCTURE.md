# Codebase Structure

**Analysis Date:** 2026-09-10

## Directory Layout

```
apk-scope/
├── docs/                      # Technical documentation, specifications, walkthroughs, screenshots
│   ├── media/                 # Screenshots and video demos
│   └── HTTPS_INSPECTION_POC.md # Design documentation for HTTPS inspection POC
├──                # Root Android Gradle project
│   ├── app/                   # Main application module (Personal Profile UI & coordination)
│   │   └── src/main/java/com/nadeem/apkscope/
│   │       ├── sandbox/       # Profile management, VPN service, CA installer
│   │       └── ui/            # Jetpack Compose UI (screens, components, theme, navigation)
│   ├── core/                  # Core feature and infrastructure libraries
│   │   ├── model/             # Shared data models and evidence types
│   │   ├── common/            # Utility classes, dispatchers, extensions
│   │   ├── network/           # VPN packet handling, TCP proxy, HTTPS capture engine
│   │   ├── crossprofile/      # Cross-profile intents and communication
│   │   ├── staticanalysis/    # Manifest parser, DEX analyzer, cert validator
│   │   ├── sandbox/           # Work profile lifecycle & DPM integration
│   │   ├── risk/              # Deterministic risk engine & rule definitions
│   │   ├── database/          # Room DB entities, DAOs, type converters
│   │   └── report/            # Report synthesis, Markdown/JSON exporters
│   ├── fixture/               # Standard test app for verifying analysis pipeline
│   ├── riskfixture/           # High-risk behavioral fixture app for validating risk rules
│   ├── tools/                 # Development and test helper scripts
│   ├── build.gradle.kts       # Root build configuration
│   └── settings.gradle.kts    # Module settings
├── CONTRIBUTING.md            # Contribution guidelines
├── LICENSE                    # Project license
├── README.md                  # Project overview, architecture, and quickstart
└── SECURITY.md                # Vulnerability reporting policy
```

## Where to Add New Code

**New UI Screen or Navigation Route:**
- Compose UI: `app/src/main/java/com/nadeem/apkscope/ui/screens/<feature>/`
- Navigation wiring: `app/src/main/java/com/nadeem/apkscope/ui/navigation/`

**New Risk Evaluation Rule:**
- Implementation: `core/risk/src/main/kotlin/com/nadeem/apkscope/core/risk/rules/`
- Unit tests: `core/risk/src/test/kotlin/com/nadeem/apkscope/core/risk/`

**New Network Capture Feature:**
- Wire interception: `core/network/src/main/kotlin/com/nadeem/apkscope/core/network/`
- Unit tests: `core/network/src/test/kotlin/com/nadeem/apkscope/core/network/`

**Database Schema Changes:**
- Entities: `core/database/src/main/kotlin/com/nadeem/apkscope/core/database/entities/`
- DAOs: `core/database/src/main/kotlin/com/nadeem/apkscope/core/database/dao/`
- Room Migrations: `core/database/src/main/kotlin/com/nadeem/apkscope/core/database/migrations/`

---

*Structure analysis: 2026-09-10*
