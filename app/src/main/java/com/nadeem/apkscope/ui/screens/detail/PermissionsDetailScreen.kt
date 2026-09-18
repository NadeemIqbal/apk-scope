package com.nadeem.apkscope.ui.screens.detail

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MiscellaneousServices
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadeem.apkscope.ui.common.sessionViewModel
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.Badge
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.components.EmptyState
import com.nadeem.apkscope.ui.components.SectionHeader
import com.nadeem.apkscope.ui.screens.staticresult.StaticResultViewModel
import com.nadeem.apkscope.ui.theme.ApkScopeTheme
import com.nadeem.apkscope.ui.theme.MonoCodeStyle
import com.nadeem.apkscope.ui.theme.Radii
import com.nadeem.apkscope.ui.theme.Spacing
import com.nadeem.apkscope.ui.theme.extendedColors

/** Detailed presentation model for a declared permission. */
data class PermissionModel(
    val permission: String,
    val title: String,
    val category: String,
    val protectionLevelText: String,
    val isDangerous: Boolean,
    val description: String,
    val icon: ImageVector,
)

/** Resolves detailed permission metadata with curated fallbacks and OS reflection. */
fun resolvePermissionModel(permission: String, context: Context): PermissionModel {
    val simpleName = permission.substringAfterLast('.')
    
    // Curated definitions for standard and security-relevant permissions
    val curated = when (permission) {
        "android.permission.ACCESS_FINE_LOCATION" -> PermissionModel(
            permission = permission,
            title = "Precise Location (GPS)",
            category = "Location",
            protectionLevelText = "Dangerous · Runtime Consent",
            isDangerous = true,
            description = "Allows the app to access precise location from sources such as GPS, Wi-Fi networks, and cell towers. Exposes exact physical coordinates of the device.",
            icon = Icons.Filled.LocationOn,
        )
        "android.permission.ACCESS_COARSE_LOCATION" -> PermissionModel(
            permission = permission,
            title = "Approximate Location",
            category = "Location",
            protectionLevelText = "Dangerous · Runtime Consent",
            isDangerous = true,
            description = "Allows the app to estimate device location to within a few city blocks using cell towers and Wi-Fi networks.",
            icon = Icons.Filled.LocationOn,
        )
        "android.permission.ACCESS_BACKGROUND_LOCATION" -> PermissionModel(
            permission = permission,
            title = "Background Location Access",
            category = "Location",
            protectionLevelText = "Dangerous · Runtime Consent",
            isDangerous = true,
            description = "Allows the app to track device location continuously while running in the background without active user interaction.",
            icon = Icons.Filled.LocationOn,
        )
        "android.permission.ACCESS_LOCAL_NETWORK" -> PermissionModel(
            permission = permission,
            title = "Local Network Access",
            category = "Network & Connectivity",
            protectionLevelText = "Dangerous · Runtime Consent",
            isDangerous = true,
            description = "Allows the app to discover, connect to, and exchange data with other devices and services connected to the local Wi-Fi or subnet.",
            icon = Icons.Filled.Lan,
        )
        "android.permission.CAMERA" -> PermissionModel(
            permission = permission,
            title = "Camera Access",
            category = "Hardware & Multimedia",
            protectionLevelText = "Dangerous · Runtime Consent",
            isDangerous = true,
            description = "Allows the app to capture photos and record video streams from device cameras at any time.",
            icon = Icons.Filled.CameraAlt,
        )
        "android.permission.RECORD_AUDIO" -> PermissionModel(
            permission = permission,
            title = "Microphone / Audio Recording",
            category = "Hardware & Multimedia",
            protectionLevelText = "Dangerous · Runtime Consent",
            isDangerous = true,
            description = "Allows the application to capture raw audio streams using the device microphone, enabling voice recording and ambient listening.",
            icon = Icons.Filled.Mic,
        )
        "android.permission.INTERNET" -> PermissionModel(
            permission = permission,
            title = "Full Internet Access",
            category = "Network & Connectivity",
            protectionLevelText = "Normal · Install-time",
            isDangerous = false,
            description = "Allows the application to create network sockets and transfer arbitrary outbound and inbound data over the Internet.",
            icon = Icons.Filled.Public,
        )
        "android.permission.ACCESS_NETWORK_STATE" -> PermissionModel(
            permission = permission,
            title = "View Network Connections",
            category = "Network & Connectivity",
            protectionLevelText = "Normal · Install-time",
            isDangerous = false,
            description = "Allows the application to view connectivity status, such as whether Wi-Fi or cellular mobile data is active.",
            icon = Icons.Filled.Wifi,
        )
        "android.permission.ACCESS_WIFI_STATE" -> PermissionModel(
            permission = permission,
            title = "View Wi-Fi State",
            category = "Network & Connectivity",
            protectionLevelText = "Normal · Install-time",
            isDangerous = false,
            description = "Allows the application to view information about Wi-Fi networking status, configured networks, and signal strength.",
            icon = Icons.Filled.Wifi,
        )
        "android.permission.FOREGROUND_SERVICE" -> PermissionModel(
            permission = permission,
            title = "Foreground Service",
            category = "System & Background",
            protectionLevelText = "Normal · Install-time",
            isDangerous = false,
            description = "Allows the app to run persistent background services with an active user-visible notification in the status bar.",
            icon = Icons.Filled.MiscellaneousServices,
        )
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" -> PermissionModel(
            permission = permission,
            title = "Foreground Service (Special Use)",
            category = "System & Background",
            protectionLevelText = "Special · Policy Governed",
            isDangerous = false,
            description = "Allows running a foreground service for specialized tasks not covered by standard system categories, subject to Google Play policy declaration.",
            icon = Icons.Filled.MiscellaneousServices,
        )
        "android.permission.REQUEST_DELETE_PACKAGES" -> PermissionModel(
            permission = permission,
            title = "Request Package Deletion",
            category = "App Management",
            protectionLevelText = "Normal · User Prompt",
            isDangerous = false,
            description = "Allows the application to trigger the system dialog requesting the uninstallation of other applications on the device.",
            icon = Icons.Filled.Delete,
        )
        "android.permission.POST_NOTIFICATIONS" -> PermissionModel(
            permission = permission,
            title = "Post Notifications",
            category = "Notifications",
            protectionLevelText = "Dangerous · Runtime Consent",
            isDangerous = true,
            description = "Allows the application to display user-facing alerts and banners in the notification drawer.",
            icon = Icons.Filled.Notifications,
        )
        "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS" -> PermissionModel(
            permission = permission,
            title = "Contacts Access",
            category = "Personal Data",
            protectionLevelText = "Dangerous · Runtime Consent",
            isDangerous = true,
            description = "Allows reading or modifying the user's address book contacts data.",
            icon = Icons.Filled.Contacts,
        )
        "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE" -> PermissionModel(
            permission = permission,
            title = "External Storage Access",
            category = "Storage",
            protectionLevelText = "Dangerous · Runtime Consent",
            isDangerous = true,
            description = "Allows reading or writing files on shared external storage volumes.",
            icon = Icons.Filled.Folder,
        )
        "android.permission.BLUETOOTH", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN" -> PermissionModel(
            permission = permission,
            title = "Bluetooth Access",
            category = "Hardware & Wireless",
            protectionLevelText = "Dangerous · Runtime Consent",
            isDangerous = true,
            description = "Allows discovering, pairing with, and transferring data to nearby Bluetooth devices.",
            icon = Icons.Filled.Bluetooth,
        )
        "android.permission.READ_PHONE_STATE" -> PermissionModel(
            permission = permission,
            title = "Phone State & Identity",
            category = "Telephony",
            protectionLevelText = "Dangerous · Runtime Consent",
            isDangerous = true,
            description = "Allows reading telephony state including cellular network info and phone call status.",
            icon = Icons.Filled.Phone,
        )
        else -> null
    }

    if (curated != null) return curated

    // Dynamic OS fallback
    return try {
        val pm = context.packageManager
        val info = pm.getPermissionInfo(permission, 0)
        val osLabel = info.loadLabel(pm).toString()
        val osDesc = info.loadDescription(pm)?.toString()
        @Suppress("DEPRECATION")
        val isDangerous = (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS
        
        PermissionModel(
            permission = permission,
            title = osLabel.takeIf { it.isNotBlank() } ?: simpleName.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
            category = info.group?.substringAfterLast('.') ?: "General",
            protectionLevelText = if (isDangerous) "Dangerous · Runtime Consent" else "Normal · Install-time",
            isDangerous = isDangerous,
            description = osDesc?.takeIf { it.isNotBlank() } ?: "Declared in AndroidManifest.xml. Enables the application to exercise specific capabilities or access protected system resources.",
            icon = if (isDangerous) Icons.Filled.Security else Icons.Filled.Public,
        )
    } catch (_: Exception) {
        PermissionModel(
            permission = permission,
            title = simpleName.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
            category = "Manifest",
            protectionLevelText = "Declared Permission",
            isDangerous = false,
            description = "Declared in AndroidManifest.xml. Enables the application to exercise specific capabilities or access protected system resources.",
            icon = Icons.Filled.Security,
        )
    }
}

