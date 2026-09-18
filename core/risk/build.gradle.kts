plugins { kotlin("jvm") }

kotlin {
    jvmToolchain(17)
}

// The static risk engine (checkpoint 3, item 5): deterministic and unit-testable per the
// checkpoint's explicit requirement, which a plain Kotlin/JVM module gives for free — no Android
// framework, no emulator/Robolectric needed to run its tests. Depends only on core:model (also
// pure Kotlin/JVM) for ApkAnalysisInput/StaticRiskAssessment/RiskFinding/RiskLevel.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

dependencies {
 implementation(project(":core:model"))
 testImplementation("junit:junit:4.13.2")
}
