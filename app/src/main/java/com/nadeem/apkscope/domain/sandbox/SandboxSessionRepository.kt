package com.nadeem.apkscope.domain.sandbox

import android.content.Context
import com.nadeem.apkscope.core.database.SandboxDatabaseProvider
import com.nadeem.apkscope.core.database.SandboxSessionWithEnforcements
import com.nadeem.apkscope.core.model.SandboxSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Checkpoint 4, item 2: the durable, Room-backed store for [SandboxSession] — deliberately its own
 * repository, not folded into `SessionRepository` (which owns *static-analysis* history). Item 22:
 * one completed analysis may have zero or many [SandboxSession] rows; nothing here ever touches
 * `analysis_sessions` or the static-analysis read path.
 *
 * Unlike `SessionRepository`'s active/persisted split, every [SandboxSession] state — including
 * the earliest [com.nadeem.apkscope.core.model.SandboxSessionState.CREATED] — is written straight to
 * Room. A sandbox session's lifecycle is exactly the kind of state item 2 requires surviving
 * "activity recreation, navigation away/back, app process death"; there is no in-memory-only phase
 * to skip persisting the way an analysis's intermediate progress stages are.
 */
class SandboxSessionRepository(context: Context) {
 private val dao = SandboxDatabaseProvider.get(context).sandboxSessionDao()

 suspend fun save(session: SandboxSession) = withContext(Dispatchers.IO) {
  writeMutex.withLock { dao.upsertSession(session.toEntity(), session.enforcementEntities()) }
 }

 /** Apply reports/retries to the latest row; a suspended pull cannot overwrite a newer push. */
 suspend fun update(sessionId: String, transform: (SandboxSession) -> SandboxSession): SandboxSession? = withContext(Dispatchers.IO) {
  writeMutex.withLock {
  val current = get(sessionId) ?: return@withLock null
  val next = transform(current)
  if (next != current) dao.upsertSession(next.toEntity(), next.enforcementEntities())
  next
  }
 }

 companion object { private val writeMutex = Mutex() }

 suspend fun get(sessionId: String): SandboxSession? = dao.getSessionWithEnforcements(sessionId)?.toDomain()

 fun observe(sessionId: String): Flow<SandboxSession?> = dao.observeSessionWithEnforcements(sessionId).map { it?.toDomain() }

 /** Summary list only (no enforcement-result detail) — matches item 22's "one analysis may have zero or many sessions" without pulling every session's full enforcement history into a list view. */
 fun observeForAnalysis(analysisId: String): Flow<List<SandboxSession>> =
  dao.observeSessionsForAnalysis(analysisId).map { list -> list.map { SandboxSessionWithEnforcements(it, emptyList()).toDomain() } }

 /** Observe the current non-terminal active sandbox session if any exists. */
 fun observeActiveSession(): Flow<SandboxSession?> =
  dao.observeAllSessions().map { list ->
   val active = list.firstOrNull {
    it.state !in listOf(
     com.nadeem.apkscope.core.model.SandboxSessionState.COMPLETED.name,
     com.nadeem.apkscope.core.model.SandboxSessionState.FAILED.name,
     com.nadeem.apkscope.core.model.SandboxSessionState.CANCELLED.name,
    )
   }
   active?.let { dao.getSessionWithEnforcements(it.sessionId)?.toDomain() }
  }

 suspend fun delete(sessionId: String) = dao.deleteSession(sessionId)
}
