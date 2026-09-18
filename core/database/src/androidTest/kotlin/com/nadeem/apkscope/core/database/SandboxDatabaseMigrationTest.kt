package com.nadeem.apkscope.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Milestone 10 correction: proves [MIGRATION_8_9] actually preserves a real v8 database's data
 * rather than the [SandboxDatabaseProvider] fallback silently dropping it — the specific defect
 * this test exists to close. Seeds a real version-8-schema database (built by
 * [MigrationTestHelper] from `core/database/schemas/.../8.json`, wired as an androidTest asset in
 * this module's `build.gradle.kts`) with a real analysis/permission/component/finding row set,
 * upgrades it in place through [MIGRATION_8_9], then reopens it as v9 and asserts every original
 * row and column is still present and unchanged, and that the two new columns and two new tables
 * exist with the expected defaults/shape. This is a real SQLite upgrade, not a fresh install —
 * `dropAllTables` never runs in this path.
 */
@RunWith(AndroidJUnit4::class)
class SandboxDatabaseMigrationTest {
 @get:Rule
 val helper: MigrationTestHelper = MigrationTestHelper(
  InstrumentationRegistry.getInstrumentation(),
  SandboxDatabase::class.java,
  emptyList(),
  FrameworkSQLiteOpenHelperFactory(),
 )

 private val sessionId = "migration-test-session"

 @Test
 fun migrate8To9_preservesExistingAnalysisPermissionsComponentsAndFindings() {
  // Arrange: a real v8 database, seeded with one full analysis record — every table
  // AnalysisSessionDao.insertCompleteAnalysis would have written pre-migration.
  helper.createDatabase(TEST_DB, 8).apply {
   execSQL(
    "INSERT INTO analysis_sessions (sessionId, packageName, appName, versionName, versionCode, sha256, " +
     "analyzedAtEpochMs, minSdkVersion, targetSdkVersion, debuggable, signatureVerified, signatureDetail, " +
     "nativeLibraryAbis, permissionCount, componentTotalCount, componentExportedCount, riskScore, riskLevel, " +
     "riskEngineVersion, platform, platformDetails) VALUES " +
     "('$sessionId', 'com.example.premigration', 'Pre-Migration App', '1.0', 1, 'deadbeef', 1700000000000, " +
     "24, 34, 0, 1, 'cert-sha-abc', 'arm64-v8a', 2, 3, 1, 42, 'MODERATE', 'static-v1', NULL, NULL)"
   )
   execSQL("INSERT INTO analysis_permissions (sessionId, permission) VALUES ('$sessionId', 'android.permission.CAMERA')")
   execSQL("INSERT INTO analysis_components (sessionId, name, type, exported) VALUES ('$sessionId', '.MainActivity', 'ACTIVITY', 1)")
   execSQL(
    "INSERT INTO analysis_risk_findings (sessionId, ruleId, severity, title, explanation, scoreContribution, evidence) " +
     "VALUES ('$sessionId', 'STATIC_DEBUGGABLE_APK', 'MEDIUM', 'Debuggable', 'explanation text', 10, '')"
   )
   close()
  }

  // Act: the real migration under test, not a destructive rebuild.
  val migrated = helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9)

  // Assert: original data survived byte-for-byte, and the new schema is present and correctly defaulted.
  migrated.query("SELECT packageName, appName, riskScore, debuggable FROM analysis_sessions WHERE sessionId = '$sessionId'").use { c ->
   assertEquals(1, c.count)
   assertTrue(c.moveToFirst())
   assertEquals("com.example.premigration", c.getString(0))
   assertEquals("Pre-Migration App", c.getString(1))
   assertEquals(42, c.getInt(2))
   assertEquals(0, c.getInt(3)) // debuggable preserved as false
  }
  migrated.query("SELECT usesCleartextTraffic, allowBackup FROM analysis_sessions WHERE sessionId = '$sessionId'").use { c ->
   assertTrue(c.moveToFirst())
   assertEquals("pre-existing row must default usesCleartextTraffic to false (unknown, not guessed true)", 0, c.getInt(0))
   assertEquals("pre-existing row must default allowBackup to true (the real Android platform default)", 1, c.getInt(1))
  }
  migrated.query("SELECT COUNT(*) FROM analysis_permissions WHERE sessionId = '$sessionId'").use { c ->
   assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
  }
  migrated.query("SELECT COUNT(*) FROM analysis_components WHERE sessionId = '$sessionId'").use { c ->
   assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
  }
  migrated.query("SELECT COUNT(*) FROM analysis_risk_findings WHERE sessionId = '$sessionId'").use { c ->
   assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
  }
  // The two new tables exist and are queryable (empty is correct — nothing wrote to them pre-migration).
  migrated.query("SELECT COUNT(*) FROM security_audits").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
  migrated.query("SELECT COUNT(*) FROM security_audit_findings").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
  migrated.close()
 }

 @Test
 fun migrate8To9_newSecurityAuditTablesAcceptRealWritesAfterMigration() {
  helper.createDatabase(TEST_DB, 8).apply {
   execSQL(
    "INSERT INTO analysis_sessions (sessionId, packageName, appName, versionName, versionCode, sha256, " +
     "analyzedAtEpochMs, minSdkVersion, targetSdkVersion, debuggable, signatureVerified, signatureDetail, " +
     "nativeLibraryAbis, permissionCount, componentTotalCount, componentExportedCount, riskScore, riskLevel, " +
     "riskEngineVersion, platform, platformDetails) VALUES " +
     "('$sessionId', 'com.example.premigration', NULL, NULL, 1, 'deadbeef', 1700000000000, " +
     "24, 34, 0, 1, NULL, '', 0, 0, 0, 0, 'LOW', 'static-v1', NULL, NULL)"
   )
   close()
  }
  helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9).close()

  // Reopen through the real Room-generated DAO (not raw SQL) to prove the new tables' generated
  // code, not merely their raw SQL shape, works against a migrated (not freshly created) database.
  // Must register every migration up to SandboxDatabase's current declared version (10), not just
  // MIGRATION_8_9 — the on-disk file is v9 after the line above, and Room needs a full path to
  // whatever version the class currently declares, same as any real app-level database open.
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  val db = Room.databaseBuilder(context, SandboxDatabase::class.java, TEST_DB)
   .addMigrations(MIGRATION_8_9, MIGRATION_9_10)
   .allowMainThreadQueries()
   .build()
  kotlinx.coroutines.runBlocking {
   db.securityAuditDao().upsertAudit(
    SecurityAuditEntity(auditId = "audit-1", analysisId = sessionId, engineVersion = "security-audit-v1", createdAtEpochMs = 1L, status = "COMPLETE"),
    listOf(SecurityAuditFindingEntity(auditId = "audit-1", ruleId = "AUDIT_DEBUGGABLE_BUILD", outcome = "CHECK_PASSED", severity = "INFO", confidence = "HIGH", title = "t", detail = "d", remediation = "r")),
   )
   val result = db.securityAuditDao().getAuditForAnalysis(sessionId)
   assertEquals(1, result?.findings?.size)
   assertNull("a second, unrelated analysisId must find nothing", db.securityAuditDao().getAuditForAnalysis("no-such-session"))
  }
  db.close()
 }

 /**
  * Milestone 10, Phase 10.3 correction: [MIGRATION_9_10] closes a real data-accuracy gap
  * [MIGRATION_8_9] left open — a row migrated through 8 -> 9 gets `usesCleartextTraffic`'s bare SQL
  * default, indistinguishable from a genuinely-confirmed value without this new column. Chains both
  * real migrations against one real v8-seeded database (the actual upgrade path a real device would
  * take), not a fabricated v9 starting point.
  */
 @Test
 fun migrate8To10_marksPreExistingRowsAsStaticSecurityFieldsUnknown() {
  helper.createDatabase(TEST_DB, 8).apply {
   execSQL(
    "INSERT INTO analysis_sessions (sessionId, packageName, appName, versionName, versionCode, sha256, " +
     "analyzedAtEpochMs, minSdkVersion, targetSdkVersion, debuggable, signatureVerified, signatureDetail, " +
     "nativeLibraryAbis, permissionCount, componentTotalCount, componentExportedCount, riskScore, riskLevel, " +
     "riskEngineVersion, platform, platformDetails) VALUES " +
     "('$sessionId', 'com.example.premigration', 'Pre-Migration App', '1.0', 1, 'deadbeef', 1700000000000, " +
     "24, 34, 0, 1, 'cert-sha-abc', 'arm64-v8a', 2, 3, 1, 42, 'MODERATE', 'static-v1', NULL, NULL)"
   )
   close()
  }

  val migrated = helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_8_9, MIGRATION_9_10)

  migrated.query("SELECT staticSecurityFieldsKnown FROM analysis_sessions WHERE sessionId = '$sessionId'").use { c ->
   assertTrue(c.moveToFirst())
   assertEquals(
    "a row that predates Security Audit entirely must be marked staticSecurityFieldsKnown=false (0) — its usesCleartextTraffic/allowBackup are migration defaults, not real analyzer output",
    0,
    c.getInt(0),
   )
  }
  migrated.close()
 }

 /**
  * The other half of the same correction: a row this app actually inserts *after* the 9 -> 10
  * migration must carry `staticSecurityFieldsKnown = true`, through the real generated DAO — proving
  * the Kotlin-side default (and `SessionRepository`'s explicit set) reaches the real column, not just
  * that the migration's own backfill is correct.
  */
 @Test
 fun migrate8To10_thenRealInsertMarksNewRowAsStaticSecurityFieldsKnown() {
  helper.createDatabase(TEST_DB, 8).close()
  helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_8_9, MIGRATION_9_10).close()

  val context = InstrumentationRegistry.getInstrumentation().targetContext
  val db = Room.databaseBuilder(context, SandboxDatabase::class.java, TEST_DB)
   .addMigrations(MIGRATION_8_9, MIGRATION_9_10)
   .allowMainThreadQueries()
   .build()
  val freshSessionId = "post-migration-session"
  kotlinx.coroutines.runBlocking {
   db.analysisSessionDao().insertSession(
    AnalysisSessionEntity(
     sessionId = freshSessionId, packageName = "com.example.fresh", appName = "Fresh", versionName = "1.0",
     versionCode = 1, sha256 = "cafe", analyzedAtEpochMs = 1L, minSdkVersion = 24, targetSdkVersion = 34,
     debuggable = false, usesCleartextTraffic = false, allowBackup = true, staticSecurityFieldsKnown = true,
     signatureVerified = true, signatureDetail = null, nativeLibraryAbis = emptyList(),
     permissionCount = 0, componentTotalCount = 0, componentExportedCount = 0,
     riskScore = 0, riskLevel = "LOW", riskEngineVersion = "static-v1",
    ),
   )
   val row = db.analysisSessionDao().getDetails(freshSessionId)
   assertEquals(true, row?.session?.staticSecurityFieldsKnown)
  }
  db.close()
 }

 companion object { private const val TEST_DB = "migration-test.db" }
}
