package com.nadeem.apkscope.core.staticanalysis

/**
 * Slice 2 (static APK analysis) foundation — see [ApkAnalyzer]'s doc comment for what is and
 * isn't implemented yet in this checkpoint. Fields this checkpoint cannot yet populate reliably
 * from public APIs are explicitly nullable/empty with a comment, rather than guessed at.
 */
data class ApkMetadata(
 val sha256: String,
 val packageName: String,
 val versionName: String?,
 val versionCode: Long,
 val minSdkVersion: Int,
 val targetSdkVersion: Int,
 val requestedPermissions: List<String>,
 val components: List<ComponentDescriptor>,
 val debuggable: Boolean,
 val nativeLibraryAbis: List<String>,
 /** Milestone 10 (Security Audit), Phase 10.3: implemented by parsing the manifest's binary XML directly (`BinaryXmlParser.ManifestConfig.networkSecurityConfig`, read off the `<application android:networkSecurityConfig="...">` attribute) — `ApplicationInfo` itself exposes no such field for an *archived*, not-yet-installed APK, confirmed via `javap` against this project's compileSdk when this field was first added. `null` only when the manifest itself could not be located or parsed at all (a genuine "unknown"); `true`/`false` otherwise — never conflates "unknown" with "confirmed absent". This is presence of the manifest attribute only — it says nothing about the referenced config's actual contents (e.g. whether it still permits cleartext for some domains) or whether the attribute's target resource even exists; that is deliberately out of scope for this field. */
 val networkSecurityConfigPresent: Boolean?,
 /**
  * Milestone 10 (Security Audit), Phase 10.3 correction: the real *content* of the referenced
  * `network_security_config.xml` resource (via [ResourceTableParser] + [NetworkSecurityConfigParser]),
  * not just the manifest attribute's presence [networkSecurityConfigPresent] alone establishes. `null`
  * when [networkSecurityConfigPresent] is not `true`, or when resolution/parsing could not complete —
  * [NetworkSecurityConfigSummary.unavailableReason] carries why in that case, so a caller can
  * distinguish "no network security config" from "one exists but could not be inspected" (Section 3's
  * explicit "do not claim absent pinning when the configuration could not be inspected").
  */
 val networkSecurityConfig: NetworkSecurityConfigSummary? = null,
 /** `ApplicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC` — directly available and implemented, unlike [networkSecurityConfigPresent]. Not independently verified whether `getPackageArchiveInfo` (an *uninstalled* archive, not the installed package this flag is normally read from) resolves target-SDK-version-dependent defaults the same way installed-package parsing does — flag as a follow-up to confirm against a real device before this value is trusted in a risk-engine rule. */
 val usesCleartextTraffic: Boolean,
 /** `ApplicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP` — same "directly available, not yet device-verified against an installed package's flag resolution" caveat as [usesCleartextTraffic] above. Defaults to `true` (the real Android platform default when a manifest omits `android:allowBackup`) only for call sites that predate this field (persistence deserialization of older records, existing test fixtures) — [ApkAnalyzer] always passes the actually-read flag explicitly, never relies on this default. Added for Security Audit's static rule catalog (Milestone 10). */
 val allowBackup: Boolean = true,
 /** Implemented via [BinaryXmlParser]'s per-component `<intent-filter>` extraction from the manifest's raw binary XML (`getPackageArchiveInfo` alone does not surface this) — this doc previously said "not implemented, always empty" from before that parser existed; that claim was stale, not current behavior. This is the flattened union across all components ([ApkAnalyzer]'s `allFilters`); each [ComponentDescriptor] also carries its own filters individually. */
 val intentFilters: List<IntentFilterDescriptor>,
 val platformInfo: AppPlatformInfo = AppPlatformInfo(AppPlatform.UNKNOWN),
 val embeddedUrls: List<DexUrlCandidate> = emptyList(),
 val detectedSdks: List<SdkFinding> = emptyList(),
 val apiFindings: List<ApiFinding> = emptyList(),
 val staticCoverage: StaticAnalysisCoverage = StaticAnalysisCoverage(),
)

data class ComponentDescriptor(
	val name: String,
	val type: ComponentType,
	val exported: Boolean,
	val permission: String? = null,
	val intentFilters: List<IntentFilterDescriptor> = emptyList(),
) {
	enum class ComponentType { ACTIVITY, SERVICE, RECEIVER, PROVIDER }
}

/** Placeholder shape for when intent-filter extraction is implemented (see [ApkMetadata.intentFilters]'s doc). */
data class IntentFilterDescriptor(val componentName: String, val actions: List<String>, val categories: List<String>, val dataSchemes: List<String>)

