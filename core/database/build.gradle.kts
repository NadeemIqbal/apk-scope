plugins {
 id("com.android.library")
 id("com.google.devtools.ksp")
 id("androidx.room")
}

// Migrated from checkpoint 1's SQLiteOpenHelper skeleton to Room per the v0.1 UI checkpoint's
// frozen-architecture instruction — see this module's README note in V0.1_CHECKPOINT_2.md for
// why checkpoint 1 chose SQLiteOpenHelper and why Room now verifies cleanly with this project's
// Kotlin 2.4.10 toolchain (KSP 2.3.11, empirically confirmed to compile, not assumed).
android {
 namespace = "com.nadeem.apkscope.core.database"
 compileSdk = 37
 defaultConfig { minSdk = 30; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 // Exposes the Room-exported schema JSON (below) to androidTest as an asset, so
 // MigrationTestHelper can build a real historical-version database from it — required for
 // SandboxDatabaseMigrationTest to test a real 8 -> 9 upgrade rather than only a fresh install.
 sourceSets { getByName("androidTest") { assets.srcDirs(files("$projectDir/schemas")) } }
}

room {
 schemaDirectory("$projectDir/schemas")
}

dependencies {
 // `api`, not `implementation`: SandboxDatabase (this module's whole public surface) extends
 // RoomDatabase and every DAO method returns Flow — a consumer like `app` needs both types
 // resolvable on its own compile classpath, not just this module's.
 api("androidx.room:room-runtime:2.8.4")
 implementation("androidx.room:room-ktx:2.8.4")
 api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
 ksp("androidx.room:room-compiler:2.8.4")
 testImplementation("junit:junit:4.13.2")

 // Item 23: real Room persistence tests need a real SQLite/Android runtime, so these run as
 // instrumented tests (connectedAndroidTest) rather than plain JVM unit tests — no Robolectric
 // introduced, matching this project's "verify on real Android, don't fake the runtime" pattern.
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.room:room-testing:2.8.4")
 androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
