package com.nadeem.apkscope.core.database

import android.content.Context
import androidx.room.Room

/**
 * The one place a real [SandboxDatabase] instance is constructed — a plain `@Volatile`
 * double-checked-lock singleton (no DI framework in this codebase, matching every other
 * `core:*`/`app` repository's style). Always resolves via `context.applicationContext`, so it is
 * safe to call with any `Context`.
 *
 * **Correction (Milestone 10, Security Audit)**: this previously called
 * `fallbackToDestructiveMigration(dropAllTables = true)` unconditionally, on the stated (and at the
 * time accurate) grounds that this was a pre-release stage with no data worth preserving. That
 * changed the moment Security Audit shipped as a real, user-facing feature on top of existing
 * analysis history — an upgrade must not silently erase every prior analysis, report, sandbox
 * session, and evidence record just to add two columns and two tables. [MIGRATION_8_9] now covers
 * that specific transition losslessly (see its own doc comment), and [MIGRATION_9_10] covers a
 * data-accuracy gap found in review one column later (see its own doc comment).
 * `fallbackToDestructiveMigration()` is kept only as a fallback for versions *before* 8, which never
 * had a real migration path and are not a transition either correction was about — confirmed via
 * this repository's own commit history that no version before 8 has ever been distributed to, or run
 * on, a real device carrying data worth preserving (schema v8 has been the only version in active use
 * since 2026-09-11, the version every real Pixel 8 session through Milestone 9's 2026-09-14 closure
 * ran on) — an install on one of those very old dev-era versions still loses data on upgrade, which is
 * the same pre-existing behavior as before this correction, not a new regression, and not currently a
 * real risk to any actual retained data.
 */
object SandboxDatabaseProvider {
 @Volatile private var instance: SandboxDatabase? = null

 fun get(context: Context): SandboxDatabase = instance ?: synchronized(this) {
  instance ?: Room.databaseBuilder(context.applicationContext, SandboxDatabase::class.java, SandboxDatabase.DB_NAME)
   .addMigrations(MIGRATION_8_9, MIGRATION_9_10)
   .fallbackToDestructiveMigration(dropAllTables = true)
   .build()
   .also { instance = it }
 }
}
