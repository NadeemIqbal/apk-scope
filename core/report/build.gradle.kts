plugins { id("com.android.library") }

// The production network-observation writer (today: JSONL; core:database's skeleton is the
// planned home for a real queryable store — see that module's doc comment). Report *generation*
// (report.json, Slice 6) is not implemented yet — this checkpoint only promotes the existing,
// working observation sink.
android {
 namespace = "com.nadeem.apkscope.core.report"
 compileSdk = 37
 defaultConfig { minSdk = 30 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies {
 implementation(project(":core:model"))
}
