package com.nadeem.apkscope.domain.storage

import android.content.Context
import android.os.StatFs
import com.nadeem.apkscope.core.database.SandboxDatabaseProvider
import com.nadeem.apkscope.domain.StaticAnalysisResultStore
import com.nadeem.apkscope.domain.sandbox.UrlEvidenceImportStatusStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class StorageCategory {
    APKS,
    STATIC_ANALYSIS,
    DYNAMIC_ANALYSIS,
    ALL,
}

data class StorageCategorySummary(
    val category: StorageCategory,
    val title: String,
    val count: Int,
    val bytes: Long,
    val itemLabel: String,
) {
    val hasData: Boolean get() = count > 0 || bytes > 0L
}

data class StorageSummary(
    val availableBytes: Long,
    val thresholdBytes: Long,
    val apks: StorageCategorySummary,
    val staticAnalysis: StorageCategorySummary,
    val dynamicAnalysis: StorageCategorySummary,
    val hasActiveSandboxSession: Boolean,
) {
    val isLowStorage: Boolean get() = availableBytes <= thresholdBytes
    val managedBytes: Long get() = apks.bytes + staticAnalysis.bytes + dynamicAnalysis.bytes
}

/**
 * Reads and clears the Personal Profile's user-created analysis artifacts.
 *
 * APK files and rich static findings are file-backed. Runtime and Android evidence are persisted
 * in Room, so their size is reported as a database-payload estimate rather than pretending the
 * shared SQLite file can be perfectly attributed to one session.
 */
class StorageRepository(private val context: Context) {
    companion object {
        /** Low-free-space warning threshold requested for the Home screen. */
        const val LOW_STORAGE_THRESHOLD_BYTES: Long = 1L * 1024L * 1024L * 1024L
        private val TERMINAL_SANDBOX_STATES = setOf("COMPLETED", "FAILED", "CANCELLED")
    }

    private val appContext = context.applicationContext
    private val database = SandboxDatabaseProvider.get(appContext)

    suspend fun snapshot(): StorageSummary = withContext(Dispatchers.IO) {
        val analysisDirectory = File(appContext.filesDir, "analysis")
        val apkFiles = analysisDirectory.listFiles { file ->
            file.isFile && file.extension.equals("apk", ignoreCase = true)
        }.orEmpty()

        val staticDirectory = File(appContext.filesDir, "analysis_store")
        val staticFiles = staticDirectory.listFiles { file -> file.isFile }.orEmpty()
        val statusDirectory = File(appContext.filesDir, "url_evidence_import_status")
        val statusFiles = statusDirectory.listFiles { file -> file.isFile }.orEmpty()
        val analysisIds = database.storageDao().getAnalysisIds()
        val dynamicAnalysisIds = database.storageDao().getDynamicAnalysisIds()
        val sandboxSessionIds = database.storageDao().getSandboxSessionIds()

        StorageSummary(
            availableBytes = StatFs(appContext.filesDir.path).availableBytes,
            thresholdBytes = LOW_STORAGE_THRESHOLD_BYTES,
            apks = StorageCategorySummary(
                category = StorageCategory.APKS,
                title = "APKs",
                count = apkFiles.size,
                bytes = apkFiles.sumOf(File::length),
                itemLabel = if (apkFiles.size == 1) "APK" else "APKs",
            ),
            staticAnalysis = StorageCategorySummary(
                category = StorageCategory.STATIC_ANALYSIS,
                title = "Static analysis data",
                count = analysisIds.size,
                bytes = staticFiles.sumOf(File::length) + statusFiles.sumOf(File::length) +
                    analysisDirectory.listFiles { file -> file.isFile && file.extension == "json" }
                        .orEmpty().sumOf(File::length),
                itemLabel = if (analysisIds.size == 1) "APK" else "APKs",
            ),
            dynamicAnalysis = StorageCategorySummary(
                category = StorageCategory.DYNAMIC_ANALYSIS,
                title = "Dynamic analysis",
                count = dynamicAnalysisIds.size,
                bytes = database.storageDao().estimateDynamicDataBytes(),
                itemLabel = if (dynamicAnalysisIds.size == 1) "APK" else "APKs",
            ),
            hasActiveSandboxSession = database.storageDao().getSandboxSessionIds().any { sessionId ->
                val session = database.sandboxSessionDao().getSession(sessionId)
                session?.state !in TERMINAL_SANDBOX_STATES
            },
        )
    }

