plugins { id("com.android.application") }

// Milestone 10 (Security Audit), Phase 10.3, MS10-BUILD01 — a dedicated, minimal fixture app whose
// classes are deliberately named to look like real R8/ProGuard obfuscator output (1-2 character
// simple names, e.g. `a`, `b`, `Aa`), so BuildObfuscationAnalyzer has a real compiled positive fixture
// distinct from `riskfixture`/`fixture` (both already depended on by many other tests that require
// their existing descriptive class names to stay intact — minifying either of those modules would
// break that unrelated coverage, hence this separate module rather than enabling minification on an
// existing one). See `src/main/kotlin/com/apksandbox/obfuscatedfixture/` for the short-named classes
// themselves and their own doc comment for why hand-authored short names are an honest, real fixture
// for this analyzer even though they were not produced by an actual obfuscator tool run.
android {
 namespace = "com.apksandbox.obfuscatedfixture"
 compileSdk = 37
 defaultConfig { applicationId = "com.apksandbox.obfuscatedfixture"; minSdk = 30; targetSdk = 36; versionCode = 1; versionName = "test-only" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
