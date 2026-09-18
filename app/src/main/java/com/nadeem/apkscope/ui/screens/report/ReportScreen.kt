package com.nadeem.apkscope.ui.screens.report

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.core.model.AndroidEvidenceStatus
import com.nadeem.apkscope.core.model.ApkScopeReport
import com.nadeem.apkscope.core.model.AssessmentType
import com.nadeem.apkscope.core.model.EvidenceReference
import com.nadeem.apkscope.core.model.EvidenceSource
import com.nadeem.apkscope.core.model.RiskFinding
import com.nadeem.apkscope.core.model.RiskLevel
import com.nadeem.apkscope.core.model.RiskSeverity
import com.nadeem.apkscope.ui.common.sessionViewModel
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.PrimaryActionButton
import com.nadeem.apkscope.ui.components.RiskLevelBadge
import com.nadeem.apkscope.ui.components.RiskSeverityBadge
import com.nadeem.apkscope.ui.components.SectionHeader
import com.nadeem.apkscope.ui.components.StickyActionBar
import com.nadeem.apkscope.ui.theme.ApkScopeColors
import com.nadeem.apkscope.ui.theme.Spacing

/**
 * Checkpoint 7: Full production Final Report screen.
 *
 * Displays the interpretation and reporting layer over the three independent evidence sources:
 * Declared Capabilities, Observed Behavior (VPN), and Android Evidence (DPM).
 * Strictly preserves evidence provenance and clearly discloses HTTPS payload inspection boundaries.
 */
@Composable
fun ReportScreen(
 sessionId: String,
 onDone: () -> Unit,
 onBack: () -> Unit,
 modifier: Modifier = Modifier,
) {
 val context = LocalContext.current
 val viewModel: ReportViewModel = sessionViewModel {
  ReportViewModel(context.applicationContext as Application, sessionId)
 }
 val state by viewModel.uiState.collectAsState()

 androidx.activity.compose.BackHandler(onBack = onBack)

 Scaffold(
  modifier = modifier,
  topBar = {
   AppTopBar(
    title = "Evidence report",
    eyebrow = state.report?.appIdentity?.packageName ?: "APK SCOPE",
    onBack = onBack,
    onOverflow = {},
   )
  },
  bottomBar = {
   if (!state.isLoading && state.report != null) {
    StickyActionBar {
     PrimaryActionButton(
      text = "Done",
      onClick = onDone,
     )
    }
   }
  },
 ) { padding ->
  if (state.isLoading) {
   Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
    CircularProgressIndicator()
   }
  } else if (state.report == null) {
   Box(Modifier.fillMaxSize().padding(padding).padding(Spacing.base), contentAlignment = Alignment.Center) {
    Text("Report not found for identifier: $sessionId", style = MaterialTheme.typography.bodyLarge)
   }
  } else {
   val report = state.report!!
   Column(
    Modifier
     .fillMaxSize()
     .padding(padding)
     .padding(Spacing.base)
     .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(Spacing.base),
   ) {
    ReportHeroCard(report)

    // 1. App Identity Card
    AppIdentityCard(report)

    // 2. Overall Risk Card
    OverallRiskCard(report)

    // 3. Evidence Completeness Card
    EvidenceCompletenessCard(report)

    // 4. Risk Findings Section
    RiskFindingsSection(
     report = report,
     expandedFindingIds = state.expandedFindingIds,
     onToggleExpand = viewModel::toggleFindingExpansion,
    )

    // 5. Declared Capabilities Section
    DeclaredCapabilitiesSection(report, state)

    // 6. Observed Behavior Section (VPN facts only)
    ObservedBehaviorSection(report, state)

    // 7. Android Evidence Section (DPM facts only)
    AndroidEvidenceSection(report, state)

    // 8. Correlated Activity Section (Inferred)
    if (report.runtimeAssessment?.correlatedDns?.isNotEmpty() == true) {
     CorrelatedActivitySection(report)
    }

    // 9. API Visibility / HTTPS Payload Limitation Notice
    HttpsLimitationNotice()

    Spacer(Modifier.height(Spacing.xxl))
   }
  }
 }
}

