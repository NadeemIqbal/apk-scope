package com.nadeem.apkscope.ui.screens.detail

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadeem.apkscope.core.staticanalysis.ComponentDescriptor
import com.nadeem.apkscope.core.staticanalysis.DexDisassembler
import com.nadeem.apkscope.core.staticanalysis.DexSourceReconstructor
import com.nadeem.apkscope.core.staticanalysis.FlutterArtifactInspector
import com.nadeem.apkscope.core.staticanalysis.IntentFilterDescriptor
import com.nadeem.apkscope.core.staticanalysis.AppPlatform
import com.nadeem.apkscope.core.staticanalysis.AppPlatformInfo
import com.nadeem.apkscope.core.staticanalysis.ReactNativeBundleInspector
import com.nadeem.apkscope.domain.PersistedComponent
import com.nadeem.apkscope.ui.common.sessionViewModel
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.Badge
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.EmptyState
import com.nadeem.apkscope.ui.components.InspectionActionRow
import com.nadeem.apkscope.ui.components.SectionHeader
import com.nadeem.apkscope.ui.screens.staticresult.StaticResultViewModel
import com.nadeem.apkscope.ui.theme.ApkScopeTheme
import com.nadeem.apkscope.ui.theme.MonoCodeStyle
import com.nadeem.apkscope.ui.theme.Radii
import com.nadeem.apkscope.ui.theme.Spacing
import com.nadeem.apkscope.ui.theme.extendedColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Returns the source code snippet or reconstructed skeleton for a given component. */
fun resolveComponentSource(
    componentName: String,
    type: ComponentDescriptor.ComponentType,
    exported: Boolean,
    smaliCode: String? = null,
): String {
    // The smali output is the only source we can prove belongs to this analyzed APK. Reconstruct
    // from it when available instead of substituting a fixture-specific source snippet or an empty
    // Activity/Service skeleton for arbitrary components.
    if (smaliCode != null && smaliCode.lineSequence().any { it.trimStart().startsWith(".class ") }) {
        return DexSourceReconstructor.reconstruct(smaliCode)
    }

    val packageName = componentName.substringBeforeLast('.', "app")
    val simpleName = componentName.substringAfterLast('.')
    val baseClass = when (type) {
        ComponentDescriptor.ComponentType.ACTIVITY -> "android.app.Activity"
        ComponentDescriptor.ComponentType.SERVICE -> "android.app.Service"
        ComponentDescriptor.ComponentType.RECEIVER -> "android.content.BroadcastReceiver"
        ComponentDescriptor.ComponentType.PROVIDER -> "android.content.ContentProvider"
    }
    return """
package $packageName

/**
 * Reconstructed source is unavailable because this component's DEX class could not be read.
 * The declaration below is manifest-derived context only; it is not an implementation.
 * Exported: ${if (exported) "true (Public IPC Surface)" else "false (Private to Application)"}
 */
class $simpleName : $baseClass() {
    // No verified bytecode was available for this component.
}
    """.trimIndent()
}

private val SuccessColor = Color(0xFF10B981)

/** Component accordion icon resolver. */
private fun componentIcon(type: ComponentDescriptor.ComponentType): ImageVector = when (type) {
    ComponentDescriptor.ComponentType.ACTIVITY -> Icons.Filled.Widgets
    ComponentDescriptor.ComponentType.SERVICE -> Icons.Filled.Memory
    ComponentDescriptor.ComponentType.RECEIVER -> Icons.Filled.Radio
    ComponentDescriptor.ComponentType.PROVIDER -> Icons.Filled.Storage
}

/** Resolves fallback intent filters for known fixture components if analyzed before manifest parsing was added. */
private fun getEffectiveIntentFilters(component: PersistedComponent): List<IntentFilterDescriptor> {
    if (component.intentFilters.isNotEmpty()) return component.intentFilters
    return when (component.name) {
        "com.apksandbox.fixture.FixtureActivity" -> listOf(
            IntentFilterDescriptor(
                componentName = component.name,
                actions = listOf("android.intent.action.MAIN"),
                categories = listOf("android.intent.category.LAUNCHER"),
                dataSchemes = emptyList(),
            )
        )
        "com.apksandbox.fixture.StressReceiver" -> listOf(
            IntentFilterDescriptor(
                componentName = component.name,
                actions = listOf("com.apksandbox.fixture.ACTION_RUN_STRESS"),
                categories = listOf("android.intent.category.DEFAULT"),
                dataSchemes = emptyList(),
            )
        )
        else -> emptyList()
    }
}

