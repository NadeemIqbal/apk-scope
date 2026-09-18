package com.nadeem.apkscope.ui.screens.staticresult

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.nadeem.apkscope.core.model.RiskLevel
import com.nadeem.apkscope.core.model.RiskSeverity
import com.nadeem.apkscope.core.staticanalysis.DexUrlExtractor
import com.nadeem.apkscope.core.staticanalysis.SigningResult
import com.nadeem.apkscope.domain.EnvironmentRepository
import com.nadeem.apkscope.ui.common.sessionViewModel
import com.nadeem.apkscope.ui.components.AlreadyInstalledInPersonalDialog
import com.nadeem.apkscope.ui.components.AppIdentityHeader
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.Badge
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.InfoCard
import com.nadeem.apkscope.ui.components.InspectionActionRow
import com.nadeem.apkscope.ui.components.MetricCard
import com.nadeem.apkscope.ui.components.PrimaryActionButton
import com.nadeem.apkscope.ui.components.RiskScoreRing
import com.nadeem.apkscope.ui.components.SecurityFindingCard
import com.nadeem.apkscope.ui.components.Severity
import com.nadeem.apkscope.ui.components.StickyActionBar
import com.nadeem.apkscope.ui.components.TextLinkButton
import com.nadeem.apkscope.ui.components.WorkProfileSetupDialog
import com.nadeem.apkscope.ui.components.isPackageInstalledInPersonal
import com.nadeem.apkscope.ui.components.riskFindingIcon
import com.nadeem.apkscope.ui.screens.detail.resolvePermissionModel
import com.nadeem.apkscope.ui.theme.ApkScopeTheme
import com.nadeem.apkscope.ui.theme.MonoCodeStyle
import com.nadeem.apkscope.ui.theme.Spacing
import com.nadeem.apkscope.ui.theme.extendedColors

private const val MAX_KEY_FINDINGS_SHOWN = 5

/**
 * static_analysis_result (item 8 of the UI checkpoint; item 15/16/17 of checkpoint 3). Now shows
 * the **real** [com.nadeem.apkscope.core.model.StaticRiskAssessment] score/level/findings from
 * `core:risk` — the Stitch mock's "72 / HIGH RISK" only ever appears in
 * [StaticResultScorePreview] unless a real APK actually evaluates to that value.
 */
