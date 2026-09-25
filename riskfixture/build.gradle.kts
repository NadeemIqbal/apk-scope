plugins { id("com.android.application") }

/**
 * Synthetic fixture for manually exercising static-risk and private-storage inspection flows on a
 * real device, alongside the existing `fixture` module. It remains separate from `fixture` (which
 * other regression gates depend on), and declares sensitive permissions/components so structural
 * rules can be reviewed against one APK. Those declared capabilities remain inert; this fixture
 * only seeds harmless local sample files/preferences/database rows, adds user-requested local
 * runtime samples, and optionally makes the existing explicit HTTPS request. targetSdk stays below
 * `OLD_TARGET_SDK_THRESHOLD` (29) to exercise `STATIC_OLD_TARGET_SDK`.
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
