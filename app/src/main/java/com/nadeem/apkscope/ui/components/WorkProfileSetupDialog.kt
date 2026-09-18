package com.nadeem.apkscope.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.ui.theme.Spacing

/**
 * Dialog presented when sandboxing is requested but no Android Work Profile is configured.
 * Offers direct one-tap initiation of Android's system provisioning wizard.
 */
@Composable
fun WorkProfileSetupDialog(
 isOpen: Boolean,
 isProvisioningAllowed: Boolean,
 onDismiss: () -> Unit,
 onConfirmSetup: () -> Unit,
) {
 if (!isOpen) return

 AlertDialog(
  onDismissRequest = onDismiss,
  icon = {
   Icon(
    if (isProvisioningAllowed) Icons.Filled.Badge else Icons.Filled.Warning,
    contentDescription = null,
    tint = if (isProvisioningAllowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    modifier = Modifier.size(28.dp),
   )
  },
  title = {
   Text(
    if (isProvisioningAllowed) "Set Up Secure Sandbox Profile" else "Work Profile Unavailable",
    style = MaterialTheme.typography.titleLarge,
   )
  },
  text = {
   Column {
    if (isProvisioningAllowed) {
     Text(
      "APK Scope requires an isolated Android Managed Work Profile to run and inspect applications safely.",
      style = MaterialTheme.typography.bodyMedium,
     )
     Spacer(Modifier.height(Spacing.sm))
     Text(
      "Android will guide you through setting up the Work Profile now. Once complete, your session will automatically continue.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
     )
    } else {
     Text(
      "An Android Managed Work Profile cannot be created on this device. This usually occurs if an existing work or enterprise profile is already present (e.g. corporate MDM) or if this device does not support managed profiles.",
      style = MaterialTheme.typography.bodyMedium,
     )
    }
   }
  },
  confirmButton = {
   if (isProvisioningAllowed) {
    Button(onClick = onConfirmSetup) {
     Text("Set Up Work Profile")
    }
   } else {
    Button(onClick = onDismiss) {
     Text("OK")
    }
   }
  },
  dismissButton = {
   if (isProvisioningAllowed) {
    TextButton(onClick = onDismiss) {
     Text("Cancel")
    }
   }
  },
 )
}