/**
 * Permissions Tab with interactive accordion rows.
 * Default state is collapsed; expanding reveals human-readable labels, security impact,
 * protection level, and full manifest identifier.
 */
@Composable
fun PermissionsDetailScreen(sessionId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val viewModel = sessionViewModel { StaticResultViewModel(context.applicationContext as Application, sessionId) }
    val state by viewModel.uiState.collectAsState()
    val permissions = state.analysis?.permissions.orEmpty()
    
    // Accordion expansion state: set of expanded permission strings (default is empty = collapsed)
    var expandedPermissions by rememberSaveable { mutableStateOf(setOf<String>()) }

    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = "Permissions (${permissions.size})",
                onBack = onBack,
                onOverflow = {},
            )
        },
    ) { padding ->
        if (permissions.isEmpty()) {
            EmptyState(
                title = "Not determined",
                description = "Permission data is not available for this session.",
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }

        val permissionModels = remember(permissions) {
            permissions.map { resolvePermissionModel(it, context) }
        }
        val dangerousCount = remember(permissionModels) { permissionModels.count { it.isDangerous } }
        val normalCount = remember(permissionModels) { permissionModels.size - dangerousCount }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.base),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item {
                Spacer(Modifier.height(Spacing.xs))
                SectionHeader(title = "Declared Permissions", count = permissions.size)
                
                // Summary breakdown chip row
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs, bottom = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    if (dangerousCount > 0) {
                        Badge(
                            text = "$dangerousCount Dangerous",
                            color = MaterialTheme.extendedColors.warning,
                            showDot = true,
                        )
                    }
                    Badge(
                        text = "$normalCount Normal / Standard",
                        color = MaterialTheme.colorScheme.tertiary,
                        showDot = true,
                    )
                }
            }

            items(permissionModels, key = { it.permission }) { model ->
                val isExpanded = model.permission in expandedPermissions
                PermissionAccordionCard(
                    model = model,
                    isExpanded = isExpanded,
                    onToggle = {
                        expandedPermissions = if (isExpanded) {
                            expandedPermissions - model.permission
                        } else {
                            expandedPermissions + model.permission
                        }
                    },
                )
            }

            item { Spacer(Modifier.height(Spacing.xl)) }
        }
    }
}

