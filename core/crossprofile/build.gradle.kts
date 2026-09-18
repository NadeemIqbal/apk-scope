plugins { id("com.android.library") }

// Handoff/ImportActivity/CrossProfileContract — the sandbox's actual temporary-content-URI
// cross-profile transport, validated end-to-end (both directions) on the physical Pixel 8 gate.
android {
 namespace = "com.nadeem.apkscope.core.crossprofile"
 compileSdk = 37
 defaultConfig { minSdk = 30 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
 implementation(project(":core:common"))
 implementation("androidx.core:core:1.19.0")
}
