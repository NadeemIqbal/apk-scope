package com.nadeem.apkscope.ui.screens.poc

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.net.Uri
import com.nadeem.apkscope.domain.ApkImportUseCase
import com.nadeem.apkscope.poc.apkrepack.ApkRepackPipeline
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.Badge
import com.nadeem.apkscope.ui.components.PrimaryActionButton
import com.nadeem.apkscope.ui.components.StickyActionBar
import com.nadeem.apkscope.ui.theme.Spacing
import com.nadeem.apkscope.ui.theme.extendedColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * APK patching and Frida instrumentation screen.
 *
 * Allows users to:
 * 1. Select any APK
 * 2. Check compatibility (fixture only)
 * 3. Run the repack pipeline
 * 4. View results (hashes, signer, modifications)
 */
@Composable
fun ApkRepackScreen(
    onBack: () -> Unit,
    onOpenAnalysis: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedApkPath by remember { mutableStateOf<String?>(null) }
    var isSelectingApk by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var isSendingToWork by remember { mutableStateOf(false) }
    var workInstallStatus by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ApkRepackPipeline.PipelineResult?>(null) }
    var repackProgress by remember { mutableStateOf<ApkRepackPipeline.Progress?>(null) }
    var analysisProgress by remember { mutableStateOf<ApkImportUseCase.Progress?>(null) }

    val apkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            isSelectingApk = true
            coroutineScope.launch {
                val file = try {
                    withContext(Dispatchers.IO) {
                        var displayName = "selected_apk_${System.currentTimeMillis()}.apk"
                        try {
                            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (nameIndex != -1 && cursor.moveToFirst()) {
                                    val name = cursor.getString(nameIndex)
                                    if (!name.isNullOrBlank()) {
                                        displayName = name
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Fallback to timestamp name if query fails
                        }

                        // Ensure name ends with .apk or keep original extension
                        val tempFile = File(context.cacheDir, File(displayName).name)
                        val inputStream = requireNotNull(context.contentResolver.openInputStream(uri)) { "Unable to open APK" }
                        inputStream.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        tempFile
                    }
                } catch (e: Exception) {
                    result = ApkRepackPipeline.PipelineResult.Failed("Selection", "Failed to read APK: ${e.message}")
                    null
                }
            isSelectingApk = false
            selectedApkPath = file?.absolutePath
            if (file != null) {
                result = null
                workInstallStatus = null
                repackProgress = null
                analysisProgress = null
                isProcessing = true
                val pipeline = ApkRepackPipeline(context)
                val repackResult = pipeline.repack(file) { progress ->
                    repackProgress = progress
                }
                withContext(Dispatchers.IO) { file.delete() }
                result = repackResult
                isProcessing = false
                if (repackResult is ApkRepackPipeline.PipelineResult.Success) {
                    val patchedPath = repackResult.result.exportedFilePath
                    if (patchedPath != null) {
                        isSendingToWork = true
                        workInstallStatus = "Analyzing patched APK..."
                        val patchedUri = Uri.fromFile(File(patchedPath))
                        val analysisId = ApkImportUseCase(context).importAndAnalyze(patchedUri) { progress ->
                            analysisProgress = progress
                            workInstallStatus = progress.message
                        }
                        withContext(Dispatchers.IO) {
                            File(context.filesDir, "analysis/$analysisId.repack.json").writeText(
                                org.json.JSONObject().apply {
                                    put("originalSha256", repackResult.result.originalApkSha256)
                                    put("patchedSha256", repackResult.result.modifiedApkSha256)
                                }.toString()
                            )
                        }
                        isSendingToWork = false
                        onOpenAnalysis(analysisId)
                    }
                }
            }
        }
        }
    }

    LaunchedEffect(Unit) {
        apkLauncher.launch(
            arrayOf(
                "application/vnd.android.package-archive",
                "application/octet-stream",
                "*/*",
            ),
        )
    }

    androidx.activity.compose.BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = "APK Patching",
                eyebrow = "EXPERIMENTAL",
                onBack = onBack,
            )
        },
        bottomBar = {
            if (!isSelectingApk && !isProcessing && !isSendingToWork && result !is ApkRepackPipeline.PipelineResult.Success) {
                StickyActionBar {
                    PrimaryActionButton(
                        text = if (selectedApkPath == null) "Choose APK File" else "Choose Another APK",
                        onClick = {
                            apkLauncher.launch(
                                arrayOf(
                                    "application/vnd.android.package-archive",
                                    "application/octet-stream",
                                    "*/*"
                                )
                            )
                        },
                        icon = Icons.Filled.FolderOpen,
                    )
                }
            }
        },
    ) { padding ->
        val progress = patchingProgressSnapshot(
            selectedApkPath = selectedApkPath,
            isSelectingApk = isSelectingApk,
            isProcessing = isProcessing,
            isSendingToWork = isSendingToWork,
            result = result,
            repackProgress = repackProgress,
            analysisProgress = analysisProgress,
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Spacing.base),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            item {
                BaseCard(containerColor = MaterialTheme.extendedColors.warningContainer) {
                    Badge("FRIDA FLOW", MaterialTheme.extendedColors.warning)
                    Spacer(Modifier.height(Spacing.xs))
                    Text("Integrated APK Patching Pipeline", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Modified APK behavior may differ from the original. Keep CA decryption off when using this instrumentation flow.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.extendedColors.onWarningContainer,
                    )
                }
            }

            item {
                PatchingProgressCard(
                    snapshot = progress,
                    selectedApkName = selectedApkPath?.substringAfterLast("/"),
                )
            }

            if (isSelectingApk) {
                item {
                    Text(
                        "Copying the selected APK into private app storage…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.xs),
                    )
                }
            }

            result?.let { pipelineResult ->
                when (pipelineResult) {
                    is ApkRepackPipeline.PipelineResult.Success -> {
                        item {
                            BaseCard(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                                Badge("PATCH COMPLETE", MaterialTheme.colorScheme.tertiary)
                                Spacer(Modifier.height(Spacing.xs))
                                Text("APK Patched", style = MaterialTheme.typography.headlineSmall)
                                Spacer(Modifier.height(Spacing.sm))

                                ResultField("Original APK", pipelineResult.result.originalApkSha256)
                                ResultField("Modified APK", pipelineResult.result.modifiedApkSha256)
                                ResultField("Original Signer", pipelineResult.result.originalApkSignerSubject)
                                ResultField("Modified Signer", pipelineResult.result.modifiedApkSignerSubject)
                                ResultField("Package", pipelineResult.result.originalApkPackageName)
                                ResultField("Version", pipelineResult.result.originalApkVersionCode.toString())
                                workInstallStatus?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }

                                Spacer(Modifier.height(Spacing.sm))
                                Text("Applied Modifications:", style = MaterialTheme.typography.labelSmall)
                                pipelineResult.result.appliedModifications.forEach { mod ->
                                    Text(
                                        "• ${mod.type.name}: ${mod.description}",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(start = Spacing.sm)
                                    )
                                }
                            }
                        }
                    }

                    is ApkRepackPipeline.PipelineResult.Unsupported -> {
                        item {
                            BaseCard(containerColor = MaterialTheme.extendedColors.warningContainer) {
                                Badge("UNSUPPORTED", MaterialTheme.extendedColors.warning)
                                Spacer(Modifier.height(Spacing.xs))
                                Text("Unsupported APK", style = MaterialTheme.typography.headlineSmall)
                                Text(pipelineResult.reason, style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.height(Spacing.sm))
                                Text(
                                    "This patching flow currently supports the pinnedfixture (com.apksandbox.pinnedfixture).",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    is ApkRepackPipeline.PipelineResult.Failed -> {
                        item {
                            BaseCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                                Badge("FAILED", MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(Spacing.xs))
                                Text("Failed: ${pipelineResult.stage}", style = MaterialTheme.typography.headlineSmall)
                                Text(pipelineResult.error, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class PatchingStep(val number: Int) {
    SELECT(1),
    PATCH(2),
    ANALYZE(3),
}

private enum class PatchingStepStatus {
    PENDING,
    ACTIVE,
    COMPLETE,
    FAILED,
}

private data class PatchingProgressSnapshot(
    val overallFraction: Float,
    val activeStep: PatchingStep?,
    val message: String,
    val selectFraction: Float,
    val patchFraction: Float,
    val analyzeFraction: Float,
    val selectStatus: PatchingStepStatus,
    val patchStatus: PatchingStepStatus,
    val analyzeStatus: PatchingStepStatus,
)

private fun patchingProgressSnapshot(
    selectedApkPath: String?,
    isSelectingApk: Boolean,
    isProcessing: Boolean,
    isSendingToWork: Boolean,
    result: ApkRepackPipeline.PipelineResult?,
    repackProgress: ApkRepackPipeline.Progress?,
    analysisProgress: ApkImportUseCase.Progress?,
): PatchingProgressSnapshot {
    val hasSelection = selectedApkPath != null
    if (!hasSelection) {
        return PatchingProgressSnapshot(
            overallFraction = if (isSelectingApk) 0.06f else 0f,
            activeStep = PatchingStep.SELECT,
            message = if (isSelectingApk) "Copying selected APK" else "Choose an APK to begin",
            selectFraction = if (isSelectingApk) 0.5f else 0f,
            patchFraction = 0f,
            analyzeFraction = 0f,
            selectStatus = PatchingStepStatus.ACTIVE,
            patchStatus = PatchingStepStatus.PENDING,
            analyzeStatus = PatchingStepStatus.PENDING,
        )
    }

    val patchFraction = repackProgress?.let { progress ->
        when (progress.phase) {
            ApkRepackPipeline.ProgressPhase.COMPATIBILITY -> progress.fraction.coerceIn(0f, 1f) * 0.07f
            ApkRepackPipeline.ProgressPhase.REPACKING -> 0.07f + progress.fraction.coerceIn(0f, 1f) * 0.85f
            ApkRepackPipeline.ProgressPhase.PERSISTING -> 0.92f + progress.fraction.coerceIn(0f, 1f) * 0.08f
        }
    } ?: 0f
    val failed = result is ApkRepackPipeline.PipelineResult.Failed || result is ApkRepackPipeline.PipelineResult.Unsupported
    val analysisFraction = analysisProgress?.fraction?.coerceIn(0f, 1f) ?: 0f

    if (isSendingToWork) {
        return PatchingProgressSnapshot(
            overallFraction = 0.12f + 0.64f + 0.24f * analysisFraction,
            activeStep = PatchingStep.ANALYZE,
            message = analysisProgress?.message ?: "Starting patched APK analysis",
            selectFraction = 1f,
            patchFraction = 1f,
            analyzeFraction = analysisFraction,
            selectStatus = PatchingStepStatus.COMPLETE,
            patchStatus = PatchingStepStatus.COMPLETE,
            analyzeStatus = PatchingStepStatus.ACTIVE,
        )
    }

    if (result is ApkRepackPipeline.PipelineResult.Success) {
        return PatchingProgressSnapshot(
            overallFraction = 1f,
            activeStep = null,
            message = "Patched APK is ready",
            selectFraction = 1f,
            patchFraction = 1f,
            analyzeFraction = 1f,
            selectStatus = PatchingStepStatus.COMPLETE,
            patchStatus = PatchingStepStatus.COMPLETE,
            analyzeStatus = PatchingStepStatus.COMPLETE,
        )
    }

    return PatchingProgressSnapshot(
        overallFraction = 0.12f + 0.64f * patchFraction,
        activeStep = if (isProcessing) PatchingStep.PATCH else null,
        message = when {
            failed -> when (result) {
                is ApkRepackPipeline.PipelineResult.Failed -> "Stopped during ${result.stage}"
                is ApkRepackPipeline.PipelineResult.Unsupported -> "This APK is not supported by the patcher"
                is ApkRepackPipeline.PipelineResult.Success -> "Patching stopped"
            }
            isProcessing -> repackProgress?.message ?: "Preparing APK transformation"
            else -> "Ready to patch the selected APK"
        },
        selectFraction = 1f,
        patchFraction = patchFraction,
        analyzeFraction = 0f,
        selectStatus = PatchingStepStatus.COMPLETE,
        patchStatus = if (failed) PatchingStepStatus.FAILED else if (isProcessing) PatchingStepStatus.ACTIVE else PatchingStepStatus.PENDING,
        analyzeStatus = PatchingStepStatus.PENDING,
    )
}

@Composable
private fun PatchingProgressCard(
    snapshot: PatchingProgressSnapshot,
    selectedApkName: String?,
) {
    BaseCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("PATCHING PROGRESS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.xxs))
                Text(
                    when (snapshot.activeStep) {
                        PatchingStep.SELECT -> "Ready to patch"
                        PatchingStep.PATCH -> "Patching APK"
                        PatchingStep.ANALYZE -> "Analyzing patched APK"
                        null -> "Patch complete"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Text("3 STEPS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(Spacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PatchingProgressDonut(snapshot, Modifier.size(164.dp))
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    when (snapshot.activeStep) {
                        null -> "READY"
                        else -> "STEP ${snapshot.activeStep.number} OF 3"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    snapshot.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selectedApkName != null) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        selectedApkName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))
        PatchingStageRow(
            number = PatchingStep.SELECT.number,
            title = "Select APK",
            detail = selectedApkName ?: "Choose the target package for compatibility checks",
            status = snapshot.selectStatus,
            fraction = snapshot.selectFraction,
        )
        PatchingStageRow(
            number = PatchingStep.PATCH.number,
            title = "Inject Frida Gadget",
            detail = "Extract, inject, align, and sign the patched package",
            status = snapshot.patchStatus,
            fraction = snapshot.patchFraction,
        )
        PatchingStageRow(
            number = PatchingStep.ANALYZE.number,
            title = "Analyze patched APK",
            detail = "Scan the patched package and open its findings",
            status = snapshot.analyzeStatus,
            fraction = snapshot.analyzeFraction,
            last = true,
        )
    }
}

@Composable
private fun PatchingProgressDonut(
    snapshot: PatchingProgressSnapshot,
    modifier: Modifier = Modifier,
) {
    val animatedOverall by animateFloatAsState(
        targetValue = snapshot.overallFraction,
        animationSpec = tween(durationMillis = 550),
        label = "patching donut progress",
    )
    val segmentWeights = listOf(0.12f, 0.64f, 0.24f)
    val segmentColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = 12.dp.toPx()
            val diameter = min(size.width, size.height) - strokeWidth
            val topLeft = Offset(
                x = (size.width - diameter) / 2f,
                y = (size.height - diameter) / 2f,
            )
            // Keep the three stages visually distinct without making the donut feel broken up.
            val gap = 3f
            val availableSweep = 360f - (gap * segmentWeights.size)
            var startAngle = -90f
            var segmentStart = 0f

            segmentWeights.forEachIndexed { index, weight ->
                val sweep = availableSweep * weight
                val segmentProgress = ((animatedOverall - segmentStart) / weight).coerceIn(0f, 1f)
                val color = segmentColors[index]
                drawArc(
                    color = color.copy(alpha = 0.16f),
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(strokeWidth, cap = StrokeCap.Butt),
                    size = Size(diameter, diameter),
                    topLeft = topLeft,
                )
                if (segmentProgress > 0f) {
                    drawArc(
                        color = color,
                        startAngle = startAngle,
                        sweepAngle = sweep * segmentProgress,
                        useCenter = false,
                        style = Stroke(strokeWidth, cap = StrokeCap.Round),
                        size = Size(diameter, diameter),
                        topLeft = topLeft,
                    )
                }
                startAngle += sweep + gap
                segmentStart += weight
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (snapshot.activeStep) {
                    PatchingStep.SELECT -> "READY"
                    PatchingStep.PATCH, PatchingStep.ANALYZE -> "IN PROGRESS"
                    null -> "DONE"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                when (snapshot.activeStep) {
                    PatchingStep.SELECT -> "SELECT"
                    PatchingStep.PATCH -> "PATCH"
                    PatchingStep.ANALYZE -> "SCAN"
                    null -> "DONE"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PatchingStageRow(
    number: Int,
    title: String,
    detail: String,
    status: PatchingStepStatus,
    fraction: Float,
    last: Boolean = false,
) {
    val color = patchingStatusColor(status)
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 450),
        label = "step $number progress",
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = if (last) 0.dp else Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(color.copy(alpha = if (status == PatchingStepStatus.PENDING) 0.12f else 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when (status) {
                PatchingStepStatus.COMPLETE -> Icon(Icons.Filled.Check, contentDescription = "Step $number complete", tint = color, modifier = Modifier.size(15.dp))
                PatchingStepStatus.FAILED -> Icon(Icons.Filled.Close, contentDescription = "Step $number failed", tint = color, modifier = Modifier.size(15.dp))
                else -> Text(number.toString(), style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    when (status) {
                        PatchingStepStatus.COMPLETE -> "DONE"
                        PatchingStepStatus.ACTIVE -> if (number == PatchingStep.SELECT.number) "READY" else "WORKING"
                        PatchingStepStatus.FAILED -> "FAILED"
                        PatchingStepStatus.PENDING -> "WAITING"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                )
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Spacing.xs))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(4.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(animatedFraction)
                        .height(4.dp)
                        .background(color, RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}

@Composable
private fun patchingStatusColor(status: PatchingStepStatus) = when (status) {
    PatchingStepStatus.COMPLETE -> MaterialTheme.colorScheme.tertiary
    PatchingStepStatus.ACTIVE -> MaterialTheme.colorScheme.primary
    PatchingStepStatus.FAILED -> MaterialTheme.colorScheme.error
    PatchingStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun ResultField(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(start = Spacing.sm)
        )
    }
}

// Note: We use errorContainer for warning-like states since warningContainer is not standard
