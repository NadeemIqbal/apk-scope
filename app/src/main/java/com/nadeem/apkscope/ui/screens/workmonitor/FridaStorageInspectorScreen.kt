package com.nadeem.apkscope.ui.screens.workmonitor

import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nadeem.apkscope.poc.apkrepack.FridaPreviewRequest
import com.nadeem.apkscope.poc.apkrepack.FridaStorageEntry
import com.nadeem.apkscope.poc.apkrepack.FridaStorageExport
import com.nadeem.apkscope.poc.apkrepack.FridaStorageExportRequest
import com.nadeem.apkscope.poc.apkrepack.FridaStoragePreview
import com.nadeem.apkscope.poc.apkrepack.FridaStoragePreviewResult
import com.nadeem.apkscope.poc.apkrepack.FridaStorageSnapshot
import com.nadeem.apkscope.poc.apkrepack.FridaTrafficMonitor
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.theme.MonoCodeStyle
import com.nadeem.apkscope.ui.theme.Spacing
import com.nadeem.apkscope.core.crossprofile.CrossProfileContract
import com.nadeem.apkscope.core.crossprofile.Handoff
import com.nadeem.apkscope.sandbox.SANDBOX_FILE_PROVIDER_AUTHORITY
import com.nadeem.apkscope.spike.SandboxAdminReceiver
import io.github.zakayothuku.roominspector.driver.TablePageResult
import io.github.zakayothuku.roominspector.model.ColumnInfo
import io.github.zakayothuku.roominspector.ui.TableBrowserView
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The dialog's own loading/loaded/failed lifecycle for whichever preview request is currently in flight -- separate from the bulk scan's own state. */
private sealed interface PreviewUiState {
    data object Loading : PreviewUiState
    data class Loaded(val result: FridaStoragePreviewResult) : PreviewUiState
    data class Failed(val message: String) : PreviewUiState
}

private val TEXT_LIKE_EXTENSIONS = setOf("txt", "json", "xml", "log", "md", "yaml", "yml", "ini", "properties", "csv", "conf", "cfg", "html", "htm")
private const val DATABASE_PAGE_SIZE = 100

