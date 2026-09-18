package com.nadeem.apkscope.ui.screens.https

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadeem.apkscope.core.network.https.HttpsCaptureState
import com.nadeem.apkscope.core.network.https.HttpsInspectionConfig
import com.nadeem.apkscope.core.network.https.HttpsInspectionStore
import com.nadeem.apkscope.core.network.https.HttpsTransaction
import com.nadeem.apkscope.sandbox.CaInstaller
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.Badge
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.theme.MonoCodeStyle
import com.nadeem.apkscope.ui.theme.Radii
import com.nadeem.apkscope.ui.theme.Spacing
import com.nadeem.apkscope.ui.theme.extendedColors

@Composable
fun HttpsInspectionScreen(
 onBack: () -> Unit,
 modifier: Modifier = Modifier
) {
 val context = LocalContext.current
 val transactions by HttpsInspectionStore.transactionsFlow.collectAsState()

 var isInspectionEnabled by remember { mutableStateOf(HttpsInspectionConfig.isEnabled) }
 var isCaInstalled by remember { mutableStateOf(false) }

 fun refreshCaStatus() {
  isCaInstalled = CaInstaller.isCaInstalled(context)
 }

 LaunchedEffect(Unit) {
  refreshCaStatus()
 }

 Scaffold(
  modifier = modifier,
  topBar = {
   AppTopBar(
    title = "HTTPS Inspection",
    eyebrow = "SANDBOX POC",
    onBack = onBack
   )
  }
 ) { padding ->
  LazyColumn(
   modifier = Modifier
    .fillMaxSize()
    .padding(padding)
    .padding(horizontal = Spacing.base, vertical = Spacing.xs),
   verticalArrangement = Arrangement.spacedBy(Spacing.sm)
  ) {
   item {
    PocControlsCard(
     isEnabled = isInspectionEnabled,
     onToggle = { enabled ->
      HttpsInspectionConfig.isEnabled = enabled
      isInspectionEnabled = enabled
     },
     isCaInstalled = isCaInstalled,
     onInstallCa = {
      val ok = CaInstaller.installCa(context)
      refreshCaStatus()
      val msg = if (ok) "CA installed into Work Profile" else "Failed to install CA via DPM"
      Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
     },
     onUninstallCa = {
      CaInstaller.uninstallCa(context)
      refreshCaStatus()
      Toast.makeText(context, "CA removed from Work Profile", Toast.LENGTH_SHORT).show()
     },
     onResetPoc = {
      CaInstaller.resetPoc(context)
      isInspectionEnabled = false
      refreshCaStatus()
      Toast.makeText(context, "POC reset: inspection stopped, CA removed, captures cleared", Toast.LENGTH_SHORT).show()
     },
     onClearCaptures = {
      HttpsInspectionStore.clear()
     }
    )
   }

   item {
    Row(
     modifier = Modifier
      .fillMaxWidth()
      .padding(top = Spacing.xs),
     horizontalArrangement = Arrangement.SpaceBetween,
     verticalAlignment = Alignment.CenterVertically
    ) {
     Text(
      text = "Captured Transactions (${transactions.size}/${HttpsInspectionStore.MAX_TRANSACTIONS})",
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface
     )
     if (transactions.isNotEmpty()) {
      TextButton(onClick = { HttpsInspectionStore.clear() }) {
       Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
       Spacer(Modifier.width(4.dp))
       Text("Clear", style = MaterialTheme.typography.labelMedium)
      }
     }
    }
   }

   if (transactions.isEmpty()) {
    item {
     EmptyCapturesCard(isInspectionEnabled, isCaInstalled)
    }
   } else {
    items(transactions, key = { it.id }) { tx ->
     TransactionCard(tx)
    }
   }
  }
 }
}

