package com.nadeem.apkscope.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Checkpoint 5, item 26: the **personal**-side durable copy of one sandbox session's real network
 * activity, imported from the Work-local runtime-observation artifact (item 22). Replaces
 * checkpoint-1's never-populated `NetworkObservationEntity` skeleton (one opaque JSON-blob column
 * per row) with typed columns mirroring `core:database`'s Work-local
 * `WorkNetworkObservationEntity` — item 5's "use typed domain models" applies identically on the
 * personal side, and the two schemas are kept structurally parallel on purpose so the same
 * aggregation/mapping logic (`app`'s `LiveMonitorAggregator`) works on either table.
 *
 * The `(sessionId, sequence)` unique index is what makes import idempotent (item 23): re-importing
 * the same artifact re-inserts rows Room silently `IGNORE`s wherever that pair already exists,
 * rather than duplicating them.
 */
@Entity(
 tableName = "network_observations",
 indices = [
  Index("sessionId"),
  Index(value = ["sessionId", "sequence"], unique = true),
  Index(value = ["sessionId", "type"]),
 ],
)
data class NetworkObservationEntity(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val sessionId: String,
 val sequence: Long,
 val timestampEpochMs: Long,
 val type: String,
 val protocol: String?,
 val destinationIp: String?,
 val destinationPort: Int?,
 val connectionId: Long?,
 val startTimeEpochMs: Long?,
 val endTimeEpochMs: Long?,
 val uploadedBytes: Long?,
 val downloadedBytes: Long?,
 val failureReason: String?,
 val failureDetail: String?,
 val hostname: String?,
 val resolvedAddressesCsv: String?,
 val transactionId: Int?,
 val sourcePort: Int?,
 val limitName: String?,
 val currentValue: Long?,
 val limitValue: Long?,
)

/** Personal-side durable [com.nadeem.apkscope.core.model.RuntimeObservationSummary] — one row per session, item 26's `SandboxSession → 0..1 RuntimeObservationSummary`. */
@Entity(tableName = "runtime_observation_summaries")
data class RuntimeObservationSummaryEntity(
 @PrimaryKey val sessionId: String,
 val startedAtEpochMs: Long,
 val endedAtEpochMs: Long,
 val connectionCount: Int,
 val dnsQueryCount: Int,
 val uniqueObservedDomains: Int,
 val uploadedBytes: Long,
 val downloadedBytes: Long,
 val blockedConnectionCount: Int,
 val failedConnectionCount: Int,
 val droppedObservationCount: Long,
 val schemaVersion: Int,
 val truncated: Boolean,
 val exportedObservationCount: Int,
 val totalObservationCount: Int,
 val importedAtEpochMs: Long,
)

@Dao
interface NetworkObservationDao {
 @Insert(onConflict = OnConflictStrategy.IGNORE)
 suspend fun insertAll(rows: List<NetworkObservationEntity>): List<Long>

 @Query("SELECT * FROM network_observations WHERE sessionId = :sessionId ORDER BY sequence ASC")
 fun observeForSession(sessionId: String): Flow<List<NetworkObservationEntity>>

 @Query("SELECT * FROM network_observations WHERE sessionId = :sessionId ORDER BY sequence DESC LIMIT :limit")
 fun observeLatestForSession(sessionId: String, limit: Int): Flow<List<NetworkObservationEntity>>

 @Query("SELECT COUNT(*) FROM network_observations WHERE sessionId = :sessionId")
 suspend fun count(sessionId: String): Int

 @Insert(onConflict = OnConflictStrategy.REPLACE)
 suspend fun upsertSummary(summary: RuntimeObservationSummaryEntity)

 @Query("SELECT * FROM runtime_observation_summaries WHERE sessionId = :sessionId")
 fun observeSummary(sessionId: String): Flow<RuntimeObservationSummaryEntity?>

 @Query("SELECT * FROM runtime_observation_summaries WHERE sessionId = :sessionId")
 suspend fun getSummary(sessionId: String): RuntimeObservationSummaryEntity?
}