@Composable
private fun ReportHeroCard(report: ApkScopeReport) {
 val completeness = report.evidenceCompleteness
 BaseCard(containerColor = ApkScopeColors.WarningContainer) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
   Column(Modifier.weight(1f)) {
    Text("SESSION COMPLETE", style = MaterialTheme.typography.labelSmall, color = ApkScopeColors.Warning)
    Text(
     when (report.overallLevel) {
      RiskLevel.LOW -> "Review complete"
      RiskLevel.MODERATE -> "Review recommended"
      RiskLevel.HIGH -> "Review recommended"
      RiskLevel.CRITICAL -> "Immediate review needed"
     },
     style = MaterialTheme.typography.headlineMedium,
     color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
     "${report.staticAssessment.findings.size + (report.runtimeAssessment?.findings?.size ?: 0)} evidence-backed findings",
     style = MaterialTheme.typography.bodySmall,
     color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
   }
   Text("${report.overallScore}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = ApkScopeColors.Warning)
  }
  Spacer(Modifier.height(Spacing.md))
  EvidenceSourceRow("Declared", "${report.declaredCapabilities.size} capabilities", completeness.staticComplete)
  report.observedBehaviorSummary?.let { EvidenceSourceRow("Observed", "${it.connectionCount} connections", completeness.runtimeComplete) }
  report.androidEvidenceSummary?.let { EvidenceSourceRow("Android evidence", "${it.dnsCount + it.connectCount} events", it.status == AndroidEvidenceStatus.READY) }
 }
}

@Composable
private fun EvidenceSourceRow(label: String, value: String, complete: Boolean) {
 Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
  Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Row(verticalAlignment = Alignment.CenterVertically) {
   Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
   Spacer(Modifier.width(Spacing.xs))
   Text(if (complete) "Verified" else "Pending", style = MaterialTheme.typography.labelSmall, color = if (complete) MaterialTheme.colorScheme.tertiary else ApkScopeColors.Warning)
  }
 }
}

