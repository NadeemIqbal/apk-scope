package com.nadeem.apkscope.ui.screens.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nadeem.apkscope.domain.storage.StorageCategory
import com.nadeem.apkscope.domain.storage.StorageCategorySummary
import com.nadeem.apkscope.domain.storage.formatStorageBytes
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.DestructiveActionButton
import com.nadeem.apkscope.ui.components.PrimaryActionButton
import com.nadeem.apkscope.ui.components.SectionHeader
import com.nadeem.apkscope.ui.components.SecondaryActionButton
import com.nadeem.apkscope.ui.components.StickyActionBar
import com.nadeem.apkscope.ui.theme.Spacing
import com.nadeem.apkscope.ui.theme.extendedColors

@Composable
fun StorageScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StorageViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingDeletion by remember { mutableStateOf<StorageCategory?>(null) }
    val summary = state.summary
    val hasData = summary?.managedBytes?.let { it > 0L } == true
    val hasActiveSandboxSession = summary?.hasActiveSandboxSession ?: false
    // Row-level cleanup is safe for completed data and is explicitly confirmed by the user.
    // Delete-all remains blocked while a live sandbox session exists.
    val canDelete = state.deleting == null
    val canDeleteAll = canDelete && hasData && !hasActiveSandboxSession

    if (pendingDeletion != null) {
        val category = pendingDeletion!!
        val isAll = category == StorageCategory.ALL
        AlertDialog(
            onDismissRequest = { if (state.deleting == null) pendingDeletion = null },
            title = { Text(if (isAll) "Delete all saved analysis data?" else "Delete ${category.displayName()}?") },
            text = {
                Text(
                    if (isAll) {
                        "This removes imported APKs, static analysis history, saved dynamic evidence, reports, and security audits from this Personal Profile. The Work Profile is not removed."
                    } else {
                        category.confirmationText() + if (summary?.hasActiveSandboxSession == true) {
                            " Any active sandbox session records are kept until that session ends."
                        } else {
                            ""
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeletion = null
                        viewModel.delete(category)
                    },
                    enabled = canDelete,
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }, enabled = state.deleting == null) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = { AppTopBar(title = "Clear memory", eyebrow = "PERSONAL", onBack = onBack) },
        bottomBar = {
            StickyActionBar {
                DestructiveActionButton(
                    text = if (state.deleting == StorageCategory.ALL) "Deleting…" else "Delete all saved data",
                    icon = Icons.Filled.DeleteForever,
                    onClick = { pendingDeletion = StorageCategory.ALL },
                    enabled = canDeleteAll,
                    loading = state.deleting == StorageCategory.ALL,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.base).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.base),
        ) {
            Spacer(Modifier.height(Spacing.sm))
            BaseCard(containerColor = if (summary?.isLowStorage == true) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Icon(
                        if (summary?.isLowStorage == true) Icons.Filled.Warning else Icons.Filled.Storage,
                        contentDescription = null,
                        tint = if (summary?.isLowStorage == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Internal storage", style = MaterialTheme.typography.titleMedium)
                        if (summary == null || state.isLoading) {
                            Text("Reading storage…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(
                                "${formatStorageBytes(summary.availableBytes)} available",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (summary.isLowStorage) "Storage is running low. Review saved analysis data below." else "Review saved analysis data whenever you need to reclaim space.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            summary?.let {
                SectionHeader(title = "Stored APKs")
                StorageCategoryCard(
                    summary = it.apks,
                    enabled = canDelete,
                    deleting = state.deleting == StorageCategory.APKS,
                    onDelete = { pendingDeletion = StorageCategory.APKS },
                )

                SectionHeader(title = "Saved data")
                StorageCategoryCard(
                    summary = it.staticAnalysis,
                    enabled = canDelete,
                    deleting = state.deleting == StorageCategory.STATIC_ANALYSIS,
                    onDelete = { pendingDeletion = StorageCategory.STATIC_ANALYSIS },
                )
                StorageCategoryCard(
                    summary = it.dynamicAnalysis,
                    enabled = canDelete,
                    deleting = state.deleting == StorageCategory.DYNAMIC_ANALYSIS,
                    onDelete = { pendingDeletion = StorageCategory.DYNAMIC_ANALYSIS },
                    footnote = "Database-backed evidence size is estimated from saved rows.",
                )
            } ?: if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                Spacer(Modifier.height(Spacing.xs))
            }

            state.error?.let { error ->
                BaseCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(error, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    SecondaryActionButton(text = "Retry", onClick = viewModel::refresh)
                }
            }

            if (summary?.hasActiveSandboxSession == true) {
                BaseCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Row cleanup is available now. Finish the active sandbox session before using Delete all; its live records are protected.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

@Composable
private fun StorageCategoryCard(
    summary: StorageCategorySummary,
    enabled: Boolean,
    deleting: Boolean,
    onDelete: () -> Unit,
    footnote: String? = null,
) {
    BaseCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Column(Modifier.weight(1f)) {
                Text(summary.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${summary.count} ${summary.itemLabel} · ${formatStorageBytes(summary.bytes)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (!summary.hasData) {
                    Text("Nothing saved", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                footnote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            SecondaryActionButton(
                text = if (deleting) "Deleting…" else "Delete",
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(0.32f),
                icon = Icons.Filled.DeleteForever,
                enabled = enabled && summary.hasData,
            )
        }
    }
}

private fun StorageCategory.displayName(): String = when (this) {
    StorageCategory.APKS -> "stored APKs"
    StorageCategory.STATIC_ANALYSIS -> "static analysis data"
    StorageCategory.DYNAMIC_ANALYSIS -> "dynamic analysis data"
    StorageCategory.ALL -> "all saved data"
}

private fun StorageCategory.confirmationText(): String = when (this) {
    StorageCategory.APKS -> "This removes imported APK files only. Saved reports and analysis evidence remain available."
    StorageCategory.STATIC_ANALYSIS -> "This removes static analysis history, rich inspection data, security audits, and any sandbox evidence linked to those analyses."
    StorageCategory.DYNAMIC_ANALYSIS -> "This removes saved runtime observations, Android network evidence, dynamic sessions, and dynamic reports. Static analysis history remains."
    StorageCategory.ALL -> "This removes all saved analysis data."
}
