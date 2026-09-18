package com.nadeem.apkscope.domain

import com.nadeem.apkscope.core.model.ApkAnalysisInput
import com.nadeem.apkscope.core.staticanalysis.ApkAnalysisResult
import com.nadeem.apkscope.core.staticanalysis.NetworkSecurityConfigSummary
import com.nadeem.apkscope.core.staticanalysis.SigningResult

/**
 * Milestone 10 (Security Audit), Phase 10.3 — flattens the rich, `core:staticanalysis`-only
 * [NetworkSecurityConfigSummary] into the plain fields [ApkAnalysisInput] can carry across the
 * `core:model`/`core:staticanalysis` module boundary (see [ApkAnalysisInput]'s own doc for why it
 * cannot reference that type directly). Both [ApkAnalysisResult.toRiskInput] and
 * [PersistedAnalysis.toRiskInput] share this so the flattening rule is defined exactly once.
 */
private fun networkSecurityConfigFields(
 present: Boolean?,
 summary: NetworkSecurityConfigSummary?,
): NetworkSecurityConfigFields = NetworkSecurityConfigFields(
 present = present,
 cleartextPermitted = summary?.baseConfigCleartextTrafficPermitted,
 hasPinSet = summary?.hasAnyPinSet ?: false,
 debugOverridesTrustsUserCerts = summary?.debugOverridesTrustsUserCerts ?: false,
 unavailableReason = summary?.unavailableReason,
)

private data class NetworkSecurityConfigFields(
 val present: Boolean?,
 val cleartextPermitted: Boolean?,
 val hasPinSet: Boolean,
 val debugOverridesTrustsUserCerts: Boolean,
 val unavailableReason: String?,
)

/** The one place a real, Android-dependent [ApkAnalysisResult] is narrowed down to `core:risk`'s pure-fact [ApkAnalysisInput] (item 5's module-boundary requirement — nothing in `core:risk` ever sees this Android-dependent type directly). */
fun ApkAnalysisResult.toRiskInput(): ApkAnalysisInput {
 val metadata = this.metadata
 val nsc = networkSecurityConfigFields(metadata.networkSecurityConfigPresent, metadata.networkSecurityConfig)
 return ApkAnalysisInput(
  packageName = metadata.packageName,
  versionName = metadata.versionName,
  versionCode = metadata.versionCode,
  minSdkVersion = metadata.minSdkVersion,
  targetSdkVersion = metadata.targetSdkVersion,
  requestedPermissions = metadata.requestedPermissions,
  debuggable = metadata.debuggable,
  nativeLibraryAbis = metadata.nativeLibraryAbis,
  totalComponentCount = metadata.components.size,
  exportedComponentCount = metadata.components.count { it.exported },
  signatureVerified = signing is SigningResult.Verified,
  usesCleartextTraffic = metadata.usesCleartextTraffic,
  allowBackup = metadata.allowBackup,
  staticSecurityFieldsKnown = true, // a fresh ApkAnalysisResult always has real, just-read values
  networkSecurityConfigPresent = nsc.present,
  networkSecurityConfigCleartextPermitted = nsc.cleartextPermitted,
  networkSecurityConfigHasPinSet = nsc.hasPinSet,
  networkSecurityConfigDebugOverridesTrustsUserCerts = nsc.debugOverridesTrustsUserCerts,
  networkSecurityConfigUnavailableReason = nsc.unavailableReason,
 )
}

/**
 * The same narrowing as [ApkAnalysisResult.toRiskInput], from the durable, Room-backed
 * [PersistedAnalysis] instead of a fresh, not-yet-persisted [ApkAnalysisResult] — needed so Security
 * Audit (Milestone 10) can (re-)run against an analysis reopened after a process restart, not only
 * at the moment of initial import. Both mapping functions must stay in sync field-for-field; this one
 * exists specifically because [PersistedAnalysis] and [ApkAnalysisResult] are two different Android
 * types over the same underlying facts, not because the facts themselves differ.
 */
fun PersistedAnalysis.toRiskInput(): ApkAnalysisInput {
 val nsc = networkSecurityConfigFields(networkSecurityConfigPresent, networkSecurityConfig)
 return ApkAnalysisInput(
  packageName = packageName,
  versionName = versionName,
  versionCode = versionCode,
  minSdkVersion = minSdkVersion,
  targetSdkVersion = targetSdkVersion,
  requestedPermissions = permissions,
  debuggable = debuggable,
  nativeLibraryAbis = nativeLibraryAbis,
  totalComponentCount = components.size,
  exportedComponentCount = components.count { it.exported },
  signatureVerified = signing is SigningResult.Verified,
  usesCleartextTraffic = usesCleartextTraffic,
  allowBackup = allowBackup,
  // The real reason this field exists: a reopened/persisted analysis may predate Security Audit
  // entirely, in which case this is `false` and StaticAuditRules must not trust the two flags above.
  staticSecurityFieldsKnown = staticSecurityFieldsKnown,
  networkSecurityConfigPresent = nsc.present,
  networkSecurityConfigCleartextPermitted = nsc.cleartextPermitted,
  networkSecurityConfigHasPinSet = nsc.hasPinSet,
  networkSecurityConfigDebugOverridesTrustsUserCerts = nsc.debugOverridesTrustsUserCerts,
  networkSecurityConfigUnavailableReason = nsc.unavailableReason,
 )
}
