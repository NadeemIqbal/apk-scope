package com.nadeem.apkscope.sandbox

import android.content.Context
import com.nadeem.apkscope.core.database.WorkEvidenceDatabaseProvider
import com.nadeem.apkscope.core.database.WorkSessionEvidenceEntity
import org.json.JSONObject

/**
 * Checkpoint 4.1 §5/§8: the Work-profile-local durable evidence store — every fact
 * [SandboxWorkerService]/[SandboxInstallResultReceiver] discovers is written here **before** the
 * best-effort cross-profile push is attempted, so the fact survives regardless of whether that push
 * (a background-initiated `Handoff.send()`, subject to Android's background-activity-launch
 * restrictions — see `V0.1_CHECKPOINT_4_1.md`) actually reaches the personal side. This is Work-only
 * storage, backed by [com.nadeem.apkscope.core.database.WorkEvidenceDatabase] — a physically separate
 * Room database file from the personal profile's own `SandboxDatabase`, never shared, never opened
 * from personal-side code.
 *
 * Reuses [SandboxStatusReport] as its wire/storage shape rather than inventing a second one — the
 * cumulative merged fact-set for a session is exactly the same shape a push report already is, just
 * durably persisted instead of transient, and read back on demand by
 * `SandboxWorkQueryActivity`'s `EXPORT_EVIDENCE` query.
 */
class WorkEvidenceStore(context: Context) {
 private val dao = WorkEvidenceDatabaseProvider.get(context).workEvidenceDao()

 /** Merges [patch] onto whatever cumulative evidence already exists for [sessionId] (see [mergeReports]) and persists the result. [packageName] may be empty when not yet known (e.g. a failure before the APK archive was parsed) — the import side treats an empty stored packageName as "not yet known" rather than a hard mismatch. */
 suspend fun recordFact(sessionId: String, packageName: String, patch: SandboxStatusReport) {
  val existing = getReport(sessionId)
  val merged = mergeReports(existing, patch)
  val effectivePackageName = packageName.ifEmpty { getPackageName(sessionId).orEmpty() }
  dao.upsert(WorkSessionEvidenceEntity(sessionId, effectivePackageName, merged.toJson().toString(), System.currentTimeMillis()))
  com.nadeem.apkscope.core.crossprofile.HandoffDiagnostics.log("work_evidence_recorded session=$sessionId package=$effectivePackageName state=${merged.state}")
 }

 suspend fun getReport(sessionId: String): SandboxStatusReport? =
  dao.get(sessionId)?.let { runCatching { SandboxStatusReport.fromJson(JSONObject(it.latestReportJson)) }.getOrNull() }

 suspend fun getPackageName(sessionId: String): String? = dao.get(sessionId)?.packageName

 suspend fun clear(sessionId: String) = dao.delete(sessionId)

 companion object {
  /**
   * Combines an older cumulative report with a newer [patch]: every non-null/non-empty field in
   * [patch] wins; anything [patch] leaves unset falls back to [existing]. Enforcement results are
   * merged by policy so a partial later report cannot erase an earlier verified policy. `state`
   * always takes the patch's value — it is always the most recently known lifecycle stage, never
   * merged field-by-field.
   */
  fun mergeReports(existing: SandboxStatusReport?, patch: SandboxStatusReport): SandboxStatusReport {
   if (existing == null) return patch
   return SandboxStatusReport(
    sessionId = patch.sessionId,
    state = patch.state,
    installSessionId = patch.installSessionId ?: existing.installSessionId,
    installedVersionCode = patch.installedVersionCode ?: existing.installedVersionCode,
    enforcements = mergeEnforcements(existing.enforcements, patch.enforcements),
    error = patch.error ?: existing.error,
    dataClearRequestedAtEpochMs = patch.dataClearRequestedAtEpochMs ?: existing.dataClearRequestedAtEpochMs,
    dataClearCompletedAtEpochMs = patch.dataClearCompletedAtEpochMs ?: existing.dataClearCompletedAtEpochMs,
    dataClearResult = patch.dataClearResult ?: existing.dataClearResult,
    cleanup = patch.cleanup ?: existing.cleanup,
   )
  }

  private fun mergeEnforcements(
   existing: List<com.nadeem.apkscope.core.model.PolicyEnforcementResult>,
   patch: List<com.nadeem.apkscope.core.model.PolicyEnforcementResult>,
  ): List<com.nadeem.apkscope.core.model.PolicyEnforcementResult> {
   if (patch.isEmpty()) return existing
   val merged = linkedMapOf<com.nadeem.apkscope.core.model.SandboxPolicyType, com.nadeem.apkscope.core.model.PolicyEnforcementResult>()
   existing.forEach { merged[it.policy] = it }
   patch.forEach { merged[it.policy] = it }
   return merged.values.toList()
  }
 }
}
