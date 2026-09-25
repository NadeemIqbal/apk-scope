package com.nadeem.apkscope.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * v0.1 database — schema and DAOs for session lifecycle + network observations, promoted
 * incrementally checkpoint by checkpoint. Originally a plain `SQLiteOpenHelper` skeleton
 * (checkpoint 1), migrated to Room (checkpoint 2), gained the static-analysis-history tables
 * (checkpoint 3), gained the real sandbox-session lifecycle tables (checkpoint 4, see
 * `SandboxSessionEntities.kt`), and now (checkpoint 5) `NetworkObservationEntity`/`NetworkObservationDao`
 * — checkpoint-1's never-populated JSON-blob skeleton — are replaced with the typed, idempotent-import
 * shape in `RuntimeObservationEntities.kt`, alongside a new `RuntimeObservationSummaryEntity`.
 *
 * Version bumped 3 -> 4 for checkpoint 5's real [RuntimeObservationEntities.kt] shape (no
 * migration written — see `SandboxDatabaseProvider`'s doc comment for why
 * `fallbackToDestructiveMigration()` remains an accepted, documented choice at this pre-release
 * stage, not an oversight).
 *
 * Version bumped 8 -> 9 for Milestone 10 (Security Audit, Phase 10.2): [SecurityAuditEntity]/
 * [SecurityAuditFindingEntity] (see `SecurityAuditEntities.kt`), plus `AnalysisSessionEntity` gained
 * `usesCleartextTraffic`/`allowBackup` columns. Corrected in the Phase 10.2 correction pass (same
 * milestone, later same day): this transition specifically has a real, data-preserving
 * [MIGRATION_8_9] (see `Migrations.kt`) — the "same accepted destructive-migration policy" this
 * comment previously claimed stopped being accurate once Security Audit became a real feature with
 * real data worth preserving through an upgrade. `fallbackToDestructiveMigration` remains only for
 * any version older than 8, which never had a real migration path either, per the same reasoning.
 *
 * Version bumped 9 -> 10 for Milestone 10, Phase 10.3 (same-day correction, reviewed alongside the
 * NETWORK_TRUST rule wording fix): added `AnalysisSessionEntity.staticSecurityFieldsKnown`, real
 * migration [MIGRATION_9_10]. Closes a real data-accuracy gap `MIGRATION_8_9` left open — a row
 * migrated through 8 -> 9 gets `usesCleartextTraffic`'s bare SQL default (`false`), which
 * `StaticAuditRules.CLEARTEXT_TRAFFIC_ENABLED` would otherwise read as a confirmed, audited
 * `CHECK_PASSED` rather than "this analysis predates the field and the real value is unknown". See
 * [AnalysisSessionEntity.staticSecurityFieldsKnown]'s own doc.
 */
@Database(
 entities = [
  SandboxSessionEntity::class, SandboxPolicyEnforcementEntity::class,
  NetworkObservationEntity::class, RuntimeObservationSummaryEntity::class,
  AndroidDnsEvidenceEntity::class, AndroidConnectEvidenceEntity::class, AndroidEvidenceSummaryEntity::class,
  AnalysisSessionEntity::class, AnalysisPermissionEntity::class, AnalysisComponentEntity::class, RiskFindingEntity::class,
  FinalReportEntity::class, FinalReportFindingEntity::class,
  SecurityAuditEntity::class, SecurityAuditFindingEntity::class,
 ],
 version = 11,
 exportSchema = true,
)
@TypeConverters(StringListConverters::class)
abstract class SandboxDatabase : RoomDatabase() {
 abstract fun sandboxSessionDao(): SandboxSessionDao
 abstract fun observationDao(): NetworkObservationDao
 abstract fun analysisSessionDao(): AnalysisSessionDao
 abstract fun androidEvidenceDao(): AndroidEvidenceDao
 abstract fun finalReportDao(): FinalReportDao
 abstract fun securityAuditDao(): SecurityAuditDao
 abstract fun storageDao(): StorageDao

 companion object {
  const val DB_NAME = "sandbox.db"
 }
}