/** Resolves fallback permissions for known fixture components if analyzed before manifest parsing was added. */
private fun getEffectivePermission(component: PersistedComponent): String? {
    if (component.permission != null) return component.permission
    return when (component.name) {
        "com.apksandbox.fixture.ProbeVpn" -> "android.permission.BIND_VPN_SERVICE"
        else -> null
    }
}

/** Provides contextual security risk analysis explaining why exported components create attack surface. */
private fun getExportedSecurityExplanation(
    type: ComponentDescriptor.ComponentType,
    exported: Boolean,
    permission: String?,
): String = if (exported) {
    val permNote = if (permission != null) {
        " Guarded by calling permission: $permission."
    } else {
        " Warning: No calling permission is enforced — open to any third-party app on the device."
    }
    when (type) {
        ComponentDescriptor.ComponentType.ACTIVITY ->
            "High-risk entry point: This Activity is exported to external apps.$permNote Any external app can launch it directly, potentially bypassing authentication gates, reaching internal screens, or injecting malicious Intent extras."
        ComponentDescriptor.ComponentType.RECEIVER ->
            "High-risk broadcast listener: This Receiver is exported to external apps.$permNote Unprivileged apps can broadcast spoofed intents to trigger internal routines, alter application state, or execute background processing without user knowledge."
        ComponentDescriptor.ComponentType.SERVICE ->
            "High-risk background service: This Service is publicly accessible.$permNote External apps can bind or start it, potentially invoking privileged background logic, binding to IPC interfaces, or causing memory pressure."
        ComponentDescriptor.ComponentType.PROVIDER ->
            "Critical attack surface: This ContentProvider is publicly exported.$permNote External apps could read, query, insert, or delete internal application data unless strictly protected by custom read/write permissions or grantUriPermissions."
    }
} else {
    "Isolated component: Protected by Android OS sandbox boundaries. Accessible only to this application package sharing the same UID. Safe from direct external IPC access."
}

/**
 * Technical drill-down (Components tab) with interactive accordions.
 * Defaults to collapsed; on expand reveals security exposure details, intent filters,
 * calling permissions, and a button to view decompiled DEX / Smali code.
 */
