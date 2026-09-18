package com.nadeem.apkscope.poc.apkrepack

import kotlinx.coroutines.isActive
import java.io.File
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * Bounded APK extraction with safety checks.
 *
 * Implements extraction with:
 * - Path traversal rejection (`../`, absolute paths)
 * - Duplicate-entry rejection
 * - Per-entry and total expanded-size caps (reuses core/common bounds constants)
 * - Cancellation support (coroutine `isActive` checks)
 *
 * Output is a map of entry name → extracted file path, with entries sanitized and validated.
 * META-INF entries are excluded during extraction (will be rebuilt during signing).
 */
object ApkExtractor {

    // Reuse existing bounds constants from core/common if available
    // For now, define them here; they should match ApkSizeLimits.kt when integrated
    private const val MAX_PER_ENTRY_BYTES = 100L * 1024L * 1024L // 100 MiB per entry
    private const val MAX_TOTAL_BYTES = 200L * 1024L * 1024L // 200 MiB total

    data class ExtractionResult(
        val entries: Map<String, File>,
        val totalBytesExtracted: Long
    )

    /**
     * Extract an APK's contents to a working directory.
     *
     * @param apkFile The APK to extract
     * @param workDir Directory to extract into (will be created if needed)
     * @return ExtractionResult with entry map and total bytes
     * @throws IllegalArgumentException if validation fails (path traversal, duplicates, size limits, etc.)
     * @throws Exception if extraction fails
     */
    suspend fun extract(apkFile: File, workDir: File): ExtractionResult {
        if (!apkFile.exists() || !apkFile.isFile) {
            throw IllegalArgumentException("APK file does not exist: ${apkFile.absolutePath}")
        }

        workDir.mkdirs()
        if (!workDir.isDirectory) {
            throw IllegalArgumentException("Cannot create work directory: ${workDir.absolutePath}")
        }

        val entries = mutableMapOf<String, File>()
        var totalBytesExtracted = 0L
        val seenEntryNames = mutableSetOf<String>()

        ZipFile(apkFile).use { zip ->
            for (zipEntry in zip.entries()) {
                // Check cancellation
                if (!coroutineContext.isActive) {
                    throw InterruptedException("APK extraction cancelled")
                }

                val entryName = zipEntry.name

                // Skip META-INF entries (will be rebuilt during signing)
                if (entryName.startsWith("META-INF/")) {
                    continue
                }

                // Path traversal rejection
                if (entryName.contains("..") || entryName.startsWith("/")) {
                    throw IllegalArgumentException("Path traversal attempt blocked: $entryName")
                }

                // Duplicate entry rejection
                if (seenEntryNames.contains(entryName)) {
                    throw IllegalArgumentException("Duplicate entry found: $entryName")
                }
                seenEntryNames.add(entryName)

                // Per-entry size check
                if (zipEntry.size > MAX_PER_ENTRY_BYTES) {
                    throw IllegalArgumentException(
                        "Entry exceeds size limit: $entryName (${zipEntry.size} > $MAX_PER_ENTRY_BYTES bytes)"
                    )
                }

                // Total size check
                if (totalBytesExtracted + zipEntry.size > MAX_TOTAL_BYTES) {
                    throw IllegalArgumentException(
                        "Total extraction would exceed size limit (${totalBytesExtracted + zipEntry.size} > $MAX_TOTAL_BYTES bytes)"
                    )
                }

                // Create subdirectories for nested entries
                val entryPath = File(workDir, entryName)
                entryPath.parentFile?.mkdirs()

                // Extract the entry
                if (!zipEntry.isDirectory) {
                    zip.getInputStream(zipEntry).use { input ->
                        entryPath.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    entries[entryName] = entryPath
                    totalBytesExtracted += zipEntry.size
                } else {
                    entryPath.mkdirs()
                }
            }
        }

        if (entries.isEmpty()) {
            throw IllegalArgumentException("APK extraction resulted in no entries (may be malformed)")
        }

        return ExtractionResult(entries, totalBytesExtracted)
    }

    /**
     * Validate that an entry path is safe (no traversal attempts).
     */
    private fun validateEntryPath(entryName: String) {
        if (entryName.contains("..") || entryName.startsWith("/") || entryName.startsWith("\\")) {
            throw IllegalArgumentException("Invalid entry path: $entryName")
        }
    }
}
