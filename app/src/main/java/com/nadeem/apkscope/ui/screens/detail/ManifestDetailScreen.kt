package com.nadeem.apkscope.ui.screens.detail

import android.app.Application
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadeem.apkscope.core.staticanalysis.BinaryXmlParser
import com.nadeem.apkscope.domain.PersistedAnalysis
import com.nadeem.apkscope.ui.common.sessionViewModel
import com.nadeem.apkscope.ui.components.Badge
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.EmptyState
import com.nadeem.apkscope.ui.components.SectionHeader
import com.nadeem.apkscope.ui.screens.staticresult.StaticResultViewModel
import com.nadeem.apkscope.ui.theme.MonoCodeStyle
import com.nadeem.apkscope.ui.theme.Radii
import com.nadeem.apkscope.ui.theme.Spacing
import com.nadeem.apkscope.ui.theme.extendedColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * Detailed Manifest & Configuration screen with two distinct tabs:
 * 1. Configuration & Metadata: Grouped key-value list of critical manifest entries
 *    (API keys, meta-data, network configuration, security flags, SDK info, hardware features).
 * 2. Raw Manifest XML: Decompiled, formatted, indented AndroidManifest.xml with line numbers,
 *    search filter, and copy-all action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManifestDetailScreen(
    sessionId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val viewModel = sessionViewModel { StaticResultViewModel(context.applicationContext as Application, sessionId) }
    val state by viewModel.uiState.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var isLoading by rememberSaveable { mutableStateOf(true) }
    var parseResult by remember { mutableStateOf<BinaryXmlParser.ManifestParseResult?>(null) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }

    // Decompile and extract manifest data from APK on IO dispatcher
    LaunchedEffect(sessionId) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val candidateFiles = listOf(
                File(context.filesDir, "analysis/$sessionId.apk"),
                File(context.cacheDir, "analysis/$sessionId.apk"),
                File(context.filesDir, "fixtures/fixture-debug.apk"),
            )
            val apkFile = candidateFiles.firstOrNull { it.exists() && it.canRead() }
                ?: File(context.packageCodePath)

            try {
                if (apkFile.exists() && apkFile.canRead()) {
                    ZipFile(apkFile).use { zip ->
                        val entry = zip.getEntry("AndroidManifest.xml")
                        if (entry != null) {
                            val res = zip.getInputStream(entry).use { BinaryXmlParser.parseManifestFull(it) }
                            withContext(Dispatchers.Main) {
                                parseResult = res
                                isLoading = false
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                errorMessage = "AndroidManifest.xml not found in APK archive"
                                isLoading = false
                            }
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        errorMessage = "APK file not found on device storage"
                        isLoading = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = "Failed to parse manifest: ${e.message}"
                    isLoading = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "AndroidManifest.xml",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    val rawXml = parseResult?.rawXml
                    if (!rawXml.isNullOrBlank()) {
                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(rawXml))
                            Toast.makeText(context, "Full manifest XML copied to clipboard", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Copy full XML",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize(),
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // Tab Selector: Configuration Key-Value vs Raw XML
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Configuration & Keys", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal)
                        }
                    },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("Raw Manifest XML", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal)
                        }
                    },
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "Decompiling AndroidManifest.xml...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (parseResult == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Filled.Description,
                        title = "Manifest Unavailable",
                        description = errorMessage ?: "Could not load AndroidManifest.xml from analysis session.",
                    )
                }
            } else {
                val result = parseResult!!
                val analysis = state.analysis

                when (selectedTab) {
                    0 -> ManifestConfigTab(
                        config = result.config,
                        fallbackAnalysis = analysis,
                        onCopy = { text, label ->
                            clipboardManager.setText(AnnotatedString(text))
                            Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                    )
                    1 -> ManifestRawXmlTab(
                        rawXml = result.rawXml,
                        onCopy = { text ->
                            clipboardManager.setText(AnnotatedString(text))
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Tab 1: Grouped key-value list of all configuration and metadata.
 */
@Composable
private fun ManifestConfigTab(
    config: BinaryXmlParser.ManifestConfig,
    fallbackAnalysis: PersistedAnalysis?,
    onCopy: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val effectivePkg = config.packageName ?: fallbackAnalysis?.packageName ?: "Unknown"
    val effectiveVerCode = config.versionCode?.toString() ?: fallbackAnalysis?.versionCode?.toString() ?: "Unknown"
    val effectiveVerName = config.versionName ?: fallbackAnalysis?.versionName ?: "Unknown"
    val effectiveMinSdk = config.minSdkVersion ?: fallbackAnalysis?.minSdkVersion ?: -1
    val effectiveTargetSdk = config.targetSdkVersion ?: fallbackAnalysis?.targetSdkVersion ?: -1

    val metaDataFiltered = remember(config.metaData, searchQuery) {
        if (searchQuery.isBlank()) config.metaData
        else config.metaData.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                (it.value ?: "").contains(searchQuery, ignoreCase = true) ||
                (it.parentTag ?: "").contains(searchQuery, ignoreCase = true)
        }
    }

    val featuresFiltered = remember(config.features, searchQuery) {
        if (searchQuery.isBlank()) config.features
        else config.features.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item {
            Spacer(Modifier.height(Spacing.xs))
            // Search / filter bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search keys, metadata, network settings...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(Radii.md),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        }

        // Section 1: API Keys & Meta-Data (<meta-data>)
        item {
            SectionHeader(
                title = "API Keys & Meta-Data (${metaDataFiltered.size})",
            )
        }

        if (metaDataFiltered.isEmpty()) {
            item {
                BaseCard {
                    Text(
                        text = if (searchQuery.isBlank()) "No <meta-data> declarations found in manifest."
                        else "No metadata entries matching \"$searchQuery\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(metaDataFiltered) { meta ->
                MetaDataItemCard(meta = meta, onCopy = onCopy)
            }
        }

        // Section 2: Network Security Configuration
        item {
            SectionHeader(title = "Network Security Configuration")
        }

        item {
            BaseCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    // Uses Cleartext Traffic
                    val isCleartext = config.usesCleartextTraffic
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "android:usesCleartextTraffic",
                                style = MonoCodeStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (isCleartext == true) {
                                    "Plaintext HTTP enabled. Insecure traffic allowed without TLS encryption."
                                } else if (isCleartext == false) {
                                    "Plaintext HTTP blocked. All network traffic must use HTTPS/TLS."
                                } else {
                                    "Default platform policy (API 28+ requires HTTPS, earlier permits HTTP)."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isCleartext == true) {
                            Badge(text = "HTTP ALLOWED", color = MaterialTheme.extendedColors.warning)
                        } else if (isCleartext == false) {
                            Badge(text = "HTTPS ENFORCED", color = MaterialTheme.colorScheme.tertiary)
                        } else {
                            Badge(text = "DEFAULT", color = MaterialTheme.colorScheme.outline)
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Network Security Config
                    val netConfig = config.networkSecurityConfig
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "android:networkSecurityConfig",
                                style = MonoCodeStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (netConfig != null) {
                                    "Custom network security config declared ($netConfig). Can define pin sets, cleartext domains, and user certificate trust."
                                } else {
                                    "Not configured. Uses standard Android CA certificate store."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (netConfig != null) {
                            Badge(text = "CUSTOM CONFIG", color = MaterialTheme.colorScheme.primary)
                        } else {
                            Badge(text = "NONE", color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }

        // Section 3: Security & Sandbox Flags
        item {
            SectionHeader(title = "Security & Sandbox Flags")
        }

        item {
            BaseCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    // Debuggable
                    val isDebug = config.debuggable ?: fallbackAnalysis?.debuggable
                    FlagItemRow(
                        name = "android:debuggable",
                        value = isDebug?.toString() ?: "false",
                        isWarning = isDebug == true,
                        warningText = "DEBUGGABLE",
                        safeText = "RELEASE",
                        explanation = if (isDebug == true) "High Risk: Debugger can be attached to dump memory, bypass checks, and read variables."
                        else "Secure: Debugger cannot be attached in standard execution.",
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // AllowBackup
                    val isBackup = config.allowBackup
                    FlagItemRow(
                        name = "android:allowBackup",
                        value = isBackup?.toString() ?: "true (default)",
                        isWarning = isBackup == true || isBackup == null,
                        warningText = "BACKUP ENABLED",
                        safeText = "DISABLED",
                        explanation = if (isBackup != false) "Warning: Application data can be extracted using 'adb backup' unless explicitly disabled."
                        else "Safe: Android backup manager does not copy application private sandbox files.",
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // TestOnly
                    FlagItemRow(
                        name = "android:testOnly",
                        value = config.testOnly?.toString() ?: "false",
                        isWarning = config.testOnly == true,
                        warningText = "TEST ONLY",
                        safeText = "PRODUCTION",
                        explanation = if (config.testOnly == true) "May only be installed via ADB and not intended for production distribution."
                        else "Standard installation candidate.",
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Extract Native Libs
                    FlagItemRow(
                        name = "android:extractNativeLibs",
                        value = config.extractNativeLibs?.toString() ?: "Unspecified",
                        isWarning = false,
                        warningText = "EXTRACTED",
                        safeText = "DIRECT MMAP",
                        explanation = if (config.extractNativeLibs == false) "Native .so libraries are memory-mapped directly from APK without disk extraction."
                        else "Native libraries may be extracted to filesystem upon installation.",
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Request Legacy External Storage
                    FlagItemRow(
                        name = "android:requestLegacyExternalStorage",
                        value = config.requestLegacyExternalStorage?.toString() ?: "false",
                        isWarning = config.requestLegacyExternalStorage == true,
                        warningText = "LEGACY STORAGE",
                        safeText = "SCOPED STORAGE",
                        explanation = if (config.requestLegacyExternalStorage == true) "Requests bypass of Scoped Storage on Android 10+."
                        else "Enforces scoped storage privacy boundaries.",
                    )

                    val fullBackup = config.fullBackupContent
                    if (!fullBackup.isNullOrBlank()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        KeyValueDisplayRow(label = "android:fullBackupContent", value = fullBackup)
                    }

                    val extractionRules = config.dataExtractionRules
                    if (!extractionRules.isNullOrBlank()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        KeyValueDisplayRow(label = "android:dataExtractionRules", value = extractionRules)
                    }
                }
            }
        }

        // Section 4: Package & SDK Specifications
        item {
            SectionHeader(title = "Package & SDK Specifications")
        }

        item {
            BaseCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    KeyValueDisplayRow(
                        label = "Package Name",
                        value = effectivePkg,
                        onCopy = { onCopy(effectivePkg, "Package name") },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    KeyValueDisplayRow(
                        label = "Version Code",
                        value = effectiveVerCode,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    KeyValueDisplayRow(
                        label = "Version Name",
                        value = effectiveVerName,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    KeyValueDisplayRow(
                        label = "Target SDK Version",
                        value = if (effectiveTargetSdk > 0) "API $effectiveTargetSdk" else "Unspecified",
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    KeyValueDisplayRow(
                        label = "Minimum SDK Version",
                        value = if (effectiveMinSdk > 0) "API $effectiveMinSdk" else "Unspecified",
                    )
                    if (config.compileSdkVersion != null) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        KeyValueDisplayRow(
                            label = "Compile SDK Version",
                            value = "API ${config.compileSdkVersion}",
                        )
                    }
                    val sharedUid = config.sharedUserId
                    if (!sharedUid.isNullOrBlank()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        KeyValueDisplayRow(
                            label = "Shared User ID",
                            value = sharedUid,
                            badgeText = "SHARED UID",
                            isWarning = true,
                            onCopy = { onCopy(sharedUid, "Shared User ID") },
                        )
                    }
                }
            }
        }

        // Section 5: Hardware & Device Features (<uses-feature>)
        if (featuresFiltered.isNotEmpty()) {
            item {
                SectionHeader(title = "Hardware & Device Features (${featuresFiltered.size})")
            }

            items(featuresFiltered) { feat ->
                BaseCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = feat.name.ifBlank { "OpenGL ES ${feat.glEsVersion}" },
                                style = MonoCodeStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (feat.glEsVersion != null && feat.name.isNotBlank()) {
                                Text(
                                    text = "glEsVersion: ${feat.glEsVersion}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (feat.required) {
                            Badge(text = "REQUIRED", color = MaterialTheme.colorScheme.primary)
                        } else {
                            Badge(text = "OPTIONAL", color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

/**
 * Tab 2: Full decompiled, formatted AndroidManifest.xml with line numbers,
 * interactive line search, and copy-all.
 */
@Composable
private fun ManifestRawXmlTab(
    rawXml: String,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val lines = remember(rawXml) { rawXml.lines() }

    val matchedLineIndices = remember(lines, searchQuery) {
        if (searchQuery.isBlank()) emptySet()
        else lines.mapIndexedNotNull { index, line ->
            if (line.contains(searchQuery, ignoreCase = true)) index else null
        }.toSet()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Spacer(Modifier.height(Spacing.xs))

        // Search & stats header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Filter XML tags or attributes...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search XML",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(Radii.md),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )

            IconButton(
                onClick = { onCopy(rawXml) },
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(Radii.md))
                    .size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy XML",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Summary bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${lines.size} lines",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (searchQuery.isNotBlank()) {
                Text(
                    text = "${matchedLineIndices.size} matches found",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Scrollable code container with line numbers
        val vScrollState = rememberScrollState()
        val hScrollState = rememberScrollState()
        val lineHeight = 20.dp
        val totalCodeHeight = (lineHeight * lines.size).coerceAtLeast(200.dp) + Spacing.xs * 2

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = RoundedCornerShape(Radii.md),
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(Radii.md),
                )
                .padding(vertical = Spacing.sm),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(vScrollState),
            ) {
                // Pinned line number gutter
                Column(
                    modifier = Modifier
                        .width(44.dp)
                        .padding(vertical = Spacing.xs),
                    horizontalAlignment = Alignment.End,
                ) {
                    lines.forEachIndexed { index, _ ->
                        val isMatched = matchedLineIndices.contains(index)
                        val lineNum = index + 1
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(lineHeight)
                                .background(
                                    if (isMatched) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else Color.Transparent,
                                )
                                .padding(end = Spacing.xs),
                            contentAlignment = androidx.compose.ui.Alignment.CenterEnd,
                        ) {
                            Text(
                                text = "%4d".format(lineNum),
                                style = MonoCodeStyle.copy(
                                    fontSize = 11.sp,
                                    lineHeight = 20.sp,
                                    color = if (isMatched) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                    fontWeight = if (isMatched) FontWeight.Bold else FontWeight.Normal,
                                ),
                            )
                        }
                    }
                }

                // Vertical divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(totalCodeHeight)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                )

                // Horizontally scrollable code block
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = Spacing.sm, end = Spacing.md)
                        .horizontalScroll(hScrollState),
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = Spacing.xs),
                    ) {
                        lines.forEachIndexed { index, line ->
                            val isMatched = matchedLineIndices.contains(index)
                            Box(
                                modifier = Modifier
                                    .height(lineHeight)
                                    .background(
                                        if (isMatched) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        else Color.Transparent,
                                    ),
                                contentAlignment = androidx.compose.ui.Alignment.CenterStart,
                            ) {
                                Text(
                                    text = line.ifEmpty { " " },
                                    style = MonoCodeStyle.copy(
                                        fontSize = 12.sp,
                                        lineHeight = 20.sp,
                                        color = when {
                                            line.trimStart().startsWith("<!--") -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            line.trimStart().startsWith("</") || line.trimStart().startsWith("<") -> MaterialTheme.colorScheme.primary
                                            line.contains("=\"") -> MaterialTheme.colorScheme.onSurface
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                    ),
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Card for individual <meta-data> key-value item.
 */
@Composable
private fun MetaDataItemCard(
    meta: BinaryXmlParser.ManifestMetaData,
    onCopy: (String, String) -> Unit,
) {
    val isKeyOrSecret = remember(meta.name) {
        val lower = meta.name.lowercase()
        lower.contains("key") || lower.contains("token") || lower.contains("secret") ||
            lower.contains("api") || lower.contains("id")
    }

    val badgeCategory = remember(meta.name) {
        val lower = meta.name.lowercase()
        when {
            lower.contains("geo") || lower.contains("map") -> "MAPS KEY"
            lower.contains("admob") || lower.contains("ads") -> "ADMOB ID"
            lower.contains("firebase") -> "FIREBASE"
            lower.contains("facebook") -> "FACEBOOK"
            lower.contains("adjust") || lower.contains("appsflyer") -> "ANALYTICS"
            isKeyOrSecret -> "API / SECRET"
            else -> "METADATA"
        }
    }

    BaseCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = meta.name.substringAfterLast('.'),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Badge(
                    text = badgeCategory,
                    color = if (isKeyOrSecret) MaterialTheme.extendedColors.warning else MaterialTheme.colorScheme.primary,
                )
            }

            // Full qualified key name
            Text(
                text = meta.name,
                style = MonoCodeStyle.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Value + Copy Action
            val displayValue = meta.value ?: meta.resourceId ?: "(empty)"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = RoundedCornerShape(Radii.sm),
                    )
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = displayValue,
                    style = MonoCodeStyle.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (meta.value != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (displayValue != "(empty)") {
                    IconButton(
                        onClick = { onCopy(displayValue, meta.name.substringAfterLast('.')) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy value",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            // Parent tag location
            if (!meta.parentTag.isNullOrBlank() && meta.parentTag != "application") {
                Text(
                    text = "Declared inside: <${meta.parentTag}>",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * Reusable Row for Security and System Flags.
 */
@Composable
private fun FlagItemRow(
    name: String,
    value: String,
    isWarning: Boolean,
    warningText: String,
    safeText: String,
    explanation: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MonoCodeStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Spacing.xs))
        if (isWarning) {
            Badge(text = warningText, color = MaterialTheme.extendedColors.warning)
        } else {
            Badge(text = safeText, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

/**
 * Reusable Key-Value display row with optional copy and badge.
 */
@Composable
private fun KeyValueDisplayRow(
    label: String,
    value: String,
    badgeText: String? = null,
    isWarning: Boolean = false,
    onCopy: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MonoCodeStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (badgeText != null) {
                Badge(
                    text = badgeText,
                    color = if (isWarning) MaterialTheme.extendedColors.warning else MaterialTheme.colorScheme.primary,
                )
            }
            if (onCopy != null) {
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
