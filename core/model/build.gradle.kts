plugins { kotlin("jvm") }

kotlin {
    jvmToolchain(17)
}

// Pure domain model: no Android framework dependency, so this stays a plain Kotlin/JVM module —
// every other core/* module and both apps can depend on it without pulling in an Android SDK.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

dependencies { testImplementation("junit:junit:4.13.2") }