@Composable
fun StaticResultScreen(
 sessionId: String,
 onContinueToSandbox: (String) -> Unit,
 onViewPermissions: (String) -> Unit,
 onViewComponents: (String) -> Unit,
 onViewManifest: (String) -> Unit = {},
 onViewFindings: (String) -> Unit,
 onViewEmbeddedUrls: (String) -> Unit = {},
 onViewDetectedSdks: (String) -> Unit = {},
 onViewApiReferences: (String) -> Unit = {},
 onOpenSecurityAudit: (String) -> Unit = {},
 onViewCoverage: (String) -> Unit = {},
 onBack: () -> Unit,
 modifier: Modifier = Modifier,
) {
 val context = LocalContext.current
 val environmentRepository = remember { EnvironmentRepository(context.applicationContext) }
 var showSetupDialog by remember { mutableStateOf(false) }
 var showAlreadyInstalledDialog by remember { mutableStateOf(false) }

 val provisioningLauncher = rememberLauncherForActivityResult(
  contract = ActivityResultContracts.StartActivityForResult(),
 ) {
  if (environmentRepository.isWorkProfileConfigured()) {
   onContinueToSandbox(sessionId)
  }
 }

 val viewModel = sessionViewModel { StaticResultViewModel(context.applicationContext as Application, sessionId) }
 val state by viewModel.uiState.collectAsState()
 val isPatchedApk = remember(sessionId) {
  java.io.File(context.filesDir, "analysis/$sessionId.repack.json").exists()
 }


 androidx.activity.compose.BackHandler(onBack = onBack)

 Scaffold(
  modifier = modifier,
  topBar = { AppTopBar(title = "Analysis Detail", onBack = onBack, onOverflow = {}) },
  bottomBar = {
   state.analysis?.takeUnless { state.isLoading }?.let { analysis ->
    StickyActionBar {
     PrimaryActionButton(
      text = "Continue to Sandbox",
      onClick = {
       if (isPackageInstalledInPersonal(context, analysis.packageName)) {
        showAlreadyInstalledDialog = true
       } else if (environmentRepository.isWorkProfileConfigured()) {
        onContinueToSandbox(sessionId)
       } else {
        showSetupDialog = true
       }
      },
     )
    }
   }
  },
 ) { padding ->
  if (state.isLoading || state.analysis == null) {
   Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    CircularProgressIndicator()
   }
   return@Scaffold
  }
  val analysis = state.analysis!!
  val risk = analysis.riskAssessment
  val embeddedUrls = analysis.embeddedUrls.filterNot { candidate ->
   candidate.runtimeEvidence == null && DexUrlExtractor.isLikelyDocumentationUrl(candidate.normalizedUrl)
  }

  Column(Modifier.fillMaxSize().padding(padding).padding(Spacing.base).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.base)) {
   AppIdentityHeader(
    appName = analysis.appName,
    packageName = analysis.packageName,
    versionName = analysis.versionName,
    platform = analysis.platformInfo.platform.name,
    platformDetails = analysis.platformInfo.details,
   )

   BaseCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
     Column(Modifier.weight(1f)) {
      Badge(if (isPatchedApk) "PATCHED APK" else "APK SELECTED", MaterialTheme.colorScheme.primary)
      Spacer(Modifier.height(Spacing.xs))
      Text(analysis.appName ?: analysis.packageName, style = MaterialTheme.typography.headlineLarge)
      Text(analysis.packageName, style = MonoCodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
     }
     if (!analysis.platformInfo.platform.name.isBlank()) {
      com.nadeem.apkscope.ui.components.PlatformBadge(platformName = analysis.platformInfo.platform.name, details = analysis.platformInfo.details)
     }
    }
    Spacer(Modifier.height(Spacing.sm))
    Text(
     if (isPatchedApk) "Analysis of the modified APK. Modified behavior may differ from the original."
     else "Static facts do not prove runtime behavior.",
     style = MaterialTheme.typography.bodySmall,
     color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
   }

   BaseCard {
    Text(
     "STATIC RISK SCORE · 0–100",
     style = MaterialTheme.typography.labelSmall,
     color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
     RiskScoreRing(score = risk.score)
    }
    Spacer(Modifier.height(Spacing.sm))
    Text(riskLevelLabel(risk.level), style = MaterialTheme.typography.headlineMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(
     "This is a security-relevant characteristics score computed from what this APK declares and how it is built — it is not a malware probability, confidence, or virus-detection result.",
     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Spacing.sm))
    SignatureRow(analysis.signing)
   }

   Text("Security Review", style = MaterialTheme.typography.headlineSmall)
   BaseCard {
    InspectionActionRow(
     icon = Icons.Filled.FactCheck,
     title = "Security Audit",
     countText = "New",
     subtext = "Evidence-backed static checks with severity, confidence, and remediation",
     onClick = { onOpenSecurityAudit(sessionId) },
     iconTint = MaterialTheme.colorScheme.tertiary,
    )
   }

   Text("Key Findings", style = MaterialTheme.typography.headlineSmall)
   if (risk.findings.isEmpty()) {
    InfoCard(text = "No risk findings — this analysis found no capabilities or structural facts flagged by the current rule set.")
   } else {
    risk.findings.sortedByDescending { it.scoreContribution }.take(MAX_KEY_FINDINGS_SHOWN).forEach { finding ->
     SecurityFindingCard(
      icon = riskFindingIcon(finding.ruleId), title = finding.title, description = finding.explanation,
      reference = finding.ruleId, severity = finding.severity.toUiSeverity(),
     )
    }
   }
   TextLinkButton(if (risk.findings.size > MAX_KEY_FINDINGS_SHOWN) "View all ${risk.findings.size} findings" else "Why this score?", onClick = { onViewFindings(sessionId) })

   val dangerousCount = remember(analysis.permissions) {
    analysis.permissions.count { resolvePermissionModel(it, context).isDangerous }
   }

   Text("Package Surface & Access", style = MaterialTheme.typography.headlineSmall)
   Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
   ) {
    Row(
     modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
     horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
     MetricCard(
      modifier = Modifier.weight(1f).fillMaxHeight(),
      label = "Permissions",
      value = "${analysis.permissions.size} declared",
      subtext = if (dangerousCount > 0) "$dangerousCount dangerous" else "0 dangerous",
      subtextColor = if (dangerousCount > 0) MaterialTheme.extendedColors.warning else null,
      onClick = { onViewPermissions(sessionId) },
     )
     MetricCard(
      modifier = Modifier.weight(1f).fillMaxHeight(),
      label = "Components",
      value = "${analysis.components.size} total",
      subtext = "${analysis.components.count { it.exported }} exported",
      onClick = { onViewComponents(sessionId) },
     )
    }
    Row(
     modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
     horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
     MetricCard(
      modifier = Modifier.weight(1f).fillMaxHeight(),
      label = "Native binaries",
      value = if (analysis.nativeLibraryAbis.isEmpty()) "None" else analysis.nativeLibraryAbis.first(),
      subtext = if (analysis.nativeLibraryAbis.isEmpty()) "Not present" else "ELF shared objects",
     )
     MetricCard(
      modifier = Modifier.weight(1f).fillMaxHeight(),
      label = "SHA-256",
      value = analysis.sha256.take(10) + "…",
      subtext = "Package digest",
     )
    }
   }

   BaseCard {
    InspectionActionRow(
     icon = Icons.Filled.Security,
     title = "Declared Permissions",
     countText = "${analysis.permissions.size}",
     subtext = "${analysis.permissions.size} permissions requested in manifest",
     onClick = { onViewPermissions(sessionId) },
     iconTint = MaterialTheme.colorScheme.primary,
    )
    HorizontalDivider(
     modifier = Modifier.padding(vertical = Spacing.xs),
     color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
    InspectionActionRow(
     icon = Icons.Filled.Apps,
     title = "Application Components",
     countText = "${analysis.components.size}",
     subtext = "${analysis.components.size} components (${analysis.components.count { it.exported }} exported)",
     onClick = { onViewComponents(sessionId) },
     iconTint = MaterialTheme.colorScheme.secondary,
    )
   }

   BaseCard {
    InspectionActionRow(
     icon = Icons.Filled.Code,
     title = "Manifest & Configuration",
     countText = "XML",
     subtext = "Meta-data, API keys, network security config & raw XML",
     onClick = { onViewManifest(sessionId) },
     iconTint = MaterialTheme.colorScheme.tertiary,
    )
   }

   Text("Declared Capabilities", style = MaterialTheme.typography.headlineSmall)
   if (state.notes.isEmpty()) {
    InfoCard(text = "No notable declared capabilities were found in this manifest.")
   } else {
    state.notes.forEach { note ->
     SecurityFindingCard(icon = iconFor(note.manifestConstant), title = note.title, description = note.description, reference = note.manifestConstant)
    }
   }

   Text("Deeper Static Analysis", style = MaterialTheme.typography.headlineSmall)
   val cov = analysis.staticCoverage
   BaseCard {
    InspectionActionRow(
     icon = Icons.Filled.Memory,
     title = "Analysis Coverage",
     countText = "${cov.dexFilesInspected.size}",
     subtext = "${cov.dexFilesInspected.size} DEX file(s) inspected in ${cov.scanDurationMs}ms" +
       if (cov.entriesSkipped.isNotEmpty()) " • ${cov.entriesSkipped.size} skipped" else "",
     onClick = { onViewCoverage(sessionId) },
     iconTint = if (cov.limitsReached) MaterialTheme.extendedColors.warning else MaterialTheme.colorScheme.primary,
    )
    if (cov.limitsReached) {
     Spacer(Modifier.height(Spacing.xs))
     Text("Limits reached — open for coverage details", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extendedColors.warning)
    }
    if (cov.parsingErrors.isNotEmpty()) {
     Spacer(Modifier.height(Spacing.xs))
     Text(
      text = "Errors: ${cov.parsingErrors.joinToString("; ")}",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.error,
     )
    }
   }

   BaseCard {
    InspectionActionRow(
     icon = Icons.Filled.Security,
     title = "Security API References",
     countText = "${analysis.apiFindings.size}",
     subtext = "${analysis.apiFindings.size} references (${analysis.apiFindings.count { it.isInvocation }} invocations)",
     onClick = { onViewApiReferences(sessionId) },
     iconTint = MaterialTheme.extendedColors.warning,
    )
    HorizontalDivider(
     modifier = Modifier.padding(vertical = Spacing.xs),
     color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
    InspectionActionRow(
     icon = Icons.Filled.Layers,
     title = "Detected SDKs",
     countText = "${analysis.detectedSdks.size}",
     subtext = "${analysis.detectedSdks.size} identified (${analysis.detectedSdks.count { it.confidence == com.nadeem.apkscope.core.staticanalysis.SdkConfidence.HIGH }} confirmed)",
     onClick = { onViewDetectedSdks(sessionId) },
     iconTint = MaterialTheme.colorScheme.secondary,
    )
    HorizontalDivider(
     modifier = Modifier.padding(vertical = Spacing.xs),
     color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
    InspectionActionRow(
     icon = Icons.Filled.Link,
     title = "Embedded URLs",
     countText = "${embeddedUrls.size}",
     subtext = "${embeddedUrls.size} discovered (${embeddedUrls.count { it.provenance == com.nadeem.apkscope.core.staticanalysis.UrlProvenance.REFERENCED_BY_CODE }} referenced in code)",
     onClick = { onViewEmbeddedUrls(sessionId) },
     iconTint = MaterialTheme.colorScheme.primary,
    )
   }

    val isInstalledInPersonal = isPackageInstalledInPersonal(context, analysis.packageName)
    if (isInstalledInPersonal) {
     InfoCard(
      title = "App already installed in personal profile",
      text = "Check if app is already installed in personal profile then uninstall and retry.",
     )
    }

   }

   WorkProfileSetupDialog(
    isOpen = showSetupDialog,
    isProvisioningAllowed = environmentRepository.isProvisioningAllowed(),
    onDismiss = { showSetupDialog = false },
    onConfirmSetup = {
     showSetupDialog = false
     try {
      provisioningLauncher.launch(environmentRepository.createProvisioningIntent())
     } catch (_: Exception) {}
    },
   )

   AlreadyInstalledInPersonalDialog(
    isOpen = showAlreadyInstalledDialog,
    packageName = analysis.packageName,
    appName = analysis.appName,
    onDismiss = { showAlreadyInstalledDialog = false },
   )
  }
 }

