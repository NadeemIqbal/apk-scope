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
