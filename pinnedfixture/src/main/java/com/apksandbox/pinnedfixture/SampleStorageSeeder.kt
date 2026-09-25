package com.apksandbox.pinnedfixture

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Populates this fixture app with realistic-looking private storage (shared preferences, files,
 * images, and a SQLite database) so APK Scope's Storage Inspector has real, varied data to scan
 * against instead of an empty sandbox. Runs once per install, guarded by a marker file — safe to
 * call on every app start.
 *
 * Every value here is synthetic and fixture-only: no real user data, no functioning credentials.
 * Some values are deliberately shaped like secrets (a long hex token, an email address, a
 * JWT-looking string) purely so the Storage Inspector's own masking rules have something real to
 * mask during a demo.
 */
object SampleStorageSeeder {
    private const val TAG = "SampleStorageSeeder"
    private const val MARKER_FILE = ".sample_data_seeded"

    fun seedIfNeeded(context: Context) {
        val marker = File(context.filesDir, MARKER_FILE)
        if (marker.exists()) return
        try {
            seedSharedPreferences(context)
            seedFiles(context)
            seedCache(context)
            seedNoBackupFiles(context)
            seedExternalFiles(context)
            seedImages(context)
            seedDatabase(context)
            marker.parentFile?.mkdirs()
            marker.writeText(System.currentTimeMillis().toString())
            Log.i(TAG, "Sample storage seeded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seed sample storage: ${e.message}", e)
        }
    }

    /** Forces a fresh reseed next launch -- deletes the marker only, leaving old data in place until re-seeded. */
    fun resetMarker(context: Context) {
        File(context.filesDir, MARKER_FILE).delete()
    }

    private fun seedSharedPreferences(context: Context) {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().apply {
            putString("theme", "dark")
            putBoolean("notifications_enabled", true)
            putInt("launch_count", 7)
            putLong("last_opened_epoch_ms", System.currentTimeMillis())
            putFloat("sync_interval_minutes", 15.5f)
            apply()
        }

        context.getSharedPreferences("user_profile", Context.MODE_PRIVATE).edit().apply {
            putString("display_name", "Alex Rivera")
            putString("user_email", "demo.user@example.com")
            putString("auth_token", "tok_9f8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c3d2e1f0a")
            putString("session_id", "sess-4471ac2e9b7d4f0a")
            apply()
        }

        context.getSharedPreferences("cached_api_response", Context.MODE_PRIVATE).edit().apply {
            putString("last_jwt", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZW1vLXVzZXIifQ.c2lnbmF0dXJlZmFrZQ")
            putString("last_endpoint", "https://httpbin.org/get")
            putInt("cache_version", 3)
            apply()
        }
    }

    private fun seedFiles(context: Context) {
        writeText(File(context.filesDir, "notes/readme.txt"), "POC Pinned Fixture\nThis file demonstrates a nested app-files entry for storage scanning.\n")
        writeText(File(context.filesDir, "notes/todo.txt"), "- Verify certificate pinning\n- Verify gadget hook status\n- Reseed sample data\n")
        writeText(
            File(context.filesDir, "cache_manifest.json"),
            "{\n  \"schema\": 1,\n  \"lastSync\": ${System.currentTimeMillis()},\n  \"entries\": [\"get\", \"status\"]\n}\n",
        )
        writeText(
            File(context.filesDir, "app.log"),
            "INFO  PocLoaderApplication: fixture started\n" +
                "INFO  PinnedActivity: certificate pinning enforced\n" +
                "DEBUG PinnedActivity: gadget status check requested\n",
        )
    }

    private fun seedCache(context: Context) {
        writeText(File(context.cacheDir, "temp_download.tmp"), "cached-response-bytes-placeholder")
    }

    private fun seedNoBackupFiles(context: Context) {
        try {
            writeText(File(context.noBackupFilesDir, "device_binding.txt"), "device_binding_id=fixture-device-0001\n")
        } catch (e: Exception) {
            Log.w(TAG, "No-backup files dir unavailable: ${e.message}")
        }
    }

    private fun seedExternalFiles(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: return
            writeText(File(dir, "export_preview.txt"), "External-files sample entry for storage scanning.\n")
        } catch (e: Exception) {
            Log.w(TAG, "External files dir unavailable: ${e.message}")
        }
    }

    private fun seedImages(context: Context) {
        val imagesDir = File(context.filesDir, "images").apply { mkdirs() }
        writeGradientPng(File(imagesDir, "profile_photo.png"), Color.parseColor("#0284C7"), Color.parseColor("#9333EA"))
        writeGradientJpeg(File(imagesDir, "screenshot_preview.jpg"), Color.parseColor("#16A34A"), Color.parseColor("#0F172A"))
    }

    private fun writeGradientPng(file: File, startColor: Int, endColor: Int) {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), startColor, endColor, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bitmap.recycle()
    }

    private fun writeGradientJpeg(file: File, startColor: Int, endColor: Int) {
        val width = 160
        val height = 90
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), startColor, endColor, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
        bitmap.recycle()
    }

    private fun seedDatabase(context: Context) {
        FixtureDbHelper(context).writableDatabase.use { db ->
            db.execSQL("DELETE FROM notes")
            db.execSQL("DELETE FROM contacts")

            val notes = listOf(
                Triple("Pinning check", "Confirm httpbin.org SPKI pin is enforced before each release.", System.currentTimeMillis()),
                Triple("Gadget status", "Gadget status button reports whether Frida classes are loadable.", System.currentTimeMillis()),
            )
            notes.forEach { (title, body, createdAt) ->
                db.execSQL(
                    "INSERT INTO notes (title, body, created_at) VALUES (?, ?, ?)",
                    arrayOf<Any>(title, body, createdAt),
                )
            }

            val contacts = listOf(
                Pair("QA Contact", "+1-555-0100"),
                Pair("Release Owner", "+1-555-0101"),
            )
            contacts.forEach { (name, phone) ->
                db.execSQL("INSERT INTO contacts (name, phone) VALUES (?, ?)", arrayOf(name, phone))
            }
        }
    }

    private fun writeText(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private class FixtureDbHelper(context: Context) :
        SQLiteOpenHelper(context, "fixture_data.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE contacts (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, phone TEXT NOT NULL)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS notes")
            db.execSQL("DROP TABLE IF EXISTS contacts")
            onCreate(db)
        }
    }
}
