plugins { id("com.android.application") }

/**
 * Checkpoint 3, item 24 — a purely synthetic test fixture for manually exercising the static risk
 * engine on a real device, alongside the existing `fixture` module. Deliberately separate from
 * `fixture` (which other regression gates already depend on) rather than adding these permissions
 * to it. Declares a broad set of sensitive manifest permissions and several exported components so
 * most single-capability, structural, and combination rules can be verified against one real APK —
 * none of the declared capabilities are backed by real functionality; this app does nothing besides
 * present a static label, exactly like `fixture`'s own "harmless" design. targetSdk is deliberately
 * held below `OLD_TARGET_SDK_THRESHOLD` (29) specifically to also exercise `STATIC_OLD_TARGET_SDK`.
 */
android {
 namespace = "com.apksandbox.riskfixture"
 compileSdk = 37
 defaultConfig { applicationId = "com.apksandbox.riskfixture"; minSdk = 24; targetSdk = 28; versionCode = 1; versionName = "test-only" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 lint {
  // Every one of these is this module's deliberate reason to exist (item 24): a real signature
  // permission it will never be granted, sensitive permissions with no matching hardware feature
  // (this app never touches the hardware either), and an old targetSdk on purpose to exercise
  // STATIC_OLD_TARGET_SDK. Disabling here, not project-wide — `app`'s own lint config is untouched.
  disable += setOf("ProtectedPermissions", "PermissionImpliesUnsupportedChromeOsHardware", "ExpiredTargetSdkVersion")
 }
}