    suspend fun delete(category: StorageCategory) = withContext(Dispatchers.IO) {
        when (category) {
            StorageCategory.APKS -> deleteApkFiles()
            StorageCategory.STATIC_ANALYSIS -> deleteStaticAnalysis()
            StorageCategory.DYNAMIC_ANALYSIS -> deleteDynamicAnalysis()
            StorageCategory.ALL -> deleteAll()
        }
    }

    private suspend fun deleteStaticAnalysis() {
        val activeAnalysisIds = activeSandboxAnalysisIds()
        val analysisIds = database.storageDao().getAnalysisIds().filterNot(activeAnalysisIds::contains)
        analysisIds.forEach { analysisId ->
            database.storageDao().deleteStaticDataForAnalysis(analysisId)
            StaticAnalysisResultStore.clear(appContext, analysisId)
            UrlEvidenceImportStatusStore.clear(appContext, analysisId)
        }
    }

    private suspend fun deleteDynamicAnalysis() {
        database.storageDao().getSandboxSessionIds().forEach { sessionId ->
            val session = database.sandboxSessionDao().getSession(sessionId)
            if (session?.state in TERMINAL_SANDBOX_STATES) {
                database.storageDao().deleteDynamicDataForSession(sessionId)
            }
        }
    }

    private suspend fun deleteAll() {
        ensureNoActiveSandboxSession()
        database.storageDao().deleteAllPersistedData()
        StaticAnalysisResultStore.clearAll(appContext)
        UrlEvidenceImportStatusStore.clearAll(appContext)
        deleteApkFiles()
        deleteFilesWithExtension(File(appContext.filesDir, "analysis"), "json")
        deleteDirectoryFiles(File(appContext.filesDir, "sandbox/control"))
        deleteDirectoryFiles(File(appContext.filesDir, "reports"))
        appContext.cacheDir.deleteRecursively()
        appContext.cacheDir.mkdirs()
    }

    private suspend fun activeSandboxAnalysisIds(): Set<String> =
        database.storageDao().getSandboxSessionIds().mapNotNull { sessionId ->
            database.sandboxSessionDao().getSession(sessionId)?.takeIf { it.state !in TERMINAL_SANDBOX_STATES }?.analysisId
        }.toSet()

    private suspend fun deleteApkFiles() {
        val protectedAnalysisIds = activeSandboxAnalysisIds()
        val directory = File(appContext.filesDir, "analysis")
        directory.listFiles { file ->
            file.isFile &&
                file.extension.equals("apk", ignoreCase = true) &&
                file.nameWithoutExtension !in protectedAnalysisIds
        }.orEmpty().forEach(File::delete)
    }

    private fun deleteFilesWithExtension(directory: File, extension: String) {
        directory.listFiles { file -> file.isFile && file.extension == extension }.orEmpty().forEach(File::delete)
    }

    private fun deleteDirectoryFiles(directory: File) {
        directory.listFiles()?.forEach { file -> if (file.isFile) file.delete() }
    }

    private suspend fun ensureNoActiveSandboxSession() {
        val activeSession = database.storageDao().getSandboxSessionIds().firstOrNull { sessionId ->
            database.sandboxSessionDao().getSession(sessionId)?.state !in TERMINAL_SANDBOX_STATES
        }
        check(activeSession == null) { "Finish the active sandbox session before clearing saved analysis data." }
    }

}

fun formatStorageBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (value >= 100 || value % 1.0 == 0.0) {
        "%.0f %s".format(java.util.Locale.US, value, units[index])
    } else {
        "%.1f %s".format(java.util.Locale.US, value, units[index])
    }
}
