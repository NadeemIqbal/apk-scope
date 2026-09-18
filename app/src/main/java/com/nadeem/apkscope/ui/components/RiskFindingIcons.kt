package com.nadeem.apkscope.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import com.nadeem.apkscope.core.risk.RuleIds

/** Item 26: every `core:risk` rule gets a purpose-specific icon by stable rule ID; anything unmapped (a future rule this UI hasn't been updated for yet) falls back to a neutral generic security icon — never the old Bluetooth-icon-for-everything fallback. */
fun riskFindingIcon(ruleId: String): ImageVector = when (ruleId) {
 RuleIds.ACCESSIBILITY_SERVICE -> Icons.Filled.Accessibility
 RuleIds.OVERLAY_PERMISSION -> Icons.Filled.PictureInPicture
 RuleIds.READ_SMS, RuleIds.RECEIVE_SMS, RuleIds.SEND_SMS -> Icons.Filled.Sms
 RuleIds.REQUEST_INSTALL_PACKAGES -> Icons.Filled.InstallMobile
 RuleIds.BOOT_RECEIVER -> Icons.Filled.PowerSettingsNew
 RuleIds.BACKGROUND_LOCATION -> Icons.Filled.LocationOn
 RuleIds.QUERY_ALL_PACKAGES -> Icons.Filled.Apps
 RuleIds.NATIVE_LIBRARIES_PRESENT -> Icons.Filled.Memory
 RuleIds.DEBUGGABLE_APK -> Icons.Filled.BugReport
 RuleIds.OLD_TARGET_SDK -> Icons.Filled.History
 RuleIds.SIGNATURE_VERIFICATION_FAILED -> Icons.Filled.GppMaybe
 RuleIds.MANY_EXPORTED_COMPONENTS -> Icons.AutoMirrored.Filled.OpenInNew
 RuleIds.ACCESSIBILITY_OVERLAY_COMBINATION,
 RuleIds.ACCESSIBILITY_BOOT_COMBINATION,
 RuleIds.SMS_INTERNET_COMBINATION,
 RuleIds.CONTACTS_INTERNET_COMBINATION,
 RuleIds.BACKGROUND_LOCATION_INTERNET_COMBINATION -> Icons.Filled.Warning
 else -> Icons.Filled.Security
}
