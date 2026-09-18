package com.nadeem.apkscope.ui.screens.staticresult

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadeem.apkscope.core.staticanalysis.ApiCategory
import com.nadeem.apkscope.core.staticanalysis.ApiFinding
import com.nadeem.apkscope.core.staticanalysis.DexDisassembler
import com.nadeem.apkscope.core.staticanalysis.DexUrlCandidate
import com.nadeem.apkscope.core.staticanalysis.DexUrlExtractor
import com.nadeem.apkscope.core.staticanalysis.SdkConfidence
import com.nadeem.apkscope.core.staticanalysis.SdkFinding
import com.nadeem.apkscope.core.staticanalysis.UrlProvenance
import com.nadeem.apkscope.ui.common.sessionViewModel
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.InfoCard
import com.nadeem.apkscope.ui.theme.MonoCodeStyle
import com.nadeem.apkscope.ui.theme.Spacing
import com.nadeem.apkscope.ui.theme.extendedColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// ==========================================
// 1. EMBEDDED URLS SCREEN
// ==========================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmbeddedUrlsDetailScreen(
    sessionId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel = sessionViewModel { StaticResultViewModel(context.applicationContext as Application, sessionId) }
    val state by viewModel.uiState.collectAsState()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedProvenance by rememberSaveable { mutableStateOf<UrlProvenance?>(null) }
    var selectedClassForSmali by rememberSaveable { mutableStateOf<String?>(null) }

    // Milestone 9 (persistence hardening): durable outcome of the most recent URL-evidence import
    // attempt for this analysis — surfaces a failed/rejected/truncated import here instead of only
    // in logcat (see UrlEvidenceImportStatusStore's own doc comment). Read once per composition of
    // this screen (typically opened fresh after a session ends and the analysis is reopened, which
    // is exactly when this matters) rather than observed as a Flow — this is a single most-recent
    // snapshot, not a live stream.
    val importStatus = remember(sessionId) {
        com.nadeem.apkscope.domain.sandbox.UrlEvidenceImportStatusStore.get(context.applicationContext, sessionId)
    }

    // Older persisted analyses may still contain documentation links collected before the
    // extractor learned to suppress static library/help URLs. Apply the same boundary at display
    // time so the current session is cleaned up without requiring a re-import.
    val urls = state.analysis?.embeddedUrls?.filterNot { candidate ->
        candidate.runtimeEvidence == null && DexUrlExtractor.isLikelyDocumentationUrl(candidate.normalizedUrl)
    } ?: emptyList()
    val filteredUrls = remember(urls, searchQuery, selectedProvenance) {
        urls.filter { item ->
            val matchesQuery = searchQuery.isBlank() ||
                    item.normalizedUrl.contains(searchQuery, ignoreCase = true) ||
                    item.host.contains(searchQuery, ignoreCase = true)
            val matchesProv = selectedProvenance == null || item.provenance == selectedProvenance
            matchesQuery && matchesProv
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = "Embedded URLs (${filteredUrls.size})",
                onBack = onBack,
                onOverflow = {},
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.base)
        ) {
            Spacer(Modifier.height(Spacing.xs))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search by URL, domain, or path") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(Spacing.xs))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                FilterChip(
                    selected = selectedProvenance == null,
                    onClick = { selectedProvenance = null },
                    label = { Text("All (${urls.size})") }
                )
                FilterChip(
                    selected = selectedProvenance == UrlProvenance.REFERENCED_BY_CODE,
                    onClick = {
                        selectedProvenance = if (selectedProvenance == UrlProvenance.REFERENCED_BY_CODE) null else UrlProvenance.REFERENCED_BY_CODE
                    },
                    label = { Text("Code Referenced (${urls.count { it.provenance == UrlProvenance.REFERENCED_BY_CODE }})") }
                )
                FilterChip(
                    selected = selectedProvenance == UrlProvenance.PRESENT_IN_DEX,
                    onClick = {
                        selectedProvenance = if (selectedProvenance == UrlProvenance.PRESENT_IN_DEX) null else UrlProvenance.PRESENT_IN_DEX
                    },
                    label = { Text("DEX String (${urls.count { it.provenance == UrlProvenance.PRESENT_IN_DEX }})") }
                )
                FilterChip(
                    selected = selectedProvenance == UrlProvenance.RUNTIME_OBSERVED,
                    onClick = {
                        selectedProvenance = if (selectedProvenance == UrlProvenance.RUNTIME_OBSERVED) null else UrlProvenance.RUNTIME_OBSERVED
                    },
                    label = { Text("Observed Runtime (${urls.count { it.provenance == UrlProvenance.RUNTIME_OBSERVED }})") }
                )
            }

            // Milestone 9 (persistence hardening): only shown when the last import is actually
            // noteworthy (an error, or a truncated export) — a routine successful import with
            // nothing new to report stays silent, matching this screen's own quiet-by-default tone.
            //
            // Milestone 9 (Pixel 8 acceptance, fifth pass, item 3): PENDING is shown here too — a
            // real, honest "started but never concluded" signal (the durable marker written before
            // the cross-profile query dispatches, only ever overwritten once the attempt actually
            // concludes) distinct from both a genuine failure and "nothing to report."
            if (importStatus != null && (importStatus.error != null || importStatus.truncated || importStatus.status == com.nadeem.apkscope.domain.sandbox.UrlEvidenceImportStatusStore.Status.PENDING)) {
                Spacer(Modifier.height(Spacing.xs))
                Surface(
                    color = if (importStatus.error != null) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(Modifier.padding(Spacing.sm)) {
                        Text(
                            when {
                                importStatus.status == com.nadeem.apkscope.domain.sandbox.UrlEvidenceImportStatusStore.Status.PENDING -> "Last evidence import did not finish"
                                importStatus.error != null -> "Last evidence import failed"
                                else -> "Last evidence import was truncated"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        if (importStatus.status == com.nadeem.apkscope.domain.sandbox.UrlEvidenceImportStatusStore.Status.PENDING) {
                            Text("It was interrupted before completing — try ending the session again, or reopen this analysis to retry.", style = MaterialTheme.typography.bodySmall)
                        }
                        importStatus.error?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "${importStatus.entryCount} entries · " +
                                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                    .format(java.util.Date(importStatus.importedAtEpochMs)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.xs))
            if (filteredUrls.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (urls.isEmpty()) "No embedded URLs discovered in DEX files."
                        else "No URLs match the current search or filters.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(filteredUrls) { item ->
                        UrlItemCard(
                            candidate = item,
                            onOpenSmali = { className -> selectedClassForSmali = className }
                        )
                    }
                }
            }
        }
    }

    selectedClassForSmali?.let { className ->
        SmaliBytecodeViewerSheet(
            sessionId = sessionId,
            className = className,
            onDismiss = { selectedClassForSmali = null }
        )
    }
}

