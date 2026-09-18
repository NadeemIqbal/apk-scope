package com.nadeem.apkscope.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe Navigation Compose routes (item 3). Every non-top-level screen takes a
 * `sessionId: String` only — never a large domain object (`ApkAnalysisResult`, `PolicyUiModel`
 * list, etc.) — the destination's ViewModel loads real state via the session id from a
 * repository, per item 18's ViewModel/state-holder boundary.
 */

// Top-level bottom-nav destinations.
@Serializable object HomeRoute
@Serializable object ReportsRoute
@Serializable object SettingsRoute
@Serializable object StorageRoute

// Nested APK-analysis flow — never a bottom-tab item (item 3).
@Serializable data class AnalysisRoute(val sessionId: String)
@Serializable data class StaticResultRoute(
 val sessionId: String,
 /** True when the detail screen was opened from the archived Reports list. */
 val returnToHistory: Boolean = false,
)
/** Checkpoint 3, item 16/18: both "View all findings" and "Why this score?" land here — one screen serves both asks, showing every real [com.nadeem.apkscope.core.model.RiskFinding] and how they sum to the score. */
@Serializable data class FindingsRoute(val sessionId: String)
@Serializable data class PermissionsDetailRoute(val sessionId: String)
@Serializable data class ComponentsDetailRoute(val sessionId: String)
@Serializable data class ManifestDetailRoute(val sessionId: String)
@Serializable data class AnalysisCoverageRoute(val sessionId: String)
@Serializable data class SandboxConfigRoute(val sessionId: String)
@Serializable data class SandboxPreparingRoute(val sessionId: String)
@Serializable data class SandboxReadyRoute(val sessionId: String)
@Serializable data class LiveMonitorRoute(val sessionId: String)
/** Checkpoint 4, item 13/24: the real stop/clear-data/uninstall/cleanup lifecycle — distinct from [ReportBuildingRoute]/[ReportRoute], which remain unwired stubs for the later final-report checkpoint (item 23 forbids starting that work here). */
@Serializable data class SandboxCleanupRoute(val sessionId: String)
/** Checkpoint 5, item 35: "View Runtime Activity" CTA on the Session Complete state — Personal's durable, already-imported runtime-observation history for a completed session. */
@Serializable data class RuntimeActivityRoute(val sessionId: String)
@Serializable data class ReportBuildingRoute(val sessionId: String)
@Serializable data class ReportRoute(val sessionId: String)
@Serializable object TrafficInspectorRoute
@Serializable data class EmbeddedUrlsRoute(val sessionId: String)
@Serializable data class DetectedSdksRoute(val sessionId: String)
@Serializable data class ApiReferencesRoute(val sessionId: String)
/** Milestone 10 (Security Audit), Phase 10.2. */
@Serializable data class SecurityAuditRoute(val sessionId: String)
@Serializable data class SecurityAuditFindingDetailRoute(val sessionId: String, val ruleId: String)

// APK patching and Frida-based traffic instrumentation.
@Serializable object PocApkRepackRoute
