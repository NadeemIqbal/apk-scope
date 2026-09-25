package com.nadeem.apkscope.poc.apkrepack

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One on-demand, bounded content request for a single storage-inspector entry -- see `frida-storage-preview.js`. */
internal sealed interface FridaPreviewRequest {
    val root: String
    val path: String

    data class Text(override val root: String, override val path: String) : FridaPreviewRequest
    data class Hex(override val root: String, override val path: String) : FridaPreviewRequest
    data class Image(override val root: String, override val path: String) : FridaPreviewRequest
    data class DatabaseSchema(override val root: String, override val path: String) : FridaPreviewRequest
    data class DatabaseRows(
        override val root: String,
        override val path: String,
        val table: String,
        val offset: Int,
        val searchQuery: String = "",
    ) : FridaPreviewRequest
}

internal sealed interface FridaStoragePreviewResult {
    data class Text(val text: String, val byteLength: Int, val truncated: Boolean) : FridaStoragePreviewResult
    data class Hex(val hex: String, val byteLength: Int, val truncated: Boolean, val fellBackFromText: Boolean) : FridaStoragePreviewResult
    data class Image(
        val base64Jpeg: String,
        val width: Int,
        val height: Int,
        val originalWidth: Int,
        val originalHeight: Int,
        val downsampled: Boolean,
    ) : FridaStoragePreviewResult

    data class DatabaseColumn(
        val name: String,
        val declaredType: String,
        val notNull: Boolean,
        val primaryKeyPosition: Int,
    )
    data class DatabaseTable(
        val name: String,
        val objectType: String,
        val columns: List<DatabaseColumn>,
        val columnsTruncated: Boolean,
    )
    data class DatabaseSchema(val tables: List<DatabaseTable>, val truncated: Boolean) : FridaStoragePreviewResult

    data class DatabaseCell(val value: Any?, val storageType: String, val truncated: Boolean, val blobBytes: Long?)
    data class DatabaseRows(
        val table: String,
        val offset: Int,
        val nextOffset: Int,
        val columns: List<String>,
        val rows: List<List<DatabaseCell>>,
        val rowCount: Long,
        val hasMore: Boolean,
        val columnsTruncated: Boolean,
    ) : FridaStoragePreviewResult
}

internal object FridaStoragePreview {
    private const val SCRIPT_ASSET = "frida-storage-preview.js"
    private const val MAX_RESULT_CHARS = 64 * 1024

    fun commandSource(context: Context, request: FridaPreviewRequest): String {
        val template = context.assets.open(SCRIPT_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val requestJson = JSONObject().apply {
            put("root", request.root)
            put("path", request.path)
            when (request) {
                is FridaPreviewRequest.Text -> put("op", "file_text")
                is FridaPreviewRequest.Hex -> put("op", "file_hex")
                is FridaPreviewRequest.Image -> put("op", "file_image")
                is FridaPreviewRequest.DatabaseSchema -> put("op", "db_schema")
                is FridaPreviewRequest.DatabaseRows -> {
                    put("op", "db_rows")
                    put("table", request.table)
                    put("offset", request.offset)
                    put("searchQuery", request.searchQuery.take(160))
                }
            }
        }
        return "var __previewRequest = $requestJson;\n$template"
    }

    fun parse(raw: String): FridaStoragePreviewResult {
        require(raw.length <= MAX_RESULT_CHARS) { "The preview result exceeded the transport limit." }
        val json = JSONObject(raw)
        return when (val op = json.optString("op")) {
            "file_text" -> FridaStoragePreviewResult.Text(
                text = json.optString("text"),
                byteLength = json.optInt("byteLength", 0),
                truncated = json.optBoolean("truncated", false),
            )
            "file_hex" -> FridaStoragePreviewResult.Hex(
                hex = json.optString("hex"),
                byteLength = json.optInt("byteLength", 0),
                truncated = json.optBoolean("truncated", false),
                fellBackFromText = json.optBoolean("fellBackFromText", false),
            )
            "file_image" -> FridaStoragePreviewResult.Image(
                base64Jpeg = json.optString("base64Jpeg"),
                width = json.optInt("width", 0),
                height = json.optInt("height", 0),
                originalWidth = json.optInt("originalWidth", 0),
                originalHeight = json.optInt("originalHeight", 0),
                downsampled = json.optBoolean("downsampled", false),
            )
            "db_schema" -> {
                val tablesJson = json.optJSONArray("tables") ?: JSONArray()
                val tables = buildList {
                    for (i in 0 until tablesJson.length()) {
                        val entry = tablesJson.optJSONObject(i) ?: continue
                        val columnsJson = entry.optJSONArray("columns") ?: JSONArray()
                        val columns = buildList {
                            for (columnIndex in 0 until columnsJson.length()) {
                                val column = columnsJson.optJSONObject(columnIndex) ?: continue
                                add(
                                    FridaStoragePreviewResult.DatabaseColumn(
                                        name = column.optString("name"),
                                        declaredType = column.optString("declaredType"),
                                        notNull = column.optBoolean("notNull", false),
                                        primaryKeyPosition = column.optInt("primaryKeyPosition", 0).coerceAtLeast(0),
                                    ),
                                )
                            }
                        }
                        add(
                            FridaStoragePreviewResult.DatabaseTable(
                                name = entry.optString("name"),
                                objectType = entry.optString("objectType", "table"),
                                columns = columns,
                                columnsTruncated = entry.optBoolean("columnsTruncated", false),
                            ),
                        )
                    }
                }
                FridaStoragePreviewResult.DatabaseSchema(tables, json.optBoolean("truncated", false))
            }
            "db_rows" -> {
                val columnsJson = json.optJSONArray("columns") ?: JSONArray()
                val columns = buildList { for (i in 0 until columnsJson.length()) add(columnsJson.optString(i)) }
                val rowsJson = json.optJSONArray("rows") ?: JSONArray()
                val rows = buildList {
                    for (r in 0 until rowsJson.length()) {
                        val rowArray = rowsJson.optJSONArray(r) ?: continue
                        add(
                            buildList {
                                for (c in 0 until rowArray.length()) {
                                    val cell = rowArray.optJSONObject(c) ?: JSONObject()
                                    val value = cell.opt("value").takeUnless { it == null || it == JSONObject.NULL }
                                    add(
                                        FridaStoragePreviewResult.DatabaseCell(
                                            value = value,
                                            storageType = cell.optString("storageType", "UNKNOWN"),
                                            truncated = cell.optBoolean("truncated", false),
                                            blobBytes = if (cell.has("blobBytes")) cell.optLong("blobBytes").coerceAtLeast(0L) else null,
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
                FridaStoragePreviewResult.DatabaseRows(
                    table = json.optString("table"),
                    offset = json.optInt("offset", 0).coerceAtLeast(0),
                    nextOffset = json.optInt("nextOffset", 0).coerceAtLeast(0),
                    columns = columns,
                    rows = rows,
                    rowCount = json.optLong("rowCount", 0L).coerceAtLeast(0L),
                    hasMore = json.optBoolean("hasMore", false),
                    columnsTruncated = json.optBoolean("columnsTruncated", false),
                )
            }
            else -> error("Unsupported preview response: $op")
        }
    }
}
