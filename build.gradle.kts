buildscript {
    repositories { google(); mavenCentral() }
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10") }
}
plugins {
    id("com.android.application") version "9.4.0" apply false
    id("com.android.library") version "9.4.0" apply false
    kotlin("jvm") version "2.4.10" apply false
    kotlin("android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
    id("androidx.room") version "2.8.4" apply false
}

// Give every Android module a lint baseline. The codebase carries a set of known,
// pre-existing lint findings (a documented CrossProfileApps MissingPermission that
// the code guards with try/catch, intentionally malformed pin digests in a
// parser-test fixture, etc.). A baseline freezes those so `lintDebug` stays green
// in CI while still failing on any NEW issue. Regenerate with `./gradlew updateLintBaseline`.
subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension>("android") {
            lint { baseline = file("lint-baseline.xml") }
        }
    }
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
            lint { baseline = file("lint-baseline.xml") }
        }
    }
}
