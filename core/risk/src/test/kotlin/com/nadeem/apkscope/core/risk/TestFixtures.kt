package com.nadeem.apkscope.core.risk

import com.nadeem.apkscope.core.model.ApkAnalysisInput

/** Shared minimal-valid-input builder for every test in this module — override only what a given test actually needs, so each test's intent (which fact triggers/doesn't trigger a rule) stays visible instead of buried in boilerplate. */
internal fun testInput(
 permissions: List<String> = emptyList(),
 debuggable: Boolean = false,
 targetSdkVersion: Int = 34,
 nativeLibraryAbis: List<String> = emptyList(),
 totalComponentCount: Int = 1,
 exportedComponentCount: Int = 0,
 signatureVerified: Boolean = true,
 usesCleartextTraffic: Boolean = false,
 allowBackup: Boolean = true,
 staticSecurityFieldsKnown: Boolean = true,
 networkSecurityConfigPresent: Boolean? = null,
 networkSecurityConfigCleartextPermitted: Boolean? = null,
 networkSecurityConfigHasPinSet: Boolean = false,
 networkSecurityConfigDebugOverridesTrustsUserCerts: Boolean = false,
 networkSecurityConfigUnavailableReason: String? = null,
) = ApkAnalysisInput(
 packageName = "com.example.test",
 versionName = "1.0",
 versionCode = 1,
 minSdkVersion = 24,
 targetSdkVersion = targetSdkVersion,
 requestedPermissions = permissions,
 debuggable = debuggable,
 nativeLibraryAbis = nativeLibraryAbis,
 totalComponentCount = totalComponentCount,
 exportedComponentCount = exportedComponentCount,
 signatureVerified = signatureVerified,
 usesCleartextTraffic = usesCleartextTraffic,
 allowBackup = allowBackup,
 staticSecurityFieldsKnown = staticSecurityFieldsKnown,
 networkSecurityConfigPresent = networkSecurityConfigPresent,
 networkSecurityConfigCleartextPermitted = networkSecurityConfigCleartextPermitted,
 networkSecurityConfigHasPinSet = networkSecurityConfigHasPinSet,
 networkSecurityConfigDebugOverridesTrustsUserCerts = networkSecurityConfigDebugOverridesTrustsUserCerts,
 networkSecurityConfigUnavailableReason = networkSecurityConfigUnavailableReason,
)
