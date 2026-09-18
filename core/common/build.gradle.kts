plugins { kotlin("jvm") }

kotlin {
    jvmToolchain(17)
}

// Small generic utilities with no Android framework dependency and no dependency on any other
// core/* module — kept separate from core/model so a module that only needs e.g. BoundedCopy
// doesn't have to pull in the domain model too.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

dependencies { testImplementation("junit:junit:4.13.2") }
