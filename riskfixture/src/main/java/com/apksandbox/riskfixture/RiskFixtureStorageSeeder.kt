package com.apksandbox.riskfixture

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Creates harmless, repeatable private-storage examples for APK Scope's Storage Inspector. */
internal object RiskFixtureStorageSeeder {
    private const val PREFS_NAME = "fixture_profile"
    private const val RUNTIME_FILE = "inspector-fixture/runtime/runtime-samples.jsonl"

    private val bundledFiles = listOf(
        "storage_seed/profile.json" to "inspector-fixture/seed/profile.json",
        "storage_seed/notes.txt" to "inspector-fixture/seed/notes.txt",
    )

    fun seedOnStartup(context: Context) {
        bundledFiles.forEach { (assetPath, relativePath) ->
            copyBundledFileIfMissing(context, assetPath, File(context.filesDir, relativePath))
        }

        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (preferences.getInt("seed_version", 0) < 1) {
            preferences.edit()
                .putString("display_label", "Risk Fixture Demo")
                .putString("environment", "work-profile-sample")
                .putBoolean("notifications_enabled", false)
                .putInt("launch_count", 1)
                .putInt("runtime_sample_count", 0)
                .putInt("seed_version", 1)
                .commit()
        } else {
            preferences.edit().putInt("launch_count", preferences.getInt("launch_count", 0) + 1).commit()
        }

        withDatabase(context) { database ->
            database.addRecordIfMissing(
                id = "startup-profile",
                category = "startup",
                detail = "Bundled profile sample copied into private app files",
                createdAt = 1_759_000_000_000L,
            )
            database.addRecordIfMissing(
                id = "startup-preferences",
                category = "startup",
                detail = "Synthetic preferences created on first launch",
                createdAt = 1_759_000_000_001L,
            )
        }
    }

    fun addRuntimeSample(context: Context): Int {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sequence = preferences.getInt("runtime_sample_count", 0) + 1
        val timestamp = System.currentTimeMillis()
        val event = JSONObject()
            .put("kind", "user_requested_runtime_sample")
            .put("sequence", sequence)
            .put("created_at", timestamp)
            .put("note", "Synthetic fixture data; no user content")
            .toString()

        val runtimeFile = File(context.filesDir, RUNTIME_FILE)
        runtimeFile.parentFile?.mkdirs()
        FileOutputStream(runtimeFile, true).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.append(event)
            writer.newLine()
        }

        preferences.edit()
            .putInt("runtime_sample_count", sequence)
            .putString("last_runtime_action", "add_sample_$sequence")
            .putLong("last_runtime_action_at", timestamp)
            .commit()

        withDatabase(context) { database ->
            database.addRecord(
                id = "runtime-$sequence",
                category = "runtime",
                detail = "User requested sample #$sequence; synthetic fixture data only",
                createdAt = timestamp,
            )
        }
        return sequence
    }

    private fun copyBundledFileIfMissing(context: Context, assetPath: String, destination: File) {
        if (destination.exists()) return
        destination.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output) }
        }
    }

    private inline fun withDatabase(context: Context, action: (FixtureStorageDatabase) -> Unit) {
        FixtureStorageDatabase(context).use(action)
    }
}

private class FixtureStorageDatabase(context: Context) :
    SQLiteOpenHelper(context, "fixture-storage.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE storage_samples (
                id TEXT PRIMARY KEY NOT NULL,
                category TEXT NOT NULL,
                detail TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun addRecordIfMissing(id: String, category: String, detail: String, createdAt: Long) {
        writableDatabase.insertWithOnConflict(
            "storage_samples",
            null,
            values(id, category, detail, createdAt),
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun addRecord(id: String, category: String, detail: String, createdAt: Long) {
        writableDatabase.insertOrThrow("storage_samples", null, values(id, category, detail, createdAt))
    }

    private fun values(id: String, category: String, detail: String, createdAt: Long) = ContentValues().apply {
        put("id", id)
        put("category", category)
        put("detail", detail)
        put("created_at", createdAt)
    }
}