@Composable
private fun PocControlsCard(
 isEnabled: Boolean,
 onToggle: (Boolean) -> Unit,
 isCaInstalled: Boolean,
 onInstallCa: () -> Unit,
 onUninstallCa: () -> Unit,
 onResetPoc: () -> Unit,
 onClearCaptures: () -> Unit
) {
 BaseCard {
  Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
   Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
   ) {
    Column {
     Text(
      text = "HTTPS Traffic Inspection",
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface
     )
     Text(
      text = if (isEnabled) "Active · Intercepting target test hosts" else "Disabled by default",
      style = MaterialTheme.typography.bodySmall,
      color = if (isEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
     )
    }
    Switch(
     checked = isEnabled,
     onCheckedChange = onToggle
    )
   }

   HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

   Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
   ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
     Icon(
      Icons.Filled.Security,
      contentDescription = null,
      tint = if (isCaInstalled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
      modifier = Modifier.size(18.dp)
     )
     Spacer(Modifier.width(Spacing.xs))
     Text(
      text = "Work Profile CA:",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface
     )
     Spacer(Modifier.width(Spacing.xs))
     Badge(
      text = if (isCaInstalled) "Installed" else "Not Installed",
      color = if (isCaInstalled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
      showDot = true
     )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
     if (!isCaInstalled) {
      Button(
       onClick = onInstallCa,
       contentPadding = ButtonDefaults.TextButtonContentPadding
      ) {
       Text("Install CA", style = MaterialTheme.typography.labelSmall)
      }
     } else {
      OutlinedButton(
       onClick = onUninstallCa,
       contentPadding = ButtonDefaults.TextButtonContentPadding
      ) {
       Text("Remove CA", style = MaterialTheme.typography.labelSmall)
      }
     }
    }
   }

   // Trust Notice
   Row(
    modifier = Modifier
     .fillMaxWidth()
     .background(
      MaterialTheme.colorScheme.surfaceContainer,
      RoundedCornerShape(Radii.md)
     )
     .padding(Spacing.sm),
    verticalAlignment = Alignment.Top
   ) {
    Icon(
     Icons.Filled.Info,
     contentDescription = null,
     tint = MaterialTheme.colorScheme.onSurfaceVariant,
     modifier = Modifier.size(16.dp).padding(top = 2.dp)
    )
    Spacer(Modifier.width(Spacing.xs))
    Text(
     text = "Target App Trust: Only applications configured to trust user CAs (such as the fixture debug build) will succeed. Production APKs and apps with certificate pinning will reject the inspection CA.",
     style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
     color = MaterialTheme.colorScheme.onSurfaceVariant
    )
   }

   Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.End
   ) {
    TextButton(
     onClick = onResetPoc,
     colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) {
     Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
     Spacer(Modifier.width(4.dp))
     Text("Reset POC", style = MaterialTheme.typography.labelSmall)
    }
   }
  }
 }
}

@Composable
private fun EmptyCapturesCard(isEnabled: Boolean, isCaInstalled: Boolean) {
 BaseCard {
  Column(
   modifier = Modifier
    .fillMaxWidth()
    .padding(Spacing.base),
   horizontalAlignment = Alignment.CenterHorizontally,
   verticalArrangement = Arrangement.Center
  ) {
   Icon(
    Icons.Filled.Lock,
    contentDescription = null,
    tint = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.size(40.dp)
   )
   Spacer(Modifier.height(Spacing.sm))
   Text(
    text = "No HTTPS Transactions Captured",
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onSurface
   )
   Spacer(Modifier.height(Spacing.xs))
   Text(
    text = when {
     !isEnabled -> "Enable the HTTPS inspection toggle above to start capturing traffic."
     !isCaInstalled -> "Install the inspection CA to allow the fixture app to trust intercepted TLS."
     else -> "Trigger an HTTPS request in the sandboxed fixture app to view decrypted JSON payloads here."
    },
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 3
   )
  }
 }
}