@Composable
private fun AppIdentityCard(report: ApkScopeReport) {
 val id = report.appIdentity
 BaseCard {
  Text("APPLICATION IDENTITY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Text(id.appName ?: id.packageName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
  Text(id.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
   Text("Version: ${id.versionName ?: "n/a"} (${id.versionCode})", style = MaterialTheme.typography.bodySmall)
   Text("SHA256: ${id.sha256.take(8)}…", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
  }
 }
}

@Composable
private fun OverallRiskCard(report: ApkScopeReport) {
 BaseCard {
  Text("OVERALL RISK ASSESSMENT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Row(
   Modifier.fillMaxWidth(),
   horizontalArrangement = Arrangement.SpaceBetween,
   verticalAlignment = Alignment.CenterVertically,
  ) {
   Column {
    Row(verticalAlignment = Alignment.Bottom) {
     Text(
      "${report.overallScore}",
      style = MaterialTheme.typography.displayMedium,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface,
     )
     Text(" / 100", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
    }
    Text(
     when (report.overallLevel) {
      RiskLevel.LOW -> "Low observed risk"
      RiskLevel.MODERATE -> "Moderate risk"
      RiskLevel.HIGH -> "High risk"
      RiskLevel.CRITICAL -> "Critical risk"
     },
     style = MaterialTheme.typography.titleSmall,
     color = MaterialTheme.colorScheme.onSurface,
    )
   }
   RiskLevelBadge(level = report.overallLevel)
  }

  HorizontalDivider(Modifier.padding(vertical = Spacing.sm), color = MaterialTheme.colorScheme.outlineVariant)

  // Keep static and runtime scores separate. They are both 0-100 signals, not interchangeable
  // confidence labels and not a claim that the APK is malware.
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
   Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text("STATIC SCORE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("${report.staticAssessment.score} / 100", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(report.staticAssessment.level.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
   }
   Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text("RUNTIME SCORE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    report.runtimeAssessment?.let { runtime ->
     Text("${runtime.score} / 100", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
     Text(runtime.level.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } ?: Text("Not run", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
   }
   Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text("COMBINED SIGNAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
     report.combinedAssessment?.combinedFindings?.sumOf { it.scoreContribution }?.let { "+$it" } ?: "—",
     style = MaterialTheme.typography.titleMedium,
     fontWeight = FontWeight.Bold,
    )
    Text("added context", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }

  // Mandatory non-malware disclaimer
  Text(
   "This score reflects security-relevant capabilities and behavior identified during analysis. It is not a probability that the APK is malware.",
   style = MaterialTheme.typography.bodySmall,
   color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
 }
}

@Composable
private fun EvidenceCompletenessCard(report: ApkScopeReport) {
 val comp = report.evidenceCompleteness
 BaseCard {
  Text("EVIDENCE COMPLETENESS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

  CompletenessRow(
   label = "Static Analysis (${report.ruleVersions.staticVersion})",
   status = if (comp.staticComplete) "Complete" else "Incomplete",
   isSuccess = comp.staticComplete,
  )

  CompletenessRow(
   label = "Runtime Observation (${report.ruleVersions.runtimeVersion ?: "none"})",
   status = if (comp.runtimeComplete) "Complete" else "Not run",
   isSuccess = comp.runtimeComplete,
  )

  val androidStatusText = when (comp.androidEvidenceStatus) {
   AndroidEvidenceStatus.READY -> "Complete (verified by Android OS)"
   AndroidEvidenceStatus.PENDING -> "Pending platform log delivery"
   AndroidEvidenceStatus.TIMEOUT -> "Not delivered within window (timeout)"
   AndroidEvidenceStatus.NOT_AVAILABLE -> "Not collected for this report"
  }
  CompletenessRow(
   label = "Android OS Evidence",
   status = androidStatusText,
   isSuccess = comp.androidEvidenceStatus == AndroidEvidenceStatus.READY,
   isWarning = comp.androidEvidenceStatus == AndroidEvidenceStatus.PENDING || comp.androidEvidenceStatus == AndroidEvidenceStatus.TIMEOUT,
  )

  if (comp.androidEvidenceStatus == AndroidEvidenceStatus.TIMEOUT || comp.androidEvidenceStatus == AndroidEvidenceStatus.PENDING) {
   Text(
    "Android had not delivered the network-log batch within the collection window. This does not mean no network activity occurred; VPN wire observations remain available.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
   )
  }
 }
}

@Composable
private fun CompletenessRow(label: String, status: String, isSuccess: Boolean, isWarning: Boolean = false) {
 Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
  Text(label, style = MaterialTheme.typography.bodyMedium)
  Row(verticalAlignment = Alignment.CenterVertically) {
   Icon(
    imageVector = when {
     isSuccess -> Icons.Filled.CheckCircle
     isWarning -> Icons.Filled.Warning
     else -> Icons.Filled.Info
    },
    contentDescription = null,
    tint = when {
     isSuccess -> MaterialTheme.colorScheme.tertiary
     isWarning -> MaterialTheme.colorScheme.error
     else -> MaterialTheme.colorScheme.onSurfaceVariant
    },
    modifier = Modifier.size(16.dp),
   )
   Spacer(Modifier.width(4.dp))
   Text(
    status,
    style = MaterialTheme.typography.bodySmall,
    color = when {
     isSuccess -> MaterialTheme.colorScheme.tertiary
     isWarning -> MaterialTheme.colorScheme.error
     else -> MaterialTheme.colorScheme.onSurfaceVariant
    },
   )
  }
 }
}

@Composable
private fun RiskFindingsSection(
 report: ApkScopeReport,
 expandedFindingIds: Set<String>,
 onToggleExpand: (String) -> Unit,
) {
 val allFindings = mutableListOf<RiskFinding>()
 allFindings.addAll(report.staticAssessment.findings)
 report.runtimeAssessment?.findings?.let { allFindings.addAll(it) }
 report.combinedAssessment?.combinedFindings?.let { allFindings.addAll(it) }

 val sorted = allFindings.sortedWith(
  compareByDescending<RiskFinding> { it.scoreContribution }
   .thenBy { it.severity }
   .thenBy { it.id }
 )

 SectionHeader(title = "Risk Findings (${sorted.size})")

 if (sorted.isEmpty()) {
  BaseCard {
   Text("No risk findings identified.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
 } else {
  sorted.forEach { finding ->
   FindingCard(
    finding = finding,
    isExpanded = finding.id in expandedFindingIds,
    onToggle = { onToggleExpand(finding.id) },
   )
  }
 }
}

@Composable
private fun FindingCard(
 finding: RiskFinding,
 isExpanded: Boolean,
 onToggle: () -> Unit,
) {
 BaseCard(modifier = Modifier.clickable { onToggle() }) {
  Row(
   Modifier.fillMaxWidth(),
   horizontalArrangement = Arrangement.SpaceBetween,
   verticalAlignment = Alignment.CenterVertically,
  ) {
   Row(verticalAlignment = Alignment.CenterVertically) {
    RiskSeverityBadge(severity = finding.severity)
    Spacer(Modifier.width(Spacing.sm))
    Text(
     when (finding.assessmentType) {
      AssessmentType.STATIC -> "STATIC"
      AssessmentType.RUNTIME -> "RUNTIME"
      AssessmentType.COMBINED -> "COMBINED"
     },
     style = MaterialTheme.typography.labelSmall,
     color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
   }
   Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
     "+${finding.scoreContribution} risk",
     style = MaterialTheme.typography.labelMedium,
     fontWeight = FontWeight.Bold,
     color = if (finding.scoreContribution > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Icon(
     imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
     contentDescription = "Expand",
     tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
   }
  }

  Text(finding.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
  Text(finding.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

  AnimatedVisibility(visible = isExpanded) {
   Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("SUPPORTING EVIDENCE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (finding.evidence.isEmpty()) {
     Text("No specific raw evidence cited.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
     finding.evidence.forEach { ref ->
      EvidenceReferenceRow(ref)
     }
    }
   }
  }
 }
}

@Composable
private fun EvidenceReferenceRow(ref: EvidenceReference) {
 Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
  Text(
   when (ref.source) {
    EvidenceSource.DECLARED_CAPABILITY -> "Manifest"
    EvidenceSource.OBSERVED_BEHAVIOR -> "VPN Observation"
    EvidenceSource.ANDROID_EVIDENCE -> "Android Evidence"
   },
   style = MaterialTheme.typography.labelSmall,
   fontWeight = FontWeight.Bold,
   color = when (ref.source) {
    EvidenceSource.DECLARED_CAPABILITY -> MaterialTheme.colorScheme.primary
    EvidenceSource.OBSERVED_BEHAVIOR -> MaterialTheme.colorScheme.secondary
    EvidenceSource.ANDROID_EVIDENCE -> MaterialTheme.colorScheme.tertiary
   },
   modifier = Modifier.width(130.dp),
  )
  Text(
   ref.description,
   style = MaterialTheme.typography.bodySmall,
   color = MaterialTheme.colorScheme.onSurface,
   modifier = Modifier.weight(1f),
  )
 }
}

@Composable
private fun DeclaredCapabilitiesSection(report: ApkScopeReport, state: ReportUiState) {
 SectionHeader(title = "Declared Capabilities (Manifest)")
 BaseCard {
  Text(
   "What the APK statically requests. No runtime execution is implied.",
   style = MaterialTheme.typography.bodySmall,
   color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  val perms = report.declaredCapabilities
  Text("Requested Permissions: ${perms.size}", style = MaterialTheme.typography.titleSmall)
  perms.take(8).forEach { p ->
   Text("• ${p.name}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
  }
  if (perms.size > 8) {
   Text("… and ${perms.size - 8} more permissions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }

  state.staticDetails?.session?.let { s ->
   HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
   Text("Components: ${s.componentTotalCount} total (${s.componentExportedCount} exported)", style = MaterialTheme.typography.bodySmall)
   Text("Target SDK: ${s.targetSdkVersion} · Debuggable: ${s.debuggable}", style = MaterialTheme.typography.bodySmall)
   Text("Signature: ${if (s.signatureVerified) "Verified" else "Failed (${s.signatureDetail})"}", style = MaterialTheme.typography.bodySmall)
  }
 }
}

@Composable
private fun ObservedBehaviorSection(report: ApkScopeReport, state: ReportUiState) {
 SectionHeader(title = "Observed Wire Behavior (VPN)")
 val obsSummary = report.observedBehaviorSummary
 if (obsSummary == null) {
  BaseCard {
   Text("Runtime monitoring was not run for this static analysis.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
 } else {
  BaseCard {
   Text(
    "Facts directly observed on the network wire by the work-profile VPN forwarding engine.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
   )
   Text(
    "${obsSummary.connectionCount} total connections · ${obsSummary.uniqueObservedDomains} unique DNS domains\n" +
     "${formatBytes(obsSummary.uploadedBytes)} uploaded · ${formatBytes(obsSummary.downloadedBytes)} downloaded",
    style = MaterialTheme.typography.bodyMedium,
    fontWeight = FontWeight.SemiBold,
   )
   if (obsSummary.blockedConnectionCount > 0) {
    Text(
     "${obsSummary.blockedConnectionCount} connection attempt(s) blocked by destination policy",
     style = MaterialTheme.typography.bodySmall,
     color = MaterialTheme.colorScheme.error,
    )
   }

   val opened = state.vpnObservations.filter { it.type == "ConnectionOpened" }
   if (opened.isNotEmpty()) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("Observed Socket Connections (VPN):", style = MaterialTheme.typography.labelSmall)
    opened.take(5).forEach { conn ->
     Text(
      "• ${conn.protocol ?: "TCP"} ${conn.destinationIp}:${conn.destinationPort}",
      style = MaterialTheme.typography.bodySmall,
      fontFamily = FontFamily.Monospace,
     )
    }
   }
  }
 }
}

@Composable
private fun AndroidEvidenceSection(report: ApkScopeReport, state: ReportUiState) {
 SectionHeader(title = "Android OS Evidence")
 val dpmSummary = report.androidEvidenceSummary
 if (dpmSummary == null) {
  BaseCard {
   Text("Android OS network-log evidence was not collected for this report.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
 } else {
  BaseCard {
   Text(
    "Events independently recorded by the Android operating system and attributed to package ${report.appIdentity.packageName}.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
   )
   Text(
    "Android recorded: ${dpmSummary.dnsCount} DNS lookup(s) · ${dpmSummary.connectCount} connection(s)",
    style = MaterialTheme.typography.bodyMedium,
    fontWeight = FontWeight.SemiBold,
   )

   if (state.dpmDnsEvents.isNotEmpty()) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("Android recorded DNS lookups:", style = MaterialTheme.typography.labelSmall)
    state.dpmDnsEvents.take(5).forEach { dns ->
     Text("• ${dns.hostname} → ${dns.resolvedAddressesCsv}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
   }

   if (state.dpmConnectEvents.isNotEmpty()) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text("Android recorded connections:", style = MaterialTheme.typography.labelSmall)
    state.dpmConnectEvents.take(5).forEach { conn ->
     Text("• ${conn.destinationAddress}:${conn.destinationPort}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
   }
  }
 }
}

@Composable
private fun CorrelatedActivitySection(report: ApkScopeReport) {
 val correlated = report.runtimeAssessment?.correlatedDns ?: return
 SectionHeader(title = "Correlated Activity (Inferred)")
 BaseCard {
  Text(
   "DNS resolution and socket connection matching based on resolved IP and timing correlation. This is an inferred correlation, not direct payload inspection.",
   style = MaterialTheme.typography.bodySmall,
   color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  correlated.take(5).forEach { entry ->
   Text("• ${entry.hostname} (${entry.ipAddress}:${entry.port})", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
   Text("  ${entry.inferredDescription}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }
}

@Composable
private fun HttpsLimitationNotice() {
 BaseCard {
  Row(verticalAlignment = Alignment.CenterVertically) {
   Icon(imageVector = Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
   Spacer(Modifier.width(Spacing.sm))
   Column {
    Text("Encrypted HTTPS payload", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    Text(
     "Request path and payload body are not inspected. Standard end-to-end encryption is preserved without CA injection or TLS MITM.",
     style = MaterialTheme.typography.bodySmall,
     color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
   }
  }
 }
}

private fun formatBytes(bytes: Long): String = when {
 bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
 bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
 else -> "$bytes B"
}