@Composable
private fun UrlItemCard(
    candidate: DexUrlCandidate,
    onOpenSmali: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    BaseCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Surface(
                    color = when (candidate.scheme) {
                        "https", "wss" -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.extendedColors.warning.copy(alpha = 0.2f)
                    },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = candidate.scheme.uppercase(Locale.ROOT),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
                Surface(
                    color = when (candidate.provenance) {
                        UrlProvenance.RUNTIME_OBSERVED -> MaterialTheme.colorScheme.tertiaryContainer
                        UrlProvenance.REFERENCED_BY_CODE -> MaterialTheme.colorScheme.secondaryContainer
                        UrlProvenance.PRESENT_IN_DEX -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = when (candidate.provenance) {
                            UrlProvenance.RUNTIME_OBSERVED -> "EXACT RUNTIME OBSERVED"
                            UrlProvenance.REFERENCED_BY_CODE -> "CODE REFERENCED"
                            UrlProvenance.PRESENT_IN_DEX -> "DEX STRING"
                        },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (candidate.hostCorrelation != null && candidate.provenance != UrlProvenance.RUNTIME_OBSERVED) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "HOST CORRELATED",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)
                        )
                    }
                }
            }
            Text(
                candidate.dexEntry,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = candidate.normalizedUrl,
            style = MonoCodeStyle.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) 10 else 2
        )

        Spacer(Modifier.height(Spacing.xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (candidate.references.isNotEmpty()) "${candidate.references.size} code reference(s)" else "String table item only",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row {
                IconButton(onClick = { clipboardManager.setText(AnnotatedString(candidate.normalizedUrl)) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy URL", modifier = Modifier.size(16.dp))
                }
                if (candidate.references.isNotEmpty() || candidate.runtimeEvidence != null || candidate.hostCorrelation != null) {
                    Text(
                        text = if (expanded) "Hide details" else "View details",
                        style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .clickable { expanded = !expanded }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
            }
        }

        if (expanded) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = Spacing.xs),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                candidate.runtimeEvidence?.let { ev ->
                    Text("Exact Runtime Observation Evidence:", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    Text("• Session ID: ${ev.sessionId}", style = MonoCodeStyle.copy(fontSize = 11.sp))
                    Text("• Transaction ID: ${ev.transactionId}", style = MonoCodeStyle.copy(fontSize = 11.sp))
                    Text("• Captured URL: ${ev.url}", style = MonoCodeStyle.copy(fontSize = 11.sp))
                    ev.method?.let { Text("• Method: $it", style = MonoCodeStyle.copy(fontSize = 11.sp)) }
                    ev.statusCode?.let { Text("• Status Code: $it", style = MonoCodeStyle.copy(fontSize = 11.sp)) }
                    Spacer(Modifier.height(Spacing.xxs))
                }

                candidate.hostCorrelation?.let { hc ->
                    Text("Host Traffic Correlation:", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    Text("• Host Observed: ${hc.host}", style = MonoCodeStyle.copy(fontSize = 11.sp))
                    Text("• Captured Transactions on Host: ${hc.observedTransactionCount}", style = MonoCodeStyle.copy(fontSize = 11.sp))
                    hc.sampleTransactionId?.let { Text("• Sample Transaction: $it", style = MonoCodeStyle.copy(fontSize = 11.sp)) }
                    Spacer(Modifier.height(Spacing.xxs))
                }

                if (candidate.references.isNotEmpty()) {
                    Text("Code References:", style = MaterialTheme.typography.labelMedium)
                    candidate.references.forEach { ref ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ref.className,
                                    style = MonoCodeStyle.copy(fontSize = 11.sp),
                                    fontWeight = FontWeight.SemiBold
                                )
                                ref.methodName?.let { m ->
                                    Text(
                                        text = "method: $m()",
                                        style = MonoCodeStyle.copy(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(onClick = { onOpenSmali(ref.className) }) {
                                Icon(Icons.Default.Code, contentDescription = "View Smali Bytecode", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 2. DETECTED SDKS SCREEN
// ==========================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetectedSdksDetailScreen(
    sessionId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel = sessionViewModel { StaticResultViewModel(context.applicationContext as Application, sessionId) }
    val state by viewModel.uiState.collectAsState()

    var selectedConfidence by rememberSaveable { mutableStateOf<SdkConfidence?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val sdks = state.analysis?.detectedSdks ?: emptyList()
    val filteredSdks = remember(sdks, searchQuery, selectedConfidence) {
        sdks.filter { sdk ->
            val matchesQuery = searchQuery.isBlank() ||
                    sdk.sdkName.contains(searchQuery, ignoreCase = true) ||
                    sdk.category.contains(searchQuery, ignoreCase = true)
            val matchesConf = selectedConfidence == null || sdk.confidence == selectedConfidence
            matchesQuery && matchesConf
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = "Detected SDKs (${filteredSdks.size})",
                onBack = onBack,
                onOverflow = {},
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.base)
        ) {
            Spacer(Modifier.height(Spacing.xs))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search SDK by name or category") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(Spacing.xs))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                FilterChip(
                    selected = selectedConfidence == null,
                    onClick = { selectedConfidence = null },
                    label = { Text("All (${sdks.size})") }
                )
                FilterChip(
                    selected = selectedConfidence == SdkConfidence.HIGH,
                    onClick = {
                        selectedConfidence = if (selectedConfidence == SdkConfidence.HIGH) null else SdkConfidence.HIGH
                    },
                    label = { Text("Confirmed (${sdks.count { it.confidence == SdkConfidence.HIGH }})") }
                )
                FilterChip(
                    selected = selectedConfidence == SdkConfidence.MEDIUM,
                    onClick = {
                        selectedConfidence = if (selectedConfidence == SdkConfidence.MEDIUM) null else SdkConfidence.MEDIUM
                    },
                    label = { Text("Likely (${sdks.count { it.confidence == SdkConfidence.MEDIUM }})") }
                )
                FilterChip(
                    selected = selectedConfidence == SdkConfidence.LOW,
                    onClick = {
                        selectedConfidence = if (selectedConfidence == SdkConfidence.LOW) null else SdkConfidence.LOW
                    },
                    label = { Text("Possible (${sdks.count { it.confidence == SdkConfidence.LOW }})") }
                )
            }

            Spacer(Modifier.height(Spacing.xs))
            if (filteredSdks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (sdks.isEmpty()) "No third-party SDK signatures detected."
                        else "No SDKs match the selected filter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(filteredSdks) { sdk ->
                        SdkItemCard(sdk)
                    }
                }
            }
        }
    }
}

@Composable
private fun SdkItemCard(sdk: SdkFinding) {
    var expanded by remember { mutableStateOf(false) }

    BaseCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(sdk.sdkName, style = MaterialTheme.typography.titleMedium)
                Text(sdk.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(
                color = when (sdk.confidence) {
                    SdkConfidence.HIGH -> MaterialTheme.colorScheme.primaryContainer
                    SdkConfidence.MEDIUM -> MaterialTheme.extendedColors.warning.copy(alpha = 0.2f)
                    SdkConfidence.LOW -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = sdk.confidence.evidenceLabel.uppercase(Locale.US),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }

        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = sdk.rationale,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(Spacing.xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Catalog version ${sdk.catalogVersion}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (expanded) "Hide evidence" else "Show evidence (${sdk.evidence.size})",
                style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 4.dp, vertical = 6.dp)
            )
        }

        if (expanded) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = Spacing.xs),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sdk.evidence.forEach { ev ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("• ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Text(ev, style = MonoCodeStyle.copy(fontSize = 11.sp))
                    }
                }
            }
        }
    }
}

// ==========================================
// 3. API REFERENCES SCREEN
// ==========================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApiReferencesDetailScreen(
    sessionId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel = sessionViewModel { StaticResultViewModel(context.applicationContext as Application, sessionId) }
    val state by viewModel.uiState.collectAsState()

    var selectedCategory by rememberSaveable { mutableStateOf<ApiCategory?>(null) }
    var selectedInvocationOnly by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedClassForSmali by rememberSaveable { mutableStateOf<String?>(null) }

    val findings = state.analysis?.apiFindings ?: emptyList()
    val filteredFindings = remember(findings, searchQuery, selectedCategory, selectedInvocationOnly) {
        findings.filter { item ->
            val matchesQuery = searchQuery.isBlank() ||
                    item.apiName.contains(searchQuery, ignoreCase = true) ||
                    (item.callingClass?.contains(searchQuery, ignoreCase = true) == true)
            val matchesCat = selectedCategory == null || item.category == selectedCategory
            val matchesInvoc = selectedInvocationOnly == null || item.isInvocation == selectedInvocationOnly
            matchesQuery && matchesCat && matchesInvoc
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = "Security API References (${filteredFindings.size})",
                onBack = onBack,
                onOverflow = {},
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.base)
        ) {
            Spacer(Modifier.height(Spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search API or caller") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).height(56.dp),
                    singleLine = true,
                )
                IconButton(
                    onClick = { filtersExpanded = !filtersExpanded },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.FilterList,
                        contentDescription = if (filtersExpanded) "Hide filters" else "Show filters",
                        tint = if (filtersExpanded || selectedCategory != null || selectedInvocationOnly != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            if (filtersExpanded) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("All Categories (${findings.size})") },
                    )
                    ApiCategory.entries.forEach { cat ->
                        val count = findings.count { it.category == cat }
                        if (count > 0) {
                            FilterChip(
                                selected = selectedCategory == cat,
                                onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                                label = { Text("${cat.title} ($count)") },
                            )
                        }
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    FilterChip(
                        selected = selectedInvocationOnly == null,
                        onClick = { selectedInvocationOnly = null },
                        label = { Text("All Reference Types") },
                    )
                    FilterChip(
                        selected = selectedInvocationOnly == true,
                        onClick = { selectedInvocationOnly = if (selectedInvocationOnly == true) null else true },
                        label = { Text("Invocations (${findings.count { it.isInvocation }})") },
                    )
                    FilterChip(
                        selected = selectedInvocationOnly == false,
                        onClick = { selectedInvocationOnly = if (selectedInvocationOnly == false) null else false },
                        label = { Text("Reference Table (${findings.count { !it.isInvocation }})") },
                    )
                }
            } else if (selectedCategory != null || selectedInvocationOnly != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    selectedCategory?.let { category ->
                        FilterChip(
                            selected = true,
                            onClick = { selectedCategory = null },
                            label = { Text(category.title) },
                        )
                    }
                    selectedInvocationOnly?.let { invocationOnly ->
                        FilterChip(
                            selected = true,
                            onClick = { selectedInvocationOnly = null },
                            label = { Text(if (invocationOnly) "Invocations" else "Reference Table") },
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.xs))
            if (filteredFindings.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (findings.isEmpty()) "No security-relevant API references identified in DEX."
                        else "No findings match the current category or filter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(filteredFindings) { finding ->
                        ApiItemCard(
                            finding = finding,
                            onOpenSmali = { className -> selectedClassForSmali = className }
                        )
                    }
                }
            }
        }
    }

    selectedClassForSmali?.let { className ->
        SmaliBytecodeViewerSheet(
            sessionId = sessionId,
            className = className,
            onDismiss = { selectedClassForSmali = null }
        )
    }
}