@Composable
private fun TransactionCard(tx: HttpsTransaction) {
 var expanded by remember { mutableStateOf(false) }

 val stateColor = when (tx.state) {
  HttpsCaptureState.DECODED -> MaterialTheme.colorScheme.tertiary
  HttpsCaptureState.ENCRYPTED -> MaterialTheme.colorScheme.primary
  HttpsCaptureState.TLS_HANDSHAKE_FAILED -> MaterialTheme.colorScheme.error
  HttpsCaptureState.UNSUPPORTED_PROTOCOL -> MaterialTheme.extendedColors.warning
  HttpsCaptureState.TRUNCATED -> MaterialTheme.extendedColors.warning
 }

 BaseCard(modifier = Modifier.clickable { expanded = !expanded }) {
  Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
   Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
   ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
     Badge(
      text = tx.method,
      color = MaterialTheme.colorScheme.primary,
      filled = true
     )
     Spacer(Modifier.width(Spacing.xs))
     if (tx.statusCode != null) {
      val statusColor = if (tx.statusCode in 200..299) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
      Badge(
       text = "${tx.statusCode} ${tx.statusMessage ?: ""}".trim(),
       color = statusColor
      )
      Spacer(Modifier.width(Spacing.xs))
     }
     Badge(
      text = tx.state.displayName,
      color = stateColor,
      showDot = true
     )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
     if (tx.durationMs > 0) {
      Text(
       text = "${tx.durationMs}ms",
       style = MonoCodeStyle.copy(fontSize = 11.sp),
       color = MaterialTheme.colorScheme.onSurfaceVariant
      )
     }
     Icon(
      imageVector = if (expanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(20.dp)
     )
    }
   }

   Text(
    text = tx.url,
    style = MonoCodeStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    color = MaterialTheme.colorScheme.onSurface,
    maxLines = if (expanded) Int.MAX_VALUE else 1,
    overflow = TextOverflow.Ellipsis
   )

   AnimatedVisibility(visible = expanded) {
    Column(
     modifier = Modifier.padding(top = Spacing.xs),
     verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
     HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

     val failure = tx.failureDetails
     if (failure != null) {
      Card(
       colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
       shape = RoundedCornerShape(Radii.sm)
      ) {
       Row(
        modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
        verticalAlignment = Alignment.Top
       ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Spacing.xs))
        Column {
         Text("Failure Details", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
         Text(failure, style = MonoCodeStyle.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onErrorContainer)
        }
       }
      }
     }

     if (tx.requestHeaders.isNotEmpty()) {
      HeaderBlock(title = "Request Headers", headers = tx.requestHeaders)
     }

     val reqBody = tx.requestBody
     if (!reqBody.isNullOrEmpty()) {
      PayloadBlock(
       title = "Request Body",
       byteCount = tx.requestBodyBytes,
       content = reqBody,
       isTruncated = false
      )
     }

     if (tx.responseHeaders.isNotEmpty()) {
      HeaderBlock(title = "Response Headers", headers = tx.responseHeaders)
     }

     val respBody = tx.responseBody
     if (!respBody.isNullOrEmpty()) {
      PayloadBlock(
       title = "Response Body",
       byteCount = tx.responseBodyBytes,
       content = respBody,
       isTruncated = tx.isTruncated
      )
     }
    }
   }
  }
 }
}

@Composable
private fun HeaderBlock(title: String, headers: Map<String, String>) {
 Column(modifier = Modifier.fillMaxWidth()) {
  Text(
   text = title,
   style = MaterialTheme.typography.labelSmall,
   fontWeight = FontWeight.Bold,
   color = MaterialTheme.colorScheme.secondary
  )
  Spacer(Modifier.height(4.dp))
  Column(
   modifier = Modifier
    .fillMaxWidth()
    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(Radii.sm))
    .padding(Spacing.xs)
  ) {
   headers.forEach { (name, value) ->
    Row(modifier = Modifier.fillMaxWidth()) {
     Text(
      text = "$name: ",
      style = MonoCodeStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
      color = MaterialTheme.colorScheme.onSurfaceVariant
     )
     Text(
      text = value,
      style = MonoCodeStyle.copy(
       fontSize = 10.sp,
       color = if (value == "[REDACTED]") MaterialTheme.extendedColors.warning else MaterialTheme.colorScheme.onSurface
      )
     )
    }
   }
  }
 }
}

@Composable
private fun PayloadBlock(
 title: String,
 byteCount: Int,
 content: String,
 isTruncated: Boolean
) {
 Column(modifier = Modifier.fillMaxWidth()) {
  Row(
   modifier = Modifier.fillMaxWidth(),
   horizontalArrangement = Arrangement.SpaceBetween,
   verticalAlignment = Alignment.CenterVertically
  ) {
   Text(
    text = "$title ($byteCount bytes)",
    style = MaterialTheme.typography.labelSmall,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.secondary
   )
   if (isTruncated) {
    Badge(text = "Truncated (64 KiB cap)", color = MaterialTheme.extendedColors.warning)
   }
  }
  Spacer(Modifier.height(4.dp))
  Box(
   modifier = Modifier
    .fillMaxWidth()
    .heightIn(max = 200.dp)
    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(Radii.sm))
    .padding(Spacing.xs)
    .verticalScroll(rememberScrollState())
    .horizontalScroll(rememberScrollState())
  ) {
   Text(
    text = content,
    style = MonoCodeStyle.copy(fontSize = 10.sp),
    color = MaterialTheme.colorScheme.onSurface
   )
  }
 }
}
