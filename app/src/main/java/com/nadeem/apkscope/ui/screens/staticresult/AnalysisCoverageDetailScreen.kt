package com.nadeem.apkscope.ui.screens.staticresult

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.core.staticanalysis.StaticAnalysisCoverage
import com.nadeem.apkscope.ui.common.sessionViewModel
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.InfoCard
import com.nadeem.apkscope.ui.theme.Spacing
import com.nadeem.apkscope.ui.theme.extendedColors

/**
 * Detail view for the persisted static-analysis coverage record. The analyzer currently stores
 * DEX entry names and bounded failure/skip metadata, so this screen displays those exact facts
 * instead of inventing per-file sizes or instruction counts that were not collected.
 */
@Composable
fun AnalysisCoverageDetailScreen(
 sessionId: String,
 onBack: () -> Unit,
 modifier: Modifier = Modifier,
) {
 val context = LocalContext.current
 val viewModel = sessionViewModel { StaticResultViewModel(context.applicationContext as Application, sessionId) }
 val state by viewModel.uiState.collectAsState()

 Scaffold(
  modifier = modifier,
  topBar = { AppTopBar(title = "Analysis Coverage", onBack = onBack, onOverflow = {}) },
 ) { padding ->
  val analysis = state.analysis
  if (analysis == null) {
   Column(
    Modifier.fillMaxSize().padding(padding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
   ) {
    Text("Loading coverage…", style = MaterialTheme.typography.bodyMedium)
   }
  } else {
   val coverage = analysis.staticCoverage
   CoverageDetailContent(coverage = coverage, modifier = Modifier.fillMaxSize().padding(padding))
  }
 }
}

@Composable
private fun CoverageDetailContent(coverage: StaticAnalysisCoverage, modifier: Modifier = Modifier) {
 LazyColumn(
  modifier = modifier.padding(horizontal = Spacing.base),
  verticalArrangement = Arrangement.spacedBy(Spacing.sm),
 ) {
  item {
   BaseCard {
    Row(
     Modifier.fillMaxWidth(),
     horizontalArrangement = Arrangement.SpaceBetween,
     verticalAlignment = Alignment.CenterVertically,
    ) {
     Column(Modifier.weight(1f)) {
      Text("Static scan coverage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      Text(
       "${coverage.dexFilesInspected.size} DEX file(s) inspected in ${coverage.scanDurationMs}ms",
       style = MaterialTheme.typography.bodySmall,
       color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
     }
     Icon(
      imageVector = if (coverage.limitsReached) Icons.Filled.Warning else Icons.Filled.CheckCircle,
      contentDescription = null,
      tint = if (coverage.limitsReached) MaterialTheme.extendedColors.warning else MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(24.dp),
     )
    }
    if (coverage.limitsReached) {
     Spacer(Modifier.size(Spacing.xs))
     InfoCard(text = "The analyzer reached a configured safety limit. The entries below are the files it actually inspected; skipped entries and parse errors are listed separately.")
    }
   }
  }

  if (coverage.dexFilesInspected.isEmpty()) {
   item {
    InfoCard(
     title = "No DEX entries recorded",
     text = "This analysis did not persist an inspected DEX-file list.",
    )
   }
  } else {
   item {
    Text("Inspected DEX files (${coverage.dexFilesInspected.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
   }
   items(coverage.dexFilesInspected, key = { it }) { dexEntry ->
    BaseCard {
     Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Filled.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
      Spacer(Modifier.size(Spacing.sm))
      Column(Modifier.weight(1f)) {
       Text(dexEntry, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
       Text("Inspected by static analysis", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
     }
    }
   }
  }

  if (coverage.entriesSkipped.isNotEmpty()) {
   item {
    Text("Skipped entries (${coverage.entriesSkipped.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
   }
   items(coverage.entriesSkipped, key = { "skipped:$it" }) { entry ->
    CoverageMessageCard(icon = Icons.Filled.Warning, text = entry, tint = MaterialTheme.extendedColors.warning)
   }
  }

  if (coverage.parsingErrors.isNotEmpty()) {
   item {
    Text("Parsing errors (${coverage.parsingErrors.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
   }
   items(coverage.parsingErrors, key = { "error:$it" }) { error ->
    CoverageMessageCard(icon = Icons.Filled.ErrorOutline, text = error, tint = MaterialTheme.colorScheme.error)
   }
  }

  item { Spacer(Modifier.size(Spacing.base)) }
 }
}

@Composable
private fun CoverageMessageCard(
 icon: androidx.compose.ui.graphics.vector.ImageVector,
 text: String,
 tint: androidx.compose.ui.graphics.Color,
) {
 BaseCard {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
   Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
   Spacer(Modifier.size(Spacing.sm))
   Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
  }
 }
}
