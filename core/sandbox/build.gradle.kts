plugins { id("com.android.library") }

// Sandbox-session orchestration boundary. This checkpoint promotes only PolicyEnforcer — the
// piece item 2 of the v0.1 promotion explicitly demanded (never report a restriction as active
// unless Android confirmed it). SandboxSession orchestration itself (Slice 4) is not implemented
// yet.
android {
 namespace = "com.nadeem.apkscope.core.sandbox"
 compileSdk = 37
 defaultConfig { minSdk = 30 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
 implementation(project(":core:model"))
 testImplementation("junit:junit:4.13.2")
}
