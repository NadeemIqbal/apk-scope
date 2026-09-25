package com.nadeem.apkscope.poc.apkrepack

import android.content.Context
import org.json.JSONObject

internal data class FridaStorageSnapshot(
    val packageName: String,
    val processId: Int,
    val capturedAt: Long,
    val elapsedMs: Long,
    val entries: List<FridaStorageEntry>,
    val warnings: List<String>,
    val truncated: Boolean,
) {
    companion object {
        private const val SCRIPT_ASSET = "frida-storage-snapshot.js"
        private const val SCHEMA_VERSION = 1
        private const val MAX_ENTRIES = 180
        private const val MAX_FIELD_CHARS = 2_048
        private const val MAX_RESULT_CHARS = 64 * 1024

        fun commandSource(context: Context): String = context.assets
            .open(SCRIPT_ASSET)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        fun parse(raw: String, expectedPackage: String): FridaStorageSnapshot {
            require(raw.length <= MAX_RESULT_CHARS) { "The storage result exceeded the snapshot limit." }
            val json = JSONObject(raw)
            require(json.optInt("schemaVersion", -1) == SCHEMA_VERSION) { "The target returned an unsupported storage snapshot." }
            val packageName = json.optString("packageName", "")
            require(packageName == expectedPackage) { "The snapshot package did not match the active target." }
            val processId = json.optInt("pid", -1)
            require(processId > 0) { "The snapshot did not include a valid process identity." }
            val sourceEntries = json.optJSONArray("entries") ?: error("The snapshot did not contain an item list.")
            val entries = buildList {
                for (index in 0 until minOf(sourceEntries.length(), MAX_ENTRIES)) {
                    val item = sourceEntries.optJSONObject(index) ?: continue
                    val kind = item.optString("kind")
                    if (kind !in ALLOWED_KINDS) continue
                    val id = item.safeString("id", 200)
                    val name = item.safeString("name", MAX_FIELD_CHARS)
                    val key = item.safeString("key", MAX_FIELD_CHARS)
                    val path = item.safeString("path", MAX_FIELD_CHARS)
                    if (id.isBlank() || (name.isBlank() && key.isBlank() && path.isBlank())) continue
                    add(
                        FridaStorageEntry(
                            id = id,
                            kind = kind,
                            root = item.safeString("root", 160),
                            store = item.safeString("store", 160),
                            key = key,
                            valueType = item.safeString("valueType", 80),
                            value = item.safeString("value", MAX_FIELD_CHARS),
                            valueTruncated = item.optBoolean("valueTruncated", false),
                            name = name,
                            path = path,
                            sizeBytes = item.optLong("sizeBytes", 0L).coerceAtLeast(0L),
                            modifiedAt = item.optLong("modifiedAt", 0L).coerceAtLeast(0L),
                            previewAvailable = item.optBoolean("previewAvailable", false),
                        ),
                    )
                }
            }
            val warningJson = json.optJSONArray("warnings")
            val warnings = buildList {
                if (warningJson != null) {
                    for (index in 0 until minOf(warningJson.length(), 16)) {
                        warningJson.optString(index).take(MAX_FIELD_CHARS).takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }
            return FridaStorageSnapshot(
                packageName = packageName,
                processId = processId,
                capturedAt = json.optLong("capturedAt", 0L).coerceAtLeast(0L),
                elapsedMs = json.optLong("elapsedMs", 0L).coerceAtLeast(0L),
                entries = entries,
                warnings = warnings,
                truncated = json.optBoolean("truncated", false) || sourceEntries.length() > MAX_ENTRIES,
            )
        }

        private val ALLOWED_KINDS = setOf("preference", "file", "image", "database")

        private fun JSONObject.safeString(name: String, limit: Int): String =
            optString(name, "").take(limit).replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "�")
    }
}

internal data class FridaStorageEntry(
    val id: String,
    val kind: String,
    val root: String,
    val store: String,
    val key: String,
    val valueType: String,
    val value: String,
    val valueTruncated: Boolean,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val previewAvailable: Boolean,
)
