package com.nadeem.apkscope.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Milestone 10 (Security Audit), correction pass: a real migration replacing the destructive
 * fallback this project's schema changes had relied on through version 8. `SandboxDatabaseProvider`
 * previously called `fallbackToDestructiveMigration(dropAllTables = true)` unconditionally — accepted
 * at the pre-release, no-real-users stage documented on that class, but a real product feature
 * (Security Audit) reaching real users' existing analyses now means an upgrade must not silently
 * erase every prior analysis, report, sandbox session, and evidence record. This migration exists so
 * that stops being true for the 8 → 9 transition specifically.
 *
 * Statements below are copied verbatim from the Room-exported schema diff between
 * `core/database/schemas/.../8.json` and `.../9.json` (`createSql`/index `createSql` fields) — not
 * hand-guessed — so the resulting schema is byte-identical to what Room itself would generate for a
 * fresh v9 database. Confirmed via `PRAGMA` inspection after running this migration against a real v8
 * database seeded with data (see `AnalysisSessionDaoMigrationTest`).
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
 override fun migrate(db: SupportSQLiteDatabase) {
  // AnalysisSessionEntity gained usesCleartextTraffic/allowBackup (Security Audit, Phase 10.1/10.2).
  // Existing rows predate both flags — default them to the same values ApkAnalysisInput's own Kotlin
  // defaults use (false/true respectively), which is an honest "unknown, assume the safer-for-alerting
  // value" choice, not a claim about what those older analyses' real manifests actually declared. A
  // re-run of Security Audit against an old analysis will still show these two rules' results as
  // provisional until the analysis is re-imported — this migration does not, and cannot, recover data
  // that was never captured for it.
  db.execSQL("ALTER TABLE analysis_sessions ADD COLUMN usesCleartextTraffic INTEGER NOT NULL DEFAULT 0")
  db.execSQL("ALTER TABLE analysis_sessions ADD COLUMN allowBackup INTEGER NOT NULL DEFAULT 1")

  db.execSQL(
   "CREATE TABLE IF NOT EXISTS `security_audits` (`auditId` TEXT NOT NULL, `analysisId` TEXT NOT NULL, " +
    "`engineVersion` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
    "`failureReason` TEXT, PRIMARY KEY(`auditId`))"
  )
  db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_security_audits_analysisId` ON `security_audits` (`analysisId`)")

  db.execSQL(
   "CREATE TABLE IF NOT EXISTS `security_audit_findings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
    "`auditId` TEXT NOT NULL, `ruleId` TEXT NOT NULL, `outcome` TEXT NOT NULL, `severity` TEXT NOT NULL, " +
    "`confidence` TEXT NOT NULL, `title` TEXT NOT NULL, `detail` TEXT NOT NULL, `remediation` TEXT NOT NULL, " +
    "FOREIGN KEY(`auditId`) REFERENCES `security_audits`(`auditId`) ON UPDATE NO ACTION ON DELETE CASCADE )"
  )
  db.execSQL("CREATE INDEX IF NOT EXISTS `index_security_audit_findings_auditId` ON `security_audit_findings` (`auditId`)")
  db.execSQL("CREATE INDEX IF NOT EXISTS `index_security_audit_findings_ruleId` ON `security_audit_findings` (`ruleId`)")
 }
}

/**
 * Milestone 10 (Security Audit), Phase 10.3 correction: a real data-accuracy gap [MIGRATION_8_9]
 * left open, found during review rather than left silent. That migration's own comment already
 * named the honest limitation — a row migrated through 8 -> 9 gets `usesCleartextTraffic`'s bare SQL
 * default (`false`), not a real analyzer-read value — but nothing downstream could tell a defaulted
 * "false" apart from a genuinely confirmed one, so `StaticAuditRules.CLEARTEXT_TRAFFIC_ENABLED` would
 * read it as a confirmed `CHECK_PASSED`. This migration adds
 * [AnalysisSessionEntity.staticSecurityFieldsKnown], `false` (unknown) for every row that exists at
 * the moment this migration runs, `true` for every row this app inserts afterward (see that column's
 * own doc comment).
 *
 * Deliberately conservative in one specific way, stated rather than left implicit: a row inserted
 * *between* the 8 -> 9 and 9 -> 10 migrations (e.g. a fresh analysis run on a build that had 8 -> 9
 * but not yet 9 -> 10) already carries a real analyzer-read `usesCleartextTraffic`/`allowBackup`
 * value, but this migration has no way to distinguish that row from a genuinely pre-existing one — it
 * marks both `false` (unknown) alike. Under-claiming a handful of actually-known rows as unknown is
 * the safe direction of this trade-off; the alternative (an unrecoverable false claim of "known") is
 * not acceptable here.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
 override fun migrate(db: SupportSQLiteDatabase) {
  db.execSQL("ALTER TABLE analysis_sessions ADD COLUMN staticSecurityFieldsKnown INTEGER NOT NULL DEFAULT 0")
 }
}
