package com.nadeem.apkscope.domain

import android.content.Context
import com.nadeem.apkscope.core.database.SandboxDatabaseProvider
import com.nadeem.apkscope.core.model.StaticRiskAssessment
import com.nadeem.apkscope.core.staticanalysis.ApkAnalysisResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Checkpoint 3, item 2/3: the one repository every ViewModel goes through for session state,
 * cleanly split into two halves with different lifetimes/backing stores — never blurred together:
 *
 * - **Active/in-progress session state** ([observeActive]/[getActive]/[putActive]/[updateActive]):
 *   process-lifetime, in-memory only, held in a companion-object `MutableStateFlow` so every
 *   `SessionRepository(context)` instance (one per ViewModel, this module's usual style — see
 *   `EnvironmentRepository`/`PolicyRepository`) shares the same state. Only `AnalysisScreen`'s
 *   progress UI and `HomeViewModel`'s `isImporting` flag read/write this half. An analysis in
 *   progress does not get every stage transition written to Room (item 2's explicit allowance).
 * - **Persisted completed analysis** ([persistCompletedAnalysis]/[observeHistory]/
 *   [observePersisted]/[getPersisted]): durable, Room-backed via `core:database`'s
 *   `AnalysisSessionDao`. This is the *only* read path every other screen (Static Result,
 *   Permissions/Components detail, Configure Sandbox, Home's Recent Analysis) uses once an
 *   analysis is complete — identical whether the session was just analyzed in this process or
 *   reopened after a process restart (item 3's required process-death test).
 */
class SessionRepository(private val context: Context) {
 private val dao = SandboxDatabaseProvider.get(context).analysisSessionDao()

 companion object {
  private val activeSessions = MutableStateFlow<Map<String, AnalysisSession>>(emptyMap())
 }

 // --- Active / in-progress (in-memory) ---

 fun observeActive(id: String): Flow<AnalysisSession?> = activeSessions.map { it[id] }
 fun getActive(id: String): AnalysisSession? = activeSessions.value[id]

 fun putActive(session: AnalysisSession) {
  activeSessions.value = activeSessions.value + (session.id to session)
 }

 fun updateActive(id: String, transform: (AnalysisSession) -> AnalysisSession) {
  val current = activeSessions.value[id] ?: return
  activeSessions.value = activeSessions.value + (id to transform(current))
 }

 /** Once an analysis completes, its in-memory entry has served its purpose (the durable Room row is now the source of truth) — dropped rather than left to accumulate for the rest of the process's life. */
 fun clearActive(id: String) {
  activeSessions.value = activeSessions.value - id
 }

 // --- Persisted completed analysis (Room-backed) ---

 suspend fun persistCompletedAnalysis(sessionId: String, appName: String?, analyzedAtEpochMs: Long, result: ApkAnalysisResult, risk: StaticRiskAssessment) {
  StaticAnalysisResultStore.put(context, sessionId, result.metadata)
  val rows = buildPersistedRows(sessionId, appName, analyzedAtEpochMs, result, risk)
  dao.insertCompleteAnalysis(rows.session, rows.permissions, rows.components, rows.findings)
  try {
   com.nadeem.apkscope.domain.report.ReportCoordinator(context).generateStaticOnlyReport(sessionId)
  } catch (_: Exception) {}
 }

 fun observeHistory(): Flow<List<PersistedAnalysisSummary>> = dao.observeAllSummaries().map { list -> list.map { it.toSummary() } }

 fun observePersisted(sessionId: String): Flow<PersistedAnalysis?> = dao.observeDetails(sessionId).map { it?.toDomain(context) }

 suspend fun getPersisted(sessionId: String): PersistedAnalysis? = dao.getDetails(sessionId)?.toDomain(context)
}
