plugins { id("com.android.application") }
android {
 namespace = "com.apksandbox.fixture"
 compileSdk = 37
 defaultConfig { applicationId = "com.apksandbox.fixture"; minSdk = 30; targetSdk = 36; versionCode = 1; versionName = "test-only" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