/** Individual permission accordion card with smooth animated expansion. */
@Composable
private fun PermissionAccordionCard(
    model: PermissionModel,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotationDegrees by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "accordionChevronRotation",
    )

    BaseCard(
        modifier = modifier
            .clickable { onToggle() }
            .animateContentSize(),
    ) {
        // Top row: Category icon, readable name & short identifier, badges, chevron
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Category icon box
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(Radii.sm),
                        )
                        .border(
                            width = 1.dp,
                            color = if (model.isDangerous) MaterialTheme.extendedColors.warning.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(Radii.sm),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = model.icon,
                        contentDescription = null,
                        tint = if (model.isDangerous) MaterialTheme.extendedColors.warning
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(Modifier.width(Spacing.sm))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = model.permission,
                        style = MonoCodeStyle.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(Spacing.xs))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                if (model.isDangerous) {
                    Badge(
                        text = "DANGEROUS",
                        color = MaterialTheme.extendedColors.warning,
                    )
                } else {
                    Badge(
                        text = "NORMAL",
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

        // Expanded Accordion Body
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Description
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "CAPABILITY & SECURITY IMPACT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = model.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Metadata Details
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = "CATEGORY",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = model.category,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "PROTECTION LEVEL",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = model.protectionLevelText,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = if (model.isDangerous) MaterialTheme.extendedColors.warning
                            else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }

                // Full Manifest Identifier Box
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "MANIFEST CONSTANT",
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
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(Radii.sm),
                            )
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    ) {
                        Text(
                            text = model.permission,
                            style = MonoCodeStyle.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111319)
@Composable
private fun PermissionsRowPreview() {
    ApkScopeTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val modelCollapsed = PermissionModel(
                permission = "android.permission.ACCESS_FINE_LOCATION",
                title = "Precise Location (GPS)",
                category = "Location",
                protectionLevelText = "Dangerous · Runtime Consent",
                isDangerous = true,
                description = "Allows the app to access precise location from sources such as GPS, Wi-Fi networks, and cell towers.",
                icon = Icons.Filled.LocationOn,
            )
            PermissionAccordionCard(
                model = modelCollapsed,
                isExpanded = false,
                onToggle = {},
            )

            val modelExpanded = PermissionModel(
                permission = "android.permission.INTERNET",
                title = "Full Internet Access",
                category = "Network & Connectivity",
                protectionLevelText = "Normal · Install-time",
                isDangerous = false,
                description = "Allows the application to create network sockets and transfer data to remote servers over the Internet.",
                icon = Icons.Filled.Public,
            )
            PermissionAccordionCard(
                model = modelExpanded,
                isExpanded = true,
                onToggle = {},
            )
        }
    }
}

