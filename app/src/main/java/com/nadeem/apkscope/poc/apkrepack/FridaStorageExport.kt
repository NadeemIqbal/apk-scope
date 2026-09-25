package com.nadeem.apkscope.poc.apkrepack

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/** A chunked read of one file already present in a target storage snapshot. */
internal data class FridaStorageExportRequest(
    val root: String,
    val path: String,
    val expectedSizeBytes: Long,
    val offset: Long,
    val length: Int = CHUNK_BYTES,
) {
    companion object {
        const val CHUNK_BYTES = 24 * 1024
        const val MAX_FILE_BYTES = 100L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
    }
}

internal data class FridaStorageExportChunk(
    val offset: Long,
    val totalBytes: Long,
    val bytes: ByteArray,
)

internal object FridaStorageExport {
    private const val SCRIPT_ASSET = "frida-storage-export.js"
    private const val MAX_RESULT_CHARS = 50 * 1024

    fun commandSource(context: Context, request: FridaStorageExportRequest): String {
        require(request.expectedSizeBytes in 0..FridaStorageExportRequest.MAX_FILE_BYTES) {
            "The selected file is larger than the 100 MiB export limit."
        }
        require(request.offset >= 0 && request.offset <= request.expectedSizeBytes) { "Invalid export offset." }
        require(request.length in 1..FridaStorageExportRequest.CHUNK_BYTES) { "Invalid export chunk size." }
        val template = context.assets.open(SCRIPT_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val requestJson = JSONObject().apply {
            put("root", request.root)
            put("path", request.path)
            put("expectedSizeBytes", request.expectedSizeBytes)
            put("offset", request.offset)
            put("length", request.length)
        }
        return "var __exportRequest = $requestJson;\n$template"
    }

    fun parse(raw: String, expectedOffset: Long): FridaStorageExportChunk {
        require(raw.length <= MAX_RESULT_CHARS) { "The export chunk exceeded the transport limit." }
        val json = JSONObject(raw)
        require(json.optString("op") == "export_chunk") { "The target returned an unsupported export response." }
        val offset = json.optLong("offset", -1L)
        require(offset == expectedOffset) { "The target returned an unexpected export chunk." }
        val totalBytes = json.optLong("totalBytes", -1L)
        require(totalBytes in 0..FridaStorageExportRequest.MAX_FILE_BYTES) { "The target file exceeds the export size limit." }
        val bytes = Base64.decode(json.optString("base64"), Base64.DEFAULT)
        require(bytes.size <= FridaStorageExportRequest.CHUNK_BYTES) { "The target returned an oversized export chunk." }
        require(offset + bytes.size <= totalBytes) { "The export chunk extends beyond the file." }
        return FridaStorageExportChunk(offset, totalBytes, bytes)
    }
}
