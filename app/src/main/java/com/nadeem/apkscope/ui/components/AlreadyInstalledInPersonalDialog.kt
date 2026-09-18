package com.nadeem.apkscope.ui.components

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.ui.theme.MonoCodeStyle
import com.nadeem.apkscope.ui.theme.Spacing

/**
 * Checks whether the given package is already installed in the personal profile.
 */
fun isPackageInstalledInPersonal(context: Context, packageName: String?): Boolean {
    if (packageName.isNullOrBlank()) return false
    return try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0) != null
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }
}

/**
 * Dialog presented when an app or package being prepared/installed in the sandbox is already
 * installed in the user's personal profile. Prompts user to uninstall from personal profile first.
 */
@Composable
fun AlreadyInstalledInPersonalDialog(
    isOpen: Boolean,
    packageName: String,
    appName: String? = null,
    onDismiss: () -> Unit,
) {
    if (!isOpen) return
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp),
            )
        },
        title = {
            Text(
                "App Already Installed",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column {
                Text(
                    "Check if app is already installed in personal profile then uninstall and retry.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "This app is currently installed in your personal profile. Running it inside the isolated sandbox requires removing it from the personal profile first to prevent package and security conflicts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (packageName.isNotBlank()) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = packageName,
                        style = MonoCodeStyle,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    try {
                        val intent = Intent(Intent.ACTION_DELETE).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        try {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                }
            ) {
                Text("Uninstall App")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        },
    )
}
