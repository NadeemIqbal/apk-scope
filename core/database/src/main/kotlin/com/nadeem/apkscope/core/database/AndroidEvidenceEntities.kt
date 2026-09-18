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
 * Checkpoint 6: Personal-side durable storage for independent Android-recorded network evidence
 * via DPM (DnsEvent), imported from the Work Profile AndroidEvidenceArtifact.
 *
 * Parallel to, but strictly isolated from, NetworkObservationEntity (VPN observations).
 */
@Entity(
 tableName = "android_dns_evidence",
 indices = [
  Index("sessionId"),
  Index(value = ["sessionId", "eventId"], unique = true),
  Index(value = ["sessionId", "packageName"]),
 ],
)
data class AndroidDnsEvidenceEntity(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val sessionId: String,
 val eventId: Long,
 val batchToken: Long,
 val packageName: String,
 val timestampEpochMs: Long,
 val receivedAtEpochMs: Long,
 val hostname: String,
 val resolvedAddressesCsv: String,
 val totalResolvedAddressCount: Int,
)

/**
 * Checkpoint 6: Personal-side durable storage for independent Android-recorded network evidence
 * via DPM (ConnectEvent), imported from the Work Profile AndroidEvidenceArtifact.
 *
 * Parallel to, but strictly isolated from, NetworkObservationEntity (VPN observations).
 */
@Entity(
 tableName = "android_connect_evidence",
 indices = [
  Index("sessionId"),
  Index(value = ["sessionId", "eventId"], unique = true),
  Index(value = ["sessionId", "packageName"]),
 ],
)
data class AndroidConnectEvidenceEntity(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val sessionId: String,
 val eventId: Long,
 val batchToken: Long,
 val packageName: String,
 val timestampEpochMs: Long,
 val receivedAtEpochMs: Long,
 val destinationAddress: String,
 val destinationPort: Int,
)

/**
 * Checkpoint 6: Personal-side summary of imported Android evidence per session.
 */
@Entity(tableName = "android_evidence_summaries")
data class AndroidEvidenceSummaryEntity(
 @PrimaryKey val sessionId: String,
 val dnsCount: Int,
 val connectCount: Int,
 val status: String,
 val firstEventTimestampEpochMs: Long?,
 val lastEventTimestampEpochMs: Long?,
 val importedAtEpochMs: Long,
)

@Dao
interface AndroidEvidenceDao {
 @Insert(onConflict = OnConflictStrategy.IGNORE)
 suspend fun insertDnsEvents(rows: List<AndroidDnsEvidenceEntity>)

 @Insert(onConflict = OnConflictStrategy.IGNORE)
 suspend fun insertConnectEvents(rows: List<AndroidConnectEvidenceEntity>)

 @Insert(onConflict = OnConflictStrategy.REPLACE)
 suspend fun upsertSummary(summary: AndroidEvidenceSummaryEntity)

 @Query("SELECT * FROM android_dns_evidence WHERE sessionId = :sessionId ORDER BY timestampEpochMs ASC")
 suspend fun getDnsEventsForSession(sessionId: String): List<AndroidDnsEvidenceEntity>

 @Query("SELECT * FROM android_connect_evidence WHERE sessionId = :sessionId ORDER BY timestampEpochMs ASC")
 suspend fun getConnectEventsForSession(sessionId: String): List<AndroidConnectEvidenceEntity>

 @Query("SELECT * FROM android_dns_evidence WHERE sessionId = :sessionId ORDER BY timestampEpochMs ASC")
 fun observeDnsEventsForSession(sessionId: String): Flow<List<AndroidDnsEvidenceEntity>>

 @Query("SELECT * FROM android_connect_evidence WHERE sessionId = :sessionId ORDER BY timestampEpochMs ASC")
 fun observeConnectEventsForSession(sessionId: String): Flow<List<AndroidConnectEvidenceEntity>>

 @Query("SELECT * FROM android_evidence_summaries WHERE sessionId = :sessionId")
 suspend fun getSummary(sessionId: String): AndroidEvidenceSummaryEntity?

 @Query("SELECT * FROM android_evidence_summaries WHERE sessionId = :sessionId")
 fun observeSummary(sessionId: String): Flow<AndroidEvidenceSummaryEntity?>

 @Query("DELETE FROM android_dns_evidence WHERE sessionId = :sessionId")
 suspend fun deleteDnsForSession(sessionId: String): Int

 @Query("DELETE FROM android_connect_evidence WHERE sessionId = :sessionId")
 suspend fun deleteConnectForSession(sessionId: String): Int

 @Query("DELETE FROM android_evidence_summaries WHERE sessionId = :sessionId")
 suspend fun deleteSummaryForSession(sessionId: String): Int
}
