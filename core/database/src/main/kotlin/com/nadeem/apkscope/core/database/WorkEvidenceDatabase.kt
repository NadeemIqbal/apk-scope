package com.nadeem.apkscope.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Checkpoint 4.1: Work-profile-local durable evidence storage — a **physically separate** Room
 * database (its own file, `work_evidence.db`) from [SandboxDatabase], never opened from the
 * personal-side process. Because Android gives every profile ("user") its own private
 * `/data/user/<N>/<package>/databases/` directory even for the identical package/class, this file
 * is automatically Work-local storage the moment only Work-side code ever calls
 * [WorkEvidenceDatabaseProvider.get] — the same real per-user filesystem isolation the checkpoint's
 * "treat Personal/Work Room as explicitly separate stores" instruction describes, not a naming
 * convention this code enforces itself.
 *
 * Deliberately minimal: one row per sandbox session, holding the single most complete cumulative
 * fact-set known Work-side (see `app`'s `WorkEvidenceStore`, which owns the merge logic and the
 * JSON shape — this module only persists an opaque blob, exactly like `RiskFindingEntity.evidence`
 * elsewhere in this database). This is explicitly *not* the final report database — no
 * `NetworkObservation` batch schema, no cross-session history, nothing beyond "the facts one
 * in-flight or recently-ended session needs to survive a lost report."
 */
@Entity(tableName = "work_session_evidence")
data class WorkSessionEvidenceEntity(
 @PrimaryKey val sessionId: String,
 val packageName: String,
 /** Opaque cumulative JSON blob — see `app`'s `SandboxStatusReport`/`WorkEvidenceStore`. This module has no dependency on that shape. */
 val latestReportJson: String,
 val updatedAtEpochMs: Long,
)

@Dao
interface WorkEvidenceDao {
 @Insert(onConflict = OnConflictStrategy.REPLACE)
 suspend fun upsert(entity: WorkSessionEvidenceEntity)

 @Query("SELECT * FROM work_session_evidence WHERE sessionId = :sessionId")
 suspend fun get(sessionId: String): WorkSessionEvidenceEntity?

 @Query("DELETE FROM work_session_evidence WHERE sessionId = :sessionId")
 suspend fun delete(sessionId: String)

 /** Test-only visibility (matches this module's existing `countEnforcementsForSession` convention) — a genuine, non-tautological way to confirm a row is really gone. */
 @Query("SELECT COUNT(*) FROM work_session_evidence WHERE sessionId = :sessionId")
 suspend fun count(sessionId: String): Int
}

/**
 * Version bumped 1 -> 2 for checkpoint 5's [WorkNetworkObservationEntity]/[WorkRuntimeSummaryEntity]
 * (see that file's doc comment) — a second, typed table alongside the original cumulative-JSON-blob
 * one, not a replacement of it; [WorkSessionEvidenceEntity] keeps recording checkpoint 4.1's
 * lifecycle facts exactly as before.
 */
@Database(
 entities = [
  WorkSessionEvidenceEntity::class,
  WorkNetworkObservationEntity::class,
  WorkRuntimeSummaryEntity::class,
  WorkAndroidDnsEvidenceEntity::class,
  WorkAndroidConnectEvidenceEntity::class,
  WorkAndroidEvidenceSummaryEntity::class,
 ],
 version = 3,
 exportSchema = true,
)
abstract class WorkEvidenceDatabase : RoomDatabase() {
 abstract fun workEvidenceDao(): WorkEvidenceDao
 abstract fun workNetworkObservationDao(): WorkNetworkObservationDao
 abstract fun workAndroidEvidenceDao(): WorkAndroidEvidenceDao

 companion object {
  const val DB_NAME = "work_evidence.db"
 }
}

/** Mirrors [SandboxDatabaseProvider]'s exact singleton shape — this codebase's one established pattern for a Room instance, applied to a second, unrelated database file. */
object WorkEvidenceDatabaseProvider {
 @Volatile private var instance: WorkEvidenceDatabase? = null

 fun get(context: Context): WorkEvidenceDatabase = instance ?: synchronized(this) {
  instance ?: Room.databaseBuilder(context.applicationContext, WorkEvidenceDatabase::class.java, WorkEvidenceDatabase.DB_NAME)
   .fallbackToDestructiveMigration(dropAllTables = true)
   .build()
   .also { instance = it }
 }
}