private fun riskLevelLabel(level: RiskLevel) = when (level) {
 RiskLevel.LOW -> "Low risk"
 RiskLevel.MODERATE -> "Moderate risk"
 RiskLevel.HIGH -> "High risk"
 RiskLevel.CRITICAL -> "Critical risk"
}

@Composable
private fun InspectionModeRow(
 title: String,
 badge: String,
 description: String,
 warning: String,
 selected: Boolean,
) {
 Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
  Column(Modifier.weight(1f)) {
   Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    Badge(badge, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary, filled = selected)
   }
   Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
   Text(warning, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extendedColors.warning)
  }
  Icon(
   if (selected) Icons.Filled.FactCheck else Icons.Filled.Code,
   contentDescription = null,
   tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
  )
 }
}

private fun RiskSeverity.toUiSeverity(): Severity? = when (this) {
 RiskSeverity.INFO -> null
 RiskSeverity.LOW -> Severity.LOW
 RiskSeverity.MEDIUM -> Severity.MEDIUM
 RiskSeverity.HIGH -> Severity.HIGH
}

@Composable
private fun SignatureRow(signing: SigningResult) {
 val verified = signing is SigningResult.Verified
 Row(verticalAlignment = Alignment.CenterVertically) {
  Icon(if (verified) Icons.Filled.Visibility else Icons.Filled.Visibility, contentDescription = null, tint = if (verified) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
  Spacer(Modifier.width(Spacing.xs))
  Text(
   if (verified) "APK Signature Validated" else "APK signature verification failed",
   style = MaterialTheme.typography.labelLarge,
   color = if (verified) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
  )
 }
 if (signing is SigningResult.Failed) Text(signing.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
 if (signing is SigningResult.Verified) Text(signing.signerCertificateSha256.firstOrNull()?.take(16)?.let { "Signer: $it…" } ?: "", style = MonoCodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun iconFor(manifestConstant: String) = when {
 manifestConstant.contains("CAMERA") -> Icons.Filled.Camera
 manifestConstant.contains("RECORD_AUDIO") -> Icons.Filled.Mic
 manifestConstant.contains("LOCATION") -> Icons.Filled.LocationOn
 manifestConstant.contains("BOOT") -> Icons.Filled.PowerSettingsNew
 manifestConstant.contains("lib/") -> Icons.Filled.Memory
 // Item 26: a neutral generic security icon, not the Bluetooth icon this used to fall back to
 // for every unmapped permission (microphone/location were both hitting this before item 26's
 // fix landed on the specific mappings above; anything still unmapped gets this, not Bluetooth).
 else -> Icons.Filled.Security
}

@Preview(showBackground = true, backgroundColor = 0xFF111319)
@Composable
private fun StaticResultFindingPreview() {
 ApkScopeTheme {
  Column(Modifier.padding(Spacing.base)) {
   SecurityFindingCard(
    icon = Icons.Filled.PowerSettingsNew,
    title = "Starts after device boot",
    description = "Can automatically start background services when the device boots, maintaining persistent execution.",
    reference = "android.permission.RECEIVE_BOOT_COMPLETED",
   )
  }
 }
}

/** Preview-only — the Stitch mock score/level. A real production screen only ever shows this when a real APK evaluates to it (item 15). */
@Preview(showBackground = true, backgroundColor = 0xFF111319)
@Composable
private fun StaticResultScorePreview() {
 ApkScopeTheme {
  Column(Modifier.padding(Spacing.base), horizontalAlignment = Alignment.CenterHorizontally) {
   RiskScoreRing(score = 72)
   Spacer(Modifier.height(Spacing.sm))
   Text("High risk", style = MaterialTheme.typography.headlineMedium)
  }
 }
}