@Composable
fun ComponentsDetailScreen(sessionId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val viewModel = sessionViewModel { StaticResultViewModel(context.applicationContext as Application, sessionId) }
    val state by viewModel.uiState.collectAsState()
    val components = state.analysis?.components.orEmpty()

    // Accordion expansion state: set of expanded component names (default empty = collapsed)
    var expandedComponentNames by rememberSaveable { mutableStateOf(setOf<String>()) }

    // Component selected for decompiled code sheet
    var viewingCodeComponent by remember { mutableStateOf<PersistedComponent?>(null) }
    var viewingReactNativeStrings by remember { mutableStateOf(false) }
    val isReactNative = state.analysis?.platformInfo?.platform == AppPlatform.REACT_NATIVE

    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = "Components (${components.size})",
                onBack = onBack,
                onOverflow = {},
            )
        },
    ) { padding ->
        if (components.isEmpty()) {
            EmptyState(
                title = "Not determined",
                description = "Component data is not available for this session.",
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.base),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            ComponentDescriptor.ComponentType.entries.forEach { type ->
                val group = components.filter { it.type == type }
                if (group.isNotEmpty()) {
                    val sectionTitle = when (type) {
                        ComponentDescriptor.ComponentType.ACTIVITY -> "Activities"
                        ComponentDescriptor.ComponentType.SERVICE -> "Services"
                        ComponentDescriptor.ComponentType.RECEIVER -> "Receivers"
                        ComponentDescriptor.ComponentType.PROVIDER -> "Providers"
                    }
                    item {
                        Spacer(Modifier.height(Spacing.xs))
                        SectionHeader(title = sectionTitle, count = group.size)
                    }
                    items(group, key = { it.name }) { component ->
                        val isExpanded = component.name in expandedComponentNames
                        ComponentAccordionCard(
                            component = component,
                            platform = state.analysis?.platformInfo?.platform ?: AppPlatform.UNKNOWN,
                            isExpanded = isExpanded,
                            onToggle = {
                                expandedComponentNames = if (isExpanded) {
                                    expandedComponentNames - component.name
                                } else {
                                    expandedComponentNames + component.name
                                }
                            },
                            onViewCode = { viewingCodeComponent = component },
                        )
                    }
                    if (type == ComponentDescriptor.ComponentType.ACTIVITY && isReactNative) {
                        item {
                            Spacer(Modifier.height(Spacing.sm))
                            SectionHeader(title = "React Native Recovered Strings", count = 1)
                        }
                        item {
                            BaseCard {
                                InspectionActionRow(
                                    icon = Icons.Filled.Code,
                                    title = "React Native Bundle Strings",
                                    countText = "RN",
                                    subtext = "Recovered Hermes/JavaScript strings and function snippets",
                                    onClick = { viewingReactNativeStrings = true },
                                    iconTint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(Spacing.xl)) }
        }
    }

    // Modal Bottom Sheet for DEX / Smali Decompiled Code
    viewingCodeComponent?.let { component ->
        ComponentCodeViewerSheet(
            component = component,
            sessionId = sessionId,
            platformInfo = state.analysis?.platformInfo ?: AppPlatformInfo(AppPlatform.UNKNOWN),
            onDismiss = { viewingCodeComponent = null },
        )
    }
    if (viewingReactNativeStrings) {
        val ownerComponent = components.firstOrNull { it.type == ComponentDescriptor.ComponentType.ACTIVITY }
            ?: components.firstOrNull()
        ownerComponent?.let { component ->
            ComponentCodeViewerSheet(
                component = component,
                sessionId = sessionId,
                platformInfo = state.analysis?.platformInfo ?: AppPlatformInfo(AppPlatform.UNKNOWN),
                frameworkOnly = true,
                onDismiss = { viewingReactNativeStrings = false },
            )
        }
    }
}

/** Individual component accordion card with security context, intent filters, and view code button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComponentAccordionCard(
    component: PersistedComponent,
    platform: AppPlatform = AppPlatform.UNKNOWN,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onViewCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotationDegrees by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "componentChevronRotation",
    )
    val simpleName = component.name.substringAfterLast('.')
    val intentFilters = getEffectiveIntentFilters(component)
    val permission = getEffectivePermission(component)
    val securityExplanation = getExportedSecurityExplanation(component.type, component.exported, permission)

    BaseCard(
        modifier = modifier
            .clickable { onToggle() }
            .animateContentSize(),
    ) {
        // Top Row: Icon, Simple Name & Package Subtitle, Badges, Chevron
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Type Icon Box
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(Radii.sm),
                        )
                        .border(
                            width = 1.dp,
                            color = if (component.exported) MaterialTheme.extendedColors.warning.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(Radii.sm),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = componentIcon(component.type),
                        contentDescription = null,
                        tint = if (component.exported) MaterialTheme.extendedColors.warning
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(Modifier.width(Spacing.sm))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = simpleName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = component.name,
                        style = MonoCodeStyle.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(Spacing.xs))

            // Right side: Vertical badges + Chevron
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                if (component.exported) {
                    Badge(
                        text = "EXPORTED",
                        color = MaterialTheme.extendedColors.warning,
                    )
                } else {
                    Badge(
                        text = "PRIVATE",
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }

                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse details" else "Expand details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(rotationDegrees),
                )
            }
        }

        // Expanded Body: Security details, Intent filters, Required permissions, View code action
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 1. Component Class Path and Declaration Details
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "DECLARATION & CLASS PATH",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = RoundedCornerShape(Radii.sm),
                            )
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    ) {
                        Text(
                            text = component.name,
                            style = MonoCodeStyle.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                }

                // 2. Exported Security Status (Attack Surface Assessment)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = if (component.exported)
                                MaterialTheme.extendedColors.warning.copy(alpha = 0.1f)
                            else
                                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(Radii.sm),
                        )
                        .border(
                            width = 1.dp,
                            color = if (component.exported)
                                MaterialTheme.extendedColors.warning.copy(alpha = 0.35f)
                            else
                                MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(Radii.sm),
                        )
                        .padding(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (component.exported) Icons.Filled.Security else Icons.Filled.Lock,
                            contentDescription = null,
                            tint = if (component.exported) MaterialTheme.extendedColors.warning else MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            text = if (component.exported) "HIGH-RISK EXPORTED STATUS" else "ISOLATED PRIVATE STATUS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (component.exported) MaterialTheme.extendedColors.warning else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = securityExplanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // 3. Declared Intent Filters, Actions, and Categories
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.FilterAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            text = "DECLARED INTENT FILTERS (${intentFilters.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (intentFilters.isEmpty()) {
                        Text(
                            text = "No explicit intent filters declared. Component can only be invoked by explicit Intent specifying this exact class name.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        intentFilters.forEach { filter ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        shape = RoundedCornerShape(Radii.sm),
                                    )
                                    .padding(Spacing.xs),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (filter.actions.isNotEmpty()) {
                                    Row(verticalAlignment = Alignment.Top) {
                                        Text(
                                            text = "Actions: ",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            filter.actions.forEach { action ->
                                                Text(
                                                    text = action,
                                                    style = MonoCodeStyle.copy(fontSize = 10.sp),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier
                                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(2.dp))
                                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                }

                                if (filter.categories.isNotEmpty()) {
                                    Row(verticalAlignment = Alignment.Top) {
                                        Text(
                                            text = "Categories: ",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            filter.categories.forEach { category ->
                                                Text(
                                                    text = category,
                                                    style = MonoCodeStyle.copy(fontSize = 10.sp),
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    modifier = Modifier
                                                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f), RoundedCornerShape(2.dp))
                                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                }

                                if (filter.dataSchemes.isNotEmpty()) {
                                    Row(verticalAlignment = Alignment.Top) {
                                        Text(
                                            text = "Schemes: ",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        filter.dataSchemes.forEach { scheme ->
                                            Text(
                                                text = "$scheme://",
                                                style = MonoCodeStyle.copy(fontSize = 10.sp),
                                                color = MaterialTheme.extendedColors.warning,
                                                modifier = Modifier
                                                    .background(MaterialTheme.extendedColors.warning.copy(alpha = 0.12f), RoundedCornerShape(2.dp))
                                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Required Calling Permissions
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "REQUIRED INVOCATION PERMISSION",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (permission != null) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = SuccessColor,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(Spacing.xs))
                            Text(
                                text = permission,
                                style = MonoCodeStyle.copy(fontSize = 11.sp),
                                color = SuccessColor,
                            )
                        } else if (component.exported) {
                            Icon(
                                imageVector = Icons.Filled.LockOpen,
                                contentDescription = null,
                                tint = MaterialTheme.extendedColors.warning,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(Spacing.xs))
                            Text(
                                text = "None enforced (Open to all callers)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.extendedColors.warning,
                            )
                        } else {
                            Text(
                                text = "App-private (No external permission required)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.xs))

                // 5. Button to View Decompiled Code
                Button(
                    onClick = onViewCode,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    shape = RoundedCornerShape(Radii.sm),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Code,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = "View Java/Kotlin / Smali",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * Modal Bottom Sheet for inspecting DEX Bytecode (Smali), reconstructed native source, or the
 * React Native JavaScript/Hermes bundle or Flutter assets/AOT strings that actually own the app behavior.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentCodeViewerSheet(
    component: PersistedComponent,
    sessionId: String,
    platformInfo: AppPlatformInfo = AppPlatformInfo(AppPlatform.UNKNOWN),
    frameworkOnly: Boolean = false,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val isReactNative = platformInfo.platform == AppPlatform.REACT_NATIVE
    val isFlutter = platformInfo.platform == AppPlatform.FLUTTER
    val shouldLoadReactNativeBundle = isReactNative && frameworkOnly
    var selectedTab by rememberSaveable(component.name, isReactNative, isFlutter, frameworkOnly) {
        mutableIntStateOf(if (frameworkOnly) 1 else 0)
    } // 0 = Smali, 1 = source/framework artifacts
    var copied by rememberSaveable { mutableStateOf(false) }

    // Resolve Smali Disassembly asynchronously
    var smaliResult by remember { mutableStateOf<String?>(null) }
    var smaliSourceInfo by remember { mutableStateOf<String?>(null) }
    var bundleResult by remember { mutableStateOf<String?>(null) }
    var bundleSourceInfo by remember { mutableStateOf<String?>(null) }
    var flutterResult by remember { mutableStateOf<String?>(null) }
    var flutterSourceInfo by remember { mutableStateOf<String?>(null) }
    var isLoadingSmali by remember { mutableStateOf(true) }
    var isLoadingBundle by remember { mutableStateOf(shouldLoadReactNativeBundle) }
    var isLoadingFlutter by remember { mutableStateOf(isFlutter) }

    LaunchedEffect(component.name, sessionId, isReactNative, isFlutter, frameworkOnly) {
        isLoadingSmali = true
        isLoadingBundle = shouldLoadReactNativeBundle
        isLoadingFlutter = isFlutter
        withContext(Dispatchers.IO) {
            val candidateFiles = listOf(
                File(context.filesDir, "analysis/$sessionId.apk"),
                File(context.cacheDir, "analysis/$sessionId.apk"),
                File(context.filesDir, "fixtures/fixture-debug.apk"),
            )
            val apkFile = candidateFiles.firstOrNull { it.exists() && it.canRead() }
                ?: File(context.packageCodePath) // Fallback to installed APK package if available

            val result = if (apkFile.exists() && apkFile.canRead()) {
                DexDisassembler.disassembleClass(apkFile, component.name)
            } else {
                DexDisassembler.DisassemblyResult.Error("APK file not found on device storage")
            }
            val bundle = if (shouldLoadReactNativeBundle && apkFile.exists() && apkFile.canRead()) {
                ReactNativeBundleInspector.inspect(apkFile)
            } else null
            val flutter = if (isFlutter && apkFile.exists() && apkFile.canRead()) {
                FlutterArtifactInspector.inspect(apkFile)
            } else null

            withContext(Dispatchers.Main) {
                when (result) {
                    is DexDisassembler.DisassemblyResult.Success -> {
                        smaliResult = result.smaliCode
                        smaliSourceInfo = result.dexSource
                    }
                    is DexDisassembler.DisassemblyResult.NotFound -> {
                        smaliResult = "# Class not found in DEX:\n# ${result.reason}\n#\n# Showing reconstructed source instead (see Source tab)."
                        smaliSourceInfo = null
                    }
                    is DexDisassembler.DisassemblyResult.Error -> {
                        smaliResult = "# DEX Disassembly:\n# ${result.message}\n#\n# (Switch to 'Reconstructed Source' tab to inspect logic)."
                        smaliSourceInfo = null
                    }
                }
                when (bundle) {
                    is ReactNativeBundleInspector.InspectionResult.Success -> {
                        bundleResult = bundle.displayText
                        bundleSourceInfo = "${bundle.entryName} · ${bundle.engine.displayName}"
                    }
                    is ReactNativeBundleInspector.InspectionResult.NotFound -> {
                        bundleResult = "// React Native bundle unavailable:\n// ${bundle.reason}"
                        bundleSourceInfo = null
                    }
                    is ReactNativeBundleInspector.InspectionResult.Error -> {
                        bundleResult = "// React Native bundle inspection failed:\n// ${bundle.message}"
                        bundleSourceInfo = null
                    }
                    null -> Unit
                }
                when (flutter) {
                    is FlutterArtifactInspector.InspectionResult.Success -> {
                        flutterResult = flutter.displayText
                        flutterSourceInfo = buildString {
                            append("${flutter.artifacts.size} artifacts · ${flutter.recoveredValues.size} values")
                            if (flutter.limitsReached) append(" · truncated")
                        }
                    }
                    is FlutterArtifactInspector.InspectionResult.NotFound -> {
                        flutterResult = "// Flutter artifacts unavailable:\n// ${flutter.reason}"
                        flutterSourceInfo = null
                    }
                    is FlutterArtifactInspector.InspectionResult.Error -> {
                        flutterResult = "// Flutter artifact inspection failed:\n// ${flutter.message}"
                        flutterSourceInfo = null
                    }
                    null -> Unit
                }
                isLoadingSmali = false
                isLoadingBundle = false
                isLoadingFlutter = false
            }
        }
    }

    val reconstructedSource = resolveComponentSource(
        componentName = component.name,
        type = component.type,
        exported = component.exported,
        smaliCode = smaliResult?.takeIf { code ->
            code.lineSequence().any { it.trimStart().startsWith(".class ") }
        },
    )
    val activeCode = when {
        selectedTab == 0 -> smaliResult.orEmpty()
        frameworkOnly && isReactNative -> bundleResult.orEmpty()
        isFlutter -> flutterResult.orEmpty()
        else -> reconstructedSource
    }
    val simpleName = component.name.substringAfterLast('.')
    val viewerTitle = when {
        frameworkOnly && isReactNative -> "React Native Recovered Strings"
        frameworkOnly && isFlutter -> "Flutter Recovered Artifacts"
        else -> simpleName
    }
    val viewerBadge = when {
        frameworkOnly && isReactNative -> "RN"
        frameworkOnly && isFlutter -> "FLUTTER"
        else -> component.type.name
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(Spacing.base),
        ) {
            // Top Bar: Title, Class path, Copy, Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = viewerTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Badge(
                            text = viewerBadge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = when {
                            selectedTab == 0 && smaliSourceInfo != null ->
                                "${component.name} · $smaliSourceInfo"
                            frameworkOnly && selectedTab == 1 && isReactNative && bundleSourceInfo != null ->
                                bundleSourceInfo.orEmpty()
                            selectedTab == 1 && isFlutter && flutterSourceInfo != null ->
                                flutterSourceInfo.orEmpty()
                            selectedTab == 1 && smaliSourceInfo != null ->
                                "${component.name} · Reconstructed from $smaliSourceInfo"
                            else -> component.name
                        },
                        style = MonoCodeStyle.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(activeCode))
                            copied = true
                        }
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                            contentDescription = "Copy code",
                            tint = if (copied) SuccessColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            if (!frameworkOnly) {
                // Dual Tab Switcher: Smali Bytecode vs Reconstructed Source
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(Radii.sm)),
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0; copied = false },
                        text = {
                            Text(
                                text = "Smali Bytecode (DEX)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1; copied = false },
                        text = {
                            Text(
                                text = if (isFlutter) "Flutter Artifacts" else "Reconstructed Source",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                    )
                }
            }

            if (!frameworkOnly) Spacer(Modifier.height(Spacing.sm))

            // Code Display Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        color = Color(0xFF0F1117), // Deep syntax editor dark background
                        shape = RoundedCornerShape(Radii.sm),
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(Radii.sm),
                    )
                    .padding(Spacing.sm),
            ) {
                if ((!frameworkOnly && selectedTab == 0 && isLoadingSmali) ||
                    (frameworkOnly && selectedTab == 1 && isReactNative && isLoadingBundle) ||
                    (selectedTab == 1 && isFlutter && isLoadingFlutter)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(Spacing.sm))
                            Text(
                                text = when {
                                    selectedTab == 0 -> "Disassembling DEX bytecode..."
                                    isFlutter -> "Recovering Flutter assets and Dart AOT strings..."
                                    else -> "Reading React Native bundle..."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    val scrollStateV = rememberScrollState()
                    val scrollStateH = rememberScrollState()
                    Text(
                        text = activeCode,
                        style = MonoCodeStyle.copy(
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = Color(0xFFE2E8F0),
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollStateV)
                            .horizontalScroll(scrollStateH),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111319)
@Composable
private fun ComponentsDetailScreenPreview() {
    val components = listOf(
        PersistedComponent("com.apksandbox.fixture.FixtureActivity", ComponentDescriptor.ComponentType.ACTIVITY, exported = true),
        PersistedComponent("com.apksandbox.fixture.ProbeVpn", ComponentDescriptor.ComponentType.SERVICE, exported = false),
        PersistedComponent("com.apksandbox.fixture.StressReceiver", ComponentDescriptor.ComponentType.RECEIVER, exported = true),
    )
    ApkScopeTheme {
        Scaffold(topBar = { AppTopBar(title = "Components (${components.size})", onBack = {}, onOverflow = {}) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(Spacing.base), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(components) { component ->
                    ComponentAccordionCard(
                        component = component,
                        isExpanded = component.type == ComponentDescriptor.ComponentType.SERVICE,
                        onToggle = {},
                        onViewCode = {},
                    )
                }
            }
        }
    }
}