private fun looksTextLike(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in TEXT_LIKE_EXTENSIONS

private fun uniqueExportFileName(name: String, usedNames: MutableSet<String>): String {
    val safeName = name.replace('/', '_').replace('\\', '_').filterNot { it.code < 32 }.take(180).ifBlank { "item" }
    val base = safeName.substringBeforeLast('.', safeName).ifBlank { "item" }
    val extension = safeName.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
    val normalized = if (safeName.equals("manifest.json", ignoreCase = true)) "item_manifest.json" else safeName
    if (usedNames.add(normalized)) return normalized
    var suffix = 2
    while (true) {
        val candidate = base + " (" + suffix++ + ")" + (extension?.let { ".$it" } ?: "")
        if (usedNames.add(candidate)) return candidate
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val base = current.baseContext
        if (base === current) break
        current = base
    }
    return current as? Activity
}

@Composable
fun FridaStorageInspectorScreen(
    context: Context,
    targetPackage: String,
    sessionId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by FridaTrafficMonitor.shared.status.collectAsState()
    val scope = rememberCoroutineScope()
    var snapshot by remember(targetPackage, sessionId) { mutableStateOf<FridaStorageSnapshot?>(null) }
    var pendingCommandId by remember(targetPackage, sessionId) { mutableStateOf<String?>(null) }
    var scanning by remember(targetPackage, sessionId) { mutableStateOf(false) }
    var errorMessage by remember(targetPackage, sessionId) { mutableStateOf<String?>(null) }

    // Content-preview dialog state -- independent of the bulk scan above. `dbSchema` is cached
    // separately from `previewState` so drilling into a table and returning to the table list never
    // needs a second round trip for the schema that is already known.
    var previewEntry by remember(targetPackage, sessionId) { mutableStateOf<FridaStorageEntry?>(null) }
    var previewCommandId by remember(targetPackage, sessionId) { mutableStateOf<String?>(null) }
    var previewState by remember(targetPackage, sessionId) { mutableStateOf<PreviewUiState?>(null) }
    var previewImageRevealed by remember(targetPackage, sessionId) { mutableStateOf(false) }
    var dbSchema by remember(targetPackage, sessionId) { mutableStateOf<FridaStoragePreviewResult.DatabaseSchema?>(null) }
    var selectedTable by remember(targetPackage, sessionId) { mutableStateOf<String?>(null) }
    var tableColumns by remember(targetPackage, sessionId) { mutableStateOf<List<String>>(emptyList()) }
    var tableRows by remember(targetPackage, sessionId) { mutableStateOf<List<List<FridaStoragePreviewResult.DatabaseCell>>>(emptyList()) }
    var tablePageOffsets by remember(targetPackage, sessionId) { mutableStateOf(listOf(0)) }
    var tablePageIndex by remember(targetPackage, sessionId) { mutableIntStateOf(0) }
    var tableSearchQuery by remember(targetPackage, sessionId) { mutableStateOf("") }
    var tableNextOffset by remember(targetPackage, sessionId) { mutableIntStateOf(0) }
    var tableHasMore by remember(targetPackage, sessionId) { mutableStateOf(false) }
    var tableColumnsTruncated by remember(targetPackage, sessionId) { mutableStateOf(false) }
    var revealDatabaseValues by remember(targetPackage, sessionId) { mutableStateOf(false) }
    var exportDialog by remember(targetPackage, sessionId) { mutableStateOf(false) }
    var selectedExportIds by remember(targetPackage, sessionId) { mutableStateOf(emptySet<String>()) }
    var exporting by remember(targetPackage, sessionId) { mutableStateOf(false) }
    var exportProgress by remember(targetPackage, sessionId) { mutableStateOf<String?>(null) }
    var exportMessage by remember(targetPackage, sessionId) { mutableStateOf<String?>(null) }
    var exportError by remember(targetPackage, sessionId) { mutableStateOf<String?>(null) }

    fun dispatchPreview(request: FridaPreviewRequest) {
        if (!status.commandReady) {
            previewState = PreviewUiState.Failed("The target-bound channel is not connected.")
            return
        }
        val script = FridaStoragePreview.commandSource(context, request)
        val dispatch = FridaTrafficMonitor.shared.sendScript(script)
        if (dispatch.accepted && dispatch.id != null) {
            previewCommandId = dispatch.id
            previewState = PreviewUiState.Loading
        } else {
            previewState = PreviewUiState.Failed(dispatch.error ?: "The preview request could not be sent to the target.")
        }
    }

    fun openTable(tableName: String, offset: Int, pageIndex: Int, offsets: List<Int>, searchQuery: String = tableSearchQuery) {
        val entry = previewEntry ?: return
        selectedTable = tableName
        tableSearchQuery = searchQuery.take(160)
        tablePageIndex = pageIndex
        tablePageOffsets = offsets
        tableNextOffset = offset
        tableHasMore = false
        tableRows = emptyList()
        tableColumns = emptyList()
        tableColumnsTruncated = false
        revealDatabaseValues = false
        dispatchPreview(FridaPreviewRequest.DatabaseRows(entry.root, entry.path, tableName, offset, tableSearchQuery))
    }

    fun openPreview(entry: FridaStorageEntry) {
        previewEntry = entry
        previewImageRevealed = false
        dbSchema = null
        selectedTable = null
        tableSearchQuery = ""
        tableColumns = emptyList()
        tableRows = emptyList()
        tablePageOffsets = listOf(0)
        tablePageIndex = 0
        tableNextOffset = 0
        tableHasMore = false
        tableColumnsTruncated = false
        revealDatabaseValues = false
        val request = when (entry.kind) {
            "image" -> FridaPreviewRequest.Image(entry.root, entry.path)
            "database" -> FridaPreviewRequest.DatabaseSchema(entry.root, entry.path)
            else -> if (looksTextLike(entry.name)) FridaPreviewRequest.Text(entry.root, entry.path) else FridaPreviewRequest.Hex(entry.root, entry.path)
        }
        dispatchPreview(request)
    }

    fun closePreview() {
        previewEntry = null
        previewState = null
        previewCommandId = null
    }

    LaunchedEffect(targetPackage, sessionId) {
        FridaTrafficMonitor.shared.commandResults.collect { result ->
            if (result.targetPackage != targetPackage || result.sessionId != sessionId) return@collect
            when (result.id) {
                pendingCommandId -> {
                    pendingCommandId = null
                    scanning = false
                    if (!result.ok) {
                        errorMessage = result.error ?: "The target could not complete the storage scan."
                    } else {
                        runCatching { FridaStorageSnapshot.parse(result.result.orEmpty(), targetPackage) }
                            .onSuccess { snapshot = it; errorMessage = null }
                            .onFailure { errorMessage = it.message ?: "The target returned an unreadable snapshot." }
                    }
                }
                previewCommandId -> {
                    previewCommandId = null
                    if (!result.ok) {
                        previewState = PreviewUiState.Failed(result.error ?: "The preview could not be completed.")
                    } else {
                        runCatching { FridaStoragePreview.parse(result.result.orEmpty()) }
                            .onSuccess { parsed ->
                                when (parsed) {
                                    is FridaStoragePreviewResult.DatabaseSchema -> {
                                        dbSchema = parsed
                                        previewState = PreviewUiState.Loaded(parsed)
                                    }
                                    is FridaStoragePreviewResult.DatabaseRows -> {
                                        check(parsed.table == selectedTable) { "The database returned rows for a different table." }
                                        check(parsed.offset == tablePageOffsets.getOrNull(tablePageIndex)) { "The database page did not match the requested offset." }
                                        tableColumns = parsed.columns
                                        tableRows = parsed.rows
                                        tableNextOffset = parsed.nextOffset
                                        tableHasMore = parsed.hasMore
                                        tableColumnsTruncated = parsed.columnsTruncated
                                        previewState = PreviewUiState.Loaded(parsed)
                                    }
                                    else -> previewState = PreviewUiState.Loaded(parsed)
                                }
                            }
                            .onFailure { previewState = PreviewUiState.Failed(it.message ?: "The target returned an unreadable preview.") }
                    }
                }
            }
        }
    }

    fun scanStorage() {
        if (!status.commandReady || scanning) return
        val script = runCatching { FridaStorageSnapshot.commandSource(context) }
            .getOrElse {
                errorMessage = "Storage scan could not be loaded. Rebuild and repack the target APK, then start a new session."
                return
            }
        val dispatch = FridaTrafficMonitor.shared.sendScript(script)
        if (dispatch.accepted && dispatch.id != null) {
            pendingCommandId = dispatch.id
            scanning = true
            errorMessage = null
        } else {
            errorMessage = dispatch.error ?: "The storage scan could not be sent to the target."
        }
    }

    LaunchedEffect(status.commandReady, targetPackage, sessionId) {
        if (status.commandReady && snapshot == null && !scanning) scanStorage()
    }

    fun exportSelectedItems() {
        val current = snapshot ?: return
        val selected = current.entries.filter { it.id in selectedExportIds }
        if (selected.isEmpty() || exporting) return
        exporting = true
        exportError = null
        exportMessage = null
        exportProgress = "Preparing export…"

        scope.launch {
            var archive: File? = null
            try {
                require(targetPackage.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "Invalid target package." }
                UUID.fromString(sessionId)
                require(selected.all { it.kind == "preference" || it.sizeBytes <= FridaStorageExportRequest.MAX_FILE_BYTES }) {
                    "Each file must be 100 MiB or smaller."
                }
                require(selected.filter { it.kind != "preference" }.sumOf { it.sizeBytes } <= FridaStorageExportRequest.MAX_TOTAL_BYTES) {
                    "Selected files exceed the 200 MiB export limit."
                }

                val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT)
                    .withZone(ZoneId.systemDefault()).format(Instant.now())
                val usedNames = mutableSetOf<String>()
                val exportItems = selected.map { entry ->
                    val suggestedName = if (entry.kind == "preference") {
                        "pref_" + entry.store + "_" + entry.key + ".json"
                    } else entry.name
                    val name = uniqueExportFileName(suggestedName, usedNames)
                    val preferenceJson = if (entry.kind == "preference") {
                        JSONObject().apply {
                            put("targetPackage", targetPackage)
                            put("store", entry.store)
                            put("key", entry.key)
                            put("valueType", entry.valueType)
                            put("value", entry.value)
                            put("valueTruncated", entry.valueTruncated)
                            put("capturedAt", current.capturedAt)
                        }.toString().toByteArray(Charsets.UTF_8)
                    } else null
                    Triple(entry, name, preferenceJson)
                }
                require(exportItems.sumOf { item -> item.third?.size?.toLong() ?: item.first.sizeBytes } <= FridaStorageExportRequest.MAX_TOTAL_BYTES) {
                    "Selected items exceed the 200 MiB export limit."
                }
                val manifestFiles = JSONArray()
                exportItems.forEach { (entry, name, preferenceJson) ->
                    manifestFiles.put(
                        JSONObject()
                            .put("name", name)
                            .put("kind", entry.kind)
                            .put("byteLength", preferenceJson?.size?.toLong() ?: entry.sizeBytes),
                    )
                }
                val manifest = JSONObject()
                    .put("schemaVersion", 1)
                    .put("targetPackage", targetPackage)
                    .put("timestamp", timestamp)
                    .put("files", manifestFiles)
                    .toString().toByteArray(Charsets.UTF_8)

                val exportDir = File(context.cacheDir, "storage_exports")
                require(exportDir.mkdirs() || exportDir.isDirectory) { "Could not create the temporary export folder." }
                exportDir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24L * 60 * 60 * 1000 }
                    ?.forEach { it.delete() }
                val archiveFile = File(exportDir, sessionId + "-" + timestamp + ".zip")
                archive = archiveFile

                withContext(Dispatchers.IO) {
                    ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zip ->
                        zip.putNextEntry(ZipEntry("manifest.json"))
                        zip.write(manifest)
                        zip.closeEntry()

                        exportItems.forEachIndexed { index, item ->
                            val entry = item.first
                            val name = item.second
                            val preferenceJson = item.third
                            withContext(Dispatchers.Main) {
                                exportProgress = "Exporting " + (index + 1) + " of " + exportItems.size + "…"
                            }
                            zip.putNextEntry(ZipEntry(name))
                            if (preferenceJson != null) {
                                zip.write(preferenceJson)
                            } else {
                                var offset = 0L
                                while (offset < entry.sizeBytes) {
                                    val request = FridaStorageExportRequest(entry.root, entry.path, entry.sizeBytes, offset)
                                    val dispatch = FridaTrafficMonitor.shared.sendScript(FridaStorageExport.commandSource(context, request))
                                    val commandId = dispatch.id
                                    if (!dispatch.accepted || commandId == null) {
                                        throw IOException(dispatch.error ?: "The target could not send " + entry.name + ".")
                                    }
                                    val result = withTimeout(30_000) {
                                        FridaTrafficMonitor.shared.commandResults.first {
                                            it.id == commandId && it.targetPackage == targetPackage && it.sessionId == sessionId
                                        }
                                    }
                                    if (!result.ok) throw IOException(result.error ?: "The target could not export " + entry.name + ".")
                                    val chunk = FridaStorageExport.parse(result.result.orEmpty(), offset)
                                    require(chunk.totalBytes == entry.sizeBytes) { "The file changed during export. Refresh and try again." }
                                    require(chunk.bytes.isNotEmpty()) { "The target returned an empty file chunk." }
                                    zip.write(chunk.bytes)
                                    offset += chunk.bytes.size
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                }

                val completedArchive = requireNotNull(archive)
                require(completedArchive.length() <= CrossProfileContract.MAX_STORAGE_EXPORT_TRANSFER_BYTES) {
                    "The compressed export exceeds the cross-profile transfer limit."
                }
                val activity = context.findActivity() ?: error("The foreground app screen is unavailable.")
                val dpm = activity.getSystemService(android.app.admin.DevicePolicyManager::class.java)
                check(dpm?.isProfileOwnerApp(activity.packageName) == true) { "Work Profile export is not configured." }
                Handoff.configure(activity, android.content.ComponentName(activity, SandboxAdminReceiver::class.java))
                withContext(Dispatchers.Main) { exportProgress = "Sending to Personal Downloads…" }
                Handoff.send(
                    activity,
                    completedArchive,
                    CrossProfileContract.ACTION_STORAGE_EXPORT,
                    sessionId,
                    SANDBOX_FILE_PROVIDER_AUTHORITY,
                )
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ completedArchive.delete() }, 15 * 60 * 1000L)
                exportMessage = "Sent to Downloads/APKScopeExports/" + targetPackage + "/" + timestamp
                selectedExportIds = emptySet()
            } catch (error: Exception) {
                archive?.delete()
                exportError = error.message ?: "The selected items could not be exported."
            } finally {
                exporting = false
                exportProgress = null
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = "Storage Inspector",
                eyebrow = "SANDBOX",
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = {
                            selectedExportIds = emptySet()
                            exportError = null
                            exportMessage = null
                            exportDialog = true
                        },
                        enabled = snapshot?.entries?.isNotEmpty() == true && !exporting,
                    ) { Icon(Icons.Filled.FileDownload, contentDescription = "Export items") }
                    IconButton(onClick = ::scanStorage, enabled = status.commandReady && !scanning) {
                        if (scanning) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        else Icon(Icons.Filled.Refresh, contentDescription = "Refresh storage snapshot")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.base, vertical = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            snapshot?.let { current ->
                Text("${current.entries.size} items · ${formatCaptureTime(current.capturedAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                errorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    items(current.entries, key = { it.id }) { entry ->
                        StorageEntryCard(entry, onView = { openPreview(entry) })
                    }
                    if (current.truncated) item {
                        BaseCard { Text("Snapshot is partial; refresh to scan again.", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    scanning || status.commandReady -> CircularProgressIndicator()
                    errorMessage != null -> Text(errorMessage!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else -> Text("Waiting for target…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    previewEntry?.let { entry ->
        if (entry.kind == "database") {
            BackHandler {
                if (selectedTable != null) {
                    selectedTable = null
                    tableSearchQuery = ""
                    previewState = dbSchema?.let(PreviewUiState::Loaded)
                } else closePreview()
            }
        }
        Dialog(
            onDismissRequest = ::closePreview,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = if (entry.kind == "database") Modifier.fillMaxSize() else Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f),
                shape = if (entry.kind == "database") RoundedCornerShape(0.dp) else RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = if (entry.kind == "database") Modifier.fillMaxSize().padding(Spacing.base)
                        else Modifier.heightIn(max = 560.dp).padding(Spacing.base),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    if (entry.kind == "database") {
                        AppTopBar(
                            title = selectedTable?.let { entry.name + " · " + it } ?: entry.name.ifBlank { entry.key },
                            eyebrow = "DATABASE",
                            onBack = {
                                if (selectedTable != null) {
                                    selectedTable = null
                                    tableSearchQuery = ""
                                    previewState = dbSchema?.let(PreviewUiState::Loaded)
                                } else closePreview()
                            },
                            actions = {
                                IconButton(
                                    onClick = {
                                        val table = selectedTable
                                        if (table == null) dispatchPreview(FridaPreviewRequest.DatabaseSchema(entry.root, entry.path))
                                        else openTable(table, tablePageOffsets.getOrElse(tablePageIndex) { 0 }, tablePageIndex, tablePageOffsets)
                                    },
                                    enabled = status.commandReady && previewState !is PreviewUiState.Loading,
                                ) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh database") }
                            },
                        )
                        Text(entry.root + " · " + entry.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.name.ifBlank { entry.key }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text(entry.root + " · " + entry.path.ifBlank { entry.store }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            IconButton(onClick = ::closePreview) { Icon(Icons.Filled.Close, contentDescription = "Close preview") }
                        }
                    }

                    val loadedRows = (previewState as? PreviewUiState.Loaded)?.result as? FridaStoragePreviewResult.DatabaseRows
                    if (loadedRows != null) {
                        DatabaseRowsPreview(
                            modifier = Modifier.weight(1f),
                            table = loadedRows.table,
                            schema = dbSchema?.tables?.firstOrNull { it.name == loadedRows.table },
                            columns = loadedRows.columns,
                            rows = loadedRows.rows,
                            offset = loadedRows.offset,
                            pageIndex = tablePageIndex,
                            rowCount = loadedRows.rowCount,
                            searchQuery = tableSearchQuery,
                            columnsTruncated = loadedRows.columnsTruncated,
                            revealSensitive = revealDatabaseValues,
                            onToggleSensitive = { revealDatabaseValues = !revealDatabaseValues },
                            onSearchQueryChange = { query -> openTable(loadedRows.table, 0, 0, listOf(0), query) },
                            onPageChange = { requestedPage ->
                                val page = requestedPage.coerceAtLeast(0)
                                val targetOffset = tablePageOffsets.getOrNull(page)
                                    ?: tableNextOffset.takeIf { page == tablePageIndex + 1 && tableHasMore }
                                if (targetOffset != null) {
                                    val offsets = if (page > tablePageIndex) tablePageOffsets.take(page) + targetOffset else tablePageOffsets
                                    openTable(loadedRows.table, targetOffset, page, offsets)
                                }
                            },
                            onRefresh = { openTable(loadedRows.table, loadedRows.offset, tablePageIndex, tablePageOffsets) },
                        )
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
                            when (val state = previewState) {
                                null, PreviewUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(Spacing.xs))
                                    Text("Loading preview…", style = MaterialTheme.typography.bodySmall)
                                }
                                is PreviewUiState.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                is PreviewUiState.Loaded -> when (val result = state.result) {
                                    is FridaStoragePreviewResult.Text -> TextContentPreview(result)
                                    is FridaStoragePreviewResult.Hex -> HexContentPreview(result)
                                    is FridaStoragePreviewResult.Image -> ImageContentPreview(result, revealed = previewImageRevealed, onReveal = { previewImageRevealed = true })
                                    is FridaStoragePreviewResult.DatabaseSchema -> DatabaseSchemaPreview(result, onOpenTable = { openTable(it, 0, 0, listOf(0), "") })
                                    is FridaStoragePreviewResult.DatabaseRows -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (exportDialog) {
        val entries = snapshot?.entries.orEmpty()
        val selectableEntries = entries.filter {
            it.kind == "preference" || it.sizeBytes <= FridaStorageExportRequest.MAX_FILE_BYTES
        }
        val selectedCount = selectableEntries.count { it.id in selectedExportIds }
        AlertDialog(
            onDismissRequest = { if (!exporting) exportDialog = false },
            title = { Text("Export items") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selectableEntries.isNotEmpty() && selectedCount == selectableEntries.size,
                            onCheckedChange = {
                                selectedExportIds = if (it) selectableEntries.map(FridaStorageEntry::id).toSet() else emptySet()
                            },
                        )
                        Text(selectedCount.toString() + " selected")
                    }
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(entries, key = { it.id }) { entry ->
                            val selectable = entry.kind == "preference" || entry.sizeBytes <= FridaStorageExportRequest.MAX_FILE_BYTES
                            val checked = entry.id in selectedExportIds
                            val label = if (entry.kind == "preference") entry.store + " · " + entry.key else entry.name
                            val detail = if (entry.kind == "preference") "Preference · " + entry.valueType
                                else entry.kind + " · " + formatSize(entry.sizeBytes)
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = selectable) {
                                    selectedExportIds = if (checked) selectedExportIds - entry.id else selectedExportIds + entry.id
                                    exportError = null
                                    exportMessage = null
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    enabled = selectable,
                                    onCheckedChange = {
                                        selectedExportIds = if (it) selectedExportIds + entry.id else selectedExportIds - entry.id
                                        exportError = null
                                        exportMessage = null
                                    },
                                )
                                Column(Modifier.weight(1f).padding(vertical = Spacing.xs)) {
                                    Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    Text(
                                        if (selectable) detail else detail + " · over 100 MiB limit",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    exportProgress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    exportError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                }
            },
            confirmButton = {
                Button(onClick = ::exportSelectedItems, enabled = !exporting && selectedCount > 0) {
                    Text(if (exporting) "Exporting…" else "Export")
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!exporting) exportDialog = false }) { Text(if (exportMessage != null) "Done" else "Cancel") }
            },
        )
    }
}

@Composable
private fun StorageEntryCard(entry: FridaStorageEntry, onView: () -> Unit) {
    var revealed by remember(entry.id) { mutableStateOf(false) }
    val isViewable = entry.kind == "file" || entry.kind == "image" || entry.kind == "database"
    BaseCard(modifier = if (isViewable) Modifier.clickable(onClick = onView) else Modifier) {
        val heading = if (entry.kind == "preference") "${entry.store} · ${entry.key}" else entry.name
        Text(heading, style = MaterialTheme.typography.titleSmall, maxLines = 2)
        val detail = if (entry.kind == "preference") "Shared preference · ${entry.valueType}" else "${entry.root} · ${entry.path}"
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (entry.kind == "preference") {
            val masked = shouldMask(entry.key, entry.value)
            val value = when {
                entry.valueTruncated -> "${entry.value}… [value truncated]"
                masked && !revealed -> "••••••••  [masked]"
                else -> entry.value
            }
            Text(value, modifier = Modifier.padding(top = Spacing.xs), style = MonoCodeStyle, maxLines = 8)
            if (masked) {
                TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Mask value" else "Reveal value") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatSize(entry.sizeBytes), style = MaterialTheme.typography.labelMedium)
                if (isViewable) {
                    Spacer(Modifier.width(Spacing.sm))
                    Text("View →", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            if (entry.kind == "image") {
                Text("Tap to load a bounded, downsampled preview.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TextContentPreview(result: FridaStoragePreviewResult.Text) {
    Column {
        Text(result.text, style = MonoCodeStyle)
        if (result.truncated) {
            Spacer(Modifier.height(Spacing.xs))
            Text("Preview truncated at ${result.byteLength} bytes.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun HexContentPreview(result: FridaStoragePreviewResult.Hex) {
    Column {
        if (result.fellBackFromText) {
            Text("This file did not decode as text -- showing a bounded hex preview instead.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Spacing.xs))
        }
        Text(result.hex.ifBlank { "(empty file)" }, style = MonoCodeStyle)
        if (result.truncated) {
            Spacer(Modifier.height(Spacing.xs))
            Text("Hex preview bounded to ${result.byteLength} bytes.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun ImageContentPreview(result: FridaStoragePreviewResult.Image, revealed: Boolean, onReveal: () -> Unit) {
    Column {
        if (!revealed) {
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .clickable(onClick = onReveal),
                contentAlignment = Alignment.Center,
            ) {
                Text("Tap to reveal image", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val bitmap = remember(result.base64Jpeg) {
                runCatching {
                    val bytes = Base64.decode(result.base64Jpeg, Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
            if (bitmap != null) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Preview image")
            } else {
                Text("This image could not be decoded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "${result.width}×${result.height}px" + if (result.downsampled) " (downsampled from ${result.originalWidth}×${result.originalHeight})" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DatabaseSchemaPreview(result: FridaStoragePreviewResult.DatabaseSchema, onOpenTable: (String) -> Unit) {
    Column {
        if (result.tables.isEmpty()) {
            Text("No user tables or views found in this database.", style = MaterialTheme.typography.bodySmall)
        }
        result.tables.forEach { table ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenTable(table.name) }
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .padding(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(table.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(table.objectType.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
                Text(
                    table.columns.joinToString { it.name + if (it.declaredType.isBlank()) "" else " ${it.declaredType}" } +
                        if (table.columnsTruncated) " · …" else "",
                    style = MonoCodeStyle,
                    maxLines = 3,
                )
                Text("${table.columns.size}${if (table.columnsTruncated) "+" else ""} columns · tap to browse rows", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (result.truncated) {
            Text("Schema list reached a safety limit. Additional tables or columns may be hidden.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun DatabaseRowsPreview(
    modifier: Modifier = Modifier,
    table: String,
    schema: FridaStoragePreviewResult.DatabaseTable?,
    columns: List<String>,
    rows: List<List<FridaStoragePreviewResult.DatabaseCell>>,
    offset: Int,
    pageIndex: Int,
    rowCount: Long,
    searchQuery: String,
    columnsTruncated: Boolean,
    revealSensitive: Boolean,
    onToggleSensitive: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onPageChange: (Int) -> Unit,
    onRefresh: () -> Unit,
) {
    var searchText by remember(table) { mutableStateOf(searchQuery) }
    LaunchedEffect(table, searchText, searchQuery) {
        if (searchText != searchQuery) {
            delay(350)
            if (searchText != searchQuery) onSearchQueryChange(searchText)
        }
    }

    val hasSensitiveValues = rows.any { row ->
        row.withIndex().any { (index, cell) ->
            shouldMask(columns.getOrNull(index).orEmpty(), databaseCellText(cell))
        }
    }
    val displayRows = rows.map { row ->
        columns.mapIndexed { columnIndex, column ->
            val cell = row.getOrNull(columnIndex) ?: return@mapIndexed null
            val value = databaseCellText(cell)
            val sensitive = shouldMask(column, value)
            when {
                sensitive && !revealSensitive -> "•••••••• [masked]"
                cell.storageType == "NULL" -> null
                else -> value
            }
        }
    }
    // Clipboard actions in the reusable viewer export only this bounded page, and sensitive
    // columns stay masked there even after the user temporarily reveals them on screen.
    val exportRows = rows.map { row ->
        columns.mapIndexed { columnIndex, column ->
            val cell = row.getOrNull(columnIndex) ?: return@mapIndexed null
            val value = databaseCellText(cell)
            when {
                shouldMask(column, value) -> "•••••••• [masked]"
                cell.storageType == "NULL" -> null
                else -> value
            }
        }
    }
    val pageResult = TablePageResult(
        tableName = table,
        columns = columns.mapIndexed { index, name ->
            val metadata = schema?.columns?.getOrNull(index)
            ColumnInfo(
                cid = index,
                name = name,
                type = metadata?.declaredType.orEmpty(),
                notNull = metadata?.notNull ?: false,
                isPrimaryKey = (metadata?.primaryKeyPosition ?: 0) > 0,
            )
        },
        rows = displayRows,
        totalRowCount = rowCount,
        page = pageIndex,
        pageSize = DATABASE_PAGE_SIZE,
    )

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(table, style = MaterialTheme.typography.titleMedium)
        Text(
            "Read-only live data · pages can reflect different moments while the target is writing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (schema?.columnsTruncated == true || columnsTruncated) {
            Text("Column list is limited for this preview.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
        if (hasSensitiveValues) {
            TextButton(onClick = onToggleSensitive) {
                Text(if (revealSensitive) "Mask sensitive values" else "Reveal sensitive values")
            }
        }
        TableBrowserView(
            pageResult = pageResult,
            searchQuery = searchText,
            onSearchQueryChange = { searchText = it.take(160) },
            onPageChange = onPageChange,
            onRefresh = onRefresh,
            onExportCsv = { exportRows.toCsv(columns) },
            onExportJson = { exportRows.toJson(columns) },
            modifier = Modifier.weight(1f),
        )
    }
}

private fun List<List<String?>>.toCsv(columns: List<String>): String {
    val rows = this
    return buildList {
        add(columns.joinToString(",") { csvField(it) })
        rows.forEach { row -> add(row.joinToString(",") { value -> csvField(value.orEmpty()) }) }
    }.joinToString("\n")
}

private fun List<List<String?>>.toJson(columns: List<String>): String {
    val result = JSONArray()
    forEach { row ->
        val item = JSONObject()
        columns.forEachIndexed { index, column -> item.put(column, row.getOrNull(index) ?: JSONObject.NULL) }
        result.put(item)
    }
    return result.toString(2)
}

private fun csvField(value: String): String = "\"${value.replace("\"", "\"\"")}\""

@Composable
private fun DatabasePageControls(pageIndex: Int, offset: Int, rowCount: Int, hasMore: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onPrevious, enabled = pageIndex > 0) { Text("← Previous") }
        val range = if (rowCount == 0) "No rows" else "Rows ${offset + 1}–${offset + rowCount}"
        Text("Page ${pageIndex + 1} · $range", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onNext, enabled = hasMore) { Text("Next →") }
    }
}

private fun databaseCellText(cell: FridaStoragePreviewResult.DatabaseCell): String = when {
    cell.storageType == "BLOB" -> "[BLOB · ${cell.blobBytes ?: 0} bytes; contents not loaded]"
    cell.value == null -> "NULL"
    else -> cell.value.toString() + if (cell.truncated) "… [value preview truncated]" else ""
}

private fun shouldMask(key: String, value: String): Boolean =
    SENSITIVE_NAME.containsMatchIn(key) || EMAIL.containsMatchIn(value) ||
        JWT.containsMatchIn(value) || LONG_SECRET.containsMatchIn(value)

private val SENSITIVE_NAME = Regex("(?i)(password|passwd|token|secret|auth|credential|session|cookie|email|phone|address|account|api.?key)")
private val EMAIL = Regex("\\b[\\w.+-]{1,64}@[\\w.-]{1,190}\\.[A-Za-z]{2,24}\\b")
private val JWT = Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b")
private val LONG_SECRET = Regex("\\b(?:[A-Fa-f0-9]{40,}|[A-Za-z0-9_/-]{48,})\\b")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault())

private fun formatCaptureTime(epochMs: Long): String = runCatching { TIME_FORMAT.format(Instant.ofEpochMilli(epochMs)) }.getOrDefault("unknown time")

private fun formatSize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "%.1f KiB".format(bytes / 1_024.0)
    else -> "%.1f MiB".format(bytes / 1_048_576.0)
}
