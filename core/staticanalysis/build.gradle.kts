plugins { id("com.android.library") }

// Slice 2 (static APK analysis) foundation only — see ApkAnalyzer's doc comment for exactly what
// is and isn't implemented yet. The static risk engine (Slice 3) is a separate, later module.
android {
 namespace = "com.nadeem.apkscope.core.staticanalysis"
 compileSdk = 37
 defaultConfig { minSdk = 30; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
 implementation(project(":core:common"))
 implementation("com.android.tools.smali:smali-dexlib2:3.0.3")
 implementation("com.android.tools.smali:smali-baksmali:3.0.3")
 testImplementation("junit:junit:4.13.2")
 // Milestone 10, Phase 10.3: ApkAnalyzer.analyze() needs a real PackageManager, unavailable in a
 // plain JVM test (see ApkAnalyzerTest's own doc comment) — first androidTest deps this module has
 // needed, mirroring core:database's identical androidTest wiring.
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation("androidx.test:runner:1.6.2")
}
