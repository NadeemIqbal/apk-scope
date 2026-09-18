package com.nadeem.apkscope.core.model

/**
 * The static risk engine's entire input surface (item 5) — a plain, pure-Kotlin snapshot of
 * declared/structural facts about one APK, deliberately independent of `core:staticanalysis`'s
 * Android-dependent [not directly referenced here] `ApkAnalysisResult`/`ApkMetadata` types. Kept in
 * `core:model` (no Android framework dependency) rather than `core:staticanalysis` (an Android
 * library module) specifically so `core:risk` can stay a plain Kotlin/JVM module too — its rules
 * run and unit-test as ordinary JVM code, no emulator/Robolectric required.
 *
 * The app layer (`ApkImportUseCase`) maps a real `ApkAnalysisResult` into one of these; nothing
 * in `core:risk` ever sees the Android-dependent type directly. Every field here must trace back
 * to a real, reliably-determined static/declared fact — never a runtime observation (item 8).
 *
 * [totalComponentCount]/[exportedComponentCount] are pre-aggregated counts, not the full component
 * list — the exported-components rule (item 13) only needs counts today; carrying the full
 * `ComponentDescriptor` list here would pull an Android-library type into a pure-JVM module for no
 * present benefit.
 */
data class ApkAnalysisInput(
 val packageName: String,
 val versionName: String?,
 val versionCode: Long,
 val minSdkVersion: Int,
 val targetSdkVersion: Int,
 val requestedPermissions: List<String>,
 val debuggable: Boolean,
 val nativeLibraryAbis: List<String>,
 val totalComponentCount: Int,
 val exportedComponentCount: Int,
 val signatureVerified: Boolean,
 /** `ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC` — see [com.nadeem.apkscope.core.staticanalysis.ApkMetadata.usesCleartextTraffic]'s doc for the same not-yet-device-verified caveat this value inherits. Added for Security Audit's `AUDIT_CLEARTEXT_TRAFFIC_ENABLED` static rule (Milestone 10, Phase 10.1 follow-up). */
 val usesCleartextTraffic: Boolean = false,
 /** `ApplicationInfo.FLAG_ALLOW_BACKUP` — defaults to `true` on the Android platform itself when the manifest omits `android:allowBackup`, but this field carries only the flag actually read off the analyzed APK's `ApplicationInfo`, never a re-derived platform default. Added for Security Audit's `AUDIT_BACKUP_ENABLED` static rule (Milestone 10, Phase 10.1 follow-up). */
 val allowBackup: Boolean = true,
 /**
  * Milestone 10 (Security Audit), Phase 10.3 correction: `true` for every fresh analysis this app
  * ever produces (a brand-new `ApkAnalysisResult` always has real, just-read values for
  * [usesCleartextTraffic]/[allowBackup]) — `false` only when this input was built from a persisted
  * `AnalysisSessionEntity` row whose own `staticSecurityFieldsKnown` is `false` (a row that predates
  * those two columns, backfilled by `MIGRATION_9_10` with defaults it cannot verify). When `false`,
  * `StaticAuditRules.CLEARTEXT_TRAFFIC_ENABLED`/`BACKUP_ENABLED` must report
  * [com.nadeem.apkscope.core.risk.audit.AuditOutcome.NOT_TESTED] rather than trusting
  * [usesCleartextTraffic]/[allowBackup]'s possibly-defaulted values.
  */
 val staticSecurityFieldsKnown: Boolean = true,
 /**
  * Milestone 10 (Security Audit), Phase 10.3, MS10-NET01 — flattened from
  * `core:staticanalysis.NetworkSecurityConfigSummary` (a rich, `core:staticanalysis`-only type this
  * pure-Kotlin module cannot reference — see this file's own module-boundary doc above). `null`
  * means no `android:networkSecurityConfig` attribute was declared at all — genuinely different from
  * [networkSecurityConfigUnavailableReason] being non-null (attribute present, content could not be
  * inspected).
  */
 val networkSecurityConfigPresent: Boolean? = null,
 /** The real `<base-config cleartextTrafficPermitted="...">` value, when the config was successfully parsed; `null` if not declared explicitly or the config is absent/unavailable. */
 val networkSecurityConfigCleartextPermitted: Boolean? = null,
 /** `true` only when a real `<pin-set>` was found somewhere in the parsed config — never inferred from absence of anything. */
 val networkSecurityConfigHasPinSet: Boolean = false,
 /** `true` only when `<debug-overrides>` was found to trust `user`-installed CAs — inert in a release build, but a real fact about what a debug build of this APK trusts. */
 val networkSecurityConfigDebugOverridesTrustsUserCerts: Boolean = false,
 /** Non-null only when [networkSecurityConfigPresent] is `true` but the config's actual content could not be resolved/parsed — see `NetworkSecurityConfigSummary.unavailableReason`. A rule must report `NOT_TESTED`, never guess, when this is set (Section 3's "do not claim absent pinning when the configuration could not be inspected"). */
 val networkSecurityConfigUnavailableReason: String? = null,
)