@Composable
private fun ApiItemCard(
    finding: ApiFinding,
    onOpenSmali: (String) -> Unit
) {
    BaseCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = finding.category.title.uppercase(Locale.ROOT),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
            Surface(
                color = if (finding.isInvocation) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = if (finding.isInvocation) "INVOCATION INSTRUCTION" else "METHOD REF TABLE",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = finding.apiName,
            style = MonoCodeStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = finding.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(Spacing.xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (finding.callingClass != null) {
                    Text(
                        text = "Caller: ${finding.callingClass}",
                        style = MonoCodeStyle.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    finding.callingMethod?.let { m ->
                        Text(
                            text = "in method: $m() [${finding.dexEntry}]",
                            style = MonoCodeStyle.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        text = "DEX reference entry in ${finding.dexEntry}",
                        style = MonoCodeStyle.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val callerClass = finding.callingClass
            if (callerClass != null) {
                IconButton(onClick = { onOpenSmali(callerClass) }) {
                    Icon(Icons.Default.Code, contentDescription = "View Smali Bytecode", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// ==========================================
// 4. REUSABLE SMALI BYTECODE VIEWER MODAL
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmaliBytecodeViewerSheet(
    sessionId: String,
    className: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var smaliCode by rememberSaveable { mutableStateOf<String?>(null) }
    var dexSource by rememberSaveable { mutableStateOf<String?>(null) }
    var isLoading by rememberSaveable { mutableStateOf(true) }
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(className, sessionId) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val candidateFiles = listOf(
                File(context.filesDir, "analysis/$sessionId.apk"),
                File(context.cacheDir, "analysis/$sessionId.apk"),
                File(context.filesDir, "fixtures/fixture-debug.apk"),
            )
            val apkFile = candidateFiles.firstOrNull { it.exists() && it.canRead() }
                ?: File(context.packageCodePath)

            val result = if (apkFile.exists() && apkFile.canRead()) {
                DexDisassembler.disassembleClass(apkFile, className)
            } else {
                DexDisassembler.DisassemblyResult.Error("APK archive not found on device storage")
            }

            withContext(Dispatchers.Main) {
                when (result) {
                    is DexDisassembler.DisassemblyResult.Success -> {
                        smaliCode = result.smaliCode
                        dexSource = result.dexSource
                    }
                    is DexDisassembler.DisassemblyResult.NotFound -> {
                        smaliCode = "# Class not found in DEX entries:\n# ${result.reason}"
                        dexSource = null
                    }
                    is DexDisassembler.DisassemblyResult.Error -> {
                        smaliCode = "# DEX Disassembly Failed:\n# ${result.message}"
                        dexSource = null
                    }
                }
                isLoading = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(Spacing.base)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = className.substringAfterLast('.'),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = dexSource?.let { "$className [$it]" } ?: className,
                        style = MonoCodeStyle.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    smaliCode?.let { code ->
                        IconButton(onClick = { clipboardManager.setText(AnnotatedString(code)) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Smali")
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.xs))

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val code = smaliCode ?: "# No bytecode available"
                val lines = code.lines()
                val vScrollState = rememberScrollState()
                val hScrollState = rememberScrollState()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .padding(Spacing.xs)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(vScrollState)
                    ) {
                        // Line numbers
                        Column(
                            modifier = Modifier
                                .width(40.dp)
                                .padding(vertical = 2.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            lines.forEachIndexed { idx, _ ->
                                Text(
                                    text = "%3d".format(idx + 1),
                                    style = MonoCodeStyle.copy(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
                                    lineHeight = 18.sp
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height((lines.size * 18).dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        )

                        // Code body
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(hScrollState)
                                .padding(start = Spacing.xs)
                        ) {
                            Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                lines.forEach { line ->
                                    Text(
                                        text = line.ifEmpty { " " },
                                        style = MonoCodeStyle.copy(
                                            fontSize = 11.sp,
                                            lineHeight = 18.sp,
                                            color = when {
                                                line.trimStart().startsWith("#") -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                                line.trimStart().startsWith(".") -> MaterialTheme.colorScheme.primary
                                                line.trimStart().startsWith("invoke-") -> MaterialTheme.extendedColors.warning
                                                line.contains("const-string") -> MaterialTheme.colorScheme.secondary
                                                else -> MaterialTheme.colorScheme.onSurface
                                            }
                                        ),
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