/**
 * Signature verification is deliberately its own type/parse step, never merged into
 * [ApkMetadata] — so a corrupt or invalid signature never discards the metadata that *was*
 * successfully parsed. [Failed] is the explicit "APK signature verification failed" finding
 * required by the v0.1 promotion, not a generic parse error.
 */
sealed interface SigningResult {
 data class Verified(val signerCertificateSha256: List<String>) : SigningResult
 data class Failed(val reason: String) : SigningResult
}

data class ApkAnalysisResult(val metadata: ApkMetadata, val signing: SigningResult)

/**
 * Milestone 10 (Security Audit), Phase 10.3 correction — a flat summary of
 * [NetworkSecurityConfigParser.NetworkSecurityConfigAnalysis], deliberately not the full structured
 * type itself: [ApkMetadata] and its persisted/audit-input counterparts (`PersistedAnalysis`,
 * `ApkAnalysisInput`) are plain, comparable data used in full-entity-equality tests elsewhere in this
 * codebase, and a real `<domain-config>` list is unbounded-length, structured data the current audit
 * catalog does not yet need in full. Every field here is a real, directly-observed fact — never a
 * guess about what could not be inspected (see [unavailableReason]).
 */
// Implements Serializable so this can travel through StaticAnalysisResultStore's existing
// PersistedStaticData disk-cache mechanism, the same way ApiFinding/SdkFinding already do — no new
// persistence mechanism/migration needed for this rich, unbounded-shape data.
data class NetworkSecurityConfigSummary(
 /** The effective `cleartextTrafficPermitted` from `<base-config>` if declared; `null` if the base-config does not declare it explicitly (meaning the real, target-SDK-dependent platform default applies — this summary does not re-derive that default). */
 val baseConfigCleartextTrafficPermitted: Boolean?,
 /** `true` if any `<domain-config>` block declares a `<pin-set>` — a real "certificate pinning is configured somewhere" signal, distinct from [ApkMetadata.networkSecurityConfigPresent] (which is true even for a config with no pinning at all, e.g. this fixture's own base-config-only file). */
 val hasAnyPinSet: Boolean,
 /** `true` if `<debug-overrides>` trusts `user`-installed certificates — inert in a release build (the platform ignores `<debug-overrides>` entirely there) but a real, worth-surfacing fact about what a *debug* build of this APK would trust. */
 val debugOverridesTrustsUserCerts: Boolean,
 /** How many distinct `<domain-config>` blocks exist, each scoping its own settings to specific domains — `0` means every setting comes from `<base-config>` alone. */
 val domainConfigCount: Int,
 /** Non-null when this config could not be fully inspected — a real resource-resolution/parse limitation named explicitly (see [ResourceTableParser.ResolveResult.Unsupported]/[NetworkSecurityConfigParser.ParseResult.Unavailable]), never silently treated as "no pinning"/"cleartext permitted false". A caller must not read the other fields above as confirmed when this is non-null; they reflect nothing, not an "unset" default. */
 val unavailableReason: String?,
 /** Any real, valid NSC construct this parser's documented subset does not interpret (e.g. nested `<domain-config>`, `<certificateTransparency>`) — distinct from [unavailableReason] (a total resolution/parse failure): the config *was* read, just not completely. */
 val unsupportedNotes: List<String> = emptyList(),
) : java.io.Serializable {
 companion object {
  fun from(result: NetworkSecurityConfigParser.ParseResult): NetworkSecurityConfigSummary = when (result) {
   is NetworkSecurityConfigParser.ParseResult.Unavailable -> NetworkSecurityConfigSummary(
    baseConfigCleartextTrafficPermitted = null,
    hasAnyPinSet = false,
    debugOverridesTrustsUserCerts = false,
    domainConfigCount = 0,
    unavailableReason = result.reason,
   )
   is NetworkSecurityConfigParser.ParseResult.Parsed -> {
    val a = result.analysis
    NetworkSecurityConfigSummary(
     baseConfigCleartextTrafficPermitted = a.baseConfig?.cleartextTrafficPermitted,
     hasAnyPinSet = a.domainConfigs.any { it.pinSet != null } || (a.baseConfig?.pinSet != null),
     debugOverridesTrustsUserCerts = a.debugOverrides?.trustAnchorSources?.contains("user") == true,
     domainConfigCount = a.domainConfigs.size,
     unavailableReason = null,
     unsupportedNotes = a.unsupportedNotes,
    )
   }
  }
 }
}
