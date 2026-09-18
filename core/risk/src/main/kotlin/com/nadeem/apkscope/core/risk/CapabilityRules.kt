package com.nadeem.apkscope.core.risk

import com.nadeem.apkscope.core.model.ApkAnalysisInput
import com.nadeem.apkscope.core.model.RiskEvidence
import com.nadeem.apkscope.core.model.RiskFinding
import com.nadeem.apkscope.core.model.RiskSeverity

/**
 * Single-declared-capability rules (item 9) — each fires purely from one manifest permission's
 * presence in [ApkAnalysisInput.requestedPermissions]. Wording is deliberately restricted to
 * "declares"/"requests" (item 8/22): these are static findings about what the manifest *says*,
 * never a claim that the capability was actually exercised at runtime.
 *
 * Deliberately **not** implemented here (item 9's "only implement a rule when its input can be
 * reliably determined" + item 13's "do not manufacture certainty"): a notification-listener-service
 * rule — the current `ApkAnalyzer` does not capture which `Service` component (if any) extends
 * `NotificationListenerService` or is protected by `BIND_NOTIFICATION_LISTENER_SERVICE`, so there
 * is no reliable per-component signal to key a rule on yet.
 */
object AccessibilityServiceRule : RiskRule {
 override val id = RuleIds.ACCESSIBILITY_SERVICE
 override fun evaluate(input: ApkAnalysisInput): RiskFinding? {
  if (Permissions.BIND_ACCESSIBILITY_SERVICE !in input.requestedPermissions) return null
  return RiskFinding(
   ruleId = id, severity = RiskSeverity.HIGH,
   title = "Accessibility service capability declared",
   explanation = "This app declares ${Permissions.BIND_ACCESSIBILITY_SERVICE}, which allows an accessibility service to observe and interact with content displayed by other apps. Activating an accessibility service always requires explicit user authorization in system settings, but the capability's declaration is high-impact enough to surface on its own.",
   scoreContribution = 20,
   evidence = listOf(RiskEvidence("Declares ${Permissions.BIND_ACCESSIBILITY_SERVICE}")),
  )
 }
}

object OverlayPermissionRule : RiskRule {
 override val id = RuleIds.OVERLAY_PERMISSION
 override fun evaluate(input: ApkAnalysisInput): RiskFinding? {
  if (Permissions.SYSTEM_ALERT_WINDOW !in input.requestedPermissions) return null
  return RiskFinding(
   ruleId = id, severity = RiskSeverity.MEDIUM,
   title = "Overlay (draw-over-other-apps) capability declared",
   explanation = "This app declares ${Permissions.SYSTEM_ALERT_WINDOW}, which requests the ability to display content on top of other apps. Legitimate features (chat heads, picture-in-picture) commonly use this capability, but it can also be used to obscure or imitate another app's interface.",
   scoreContribution = 10,
   evidence = listOf(RiskEvidence("Declares ${Permissions.SYSTEM_ALERT_WINDOW}")),
  )
 }
}

object ReadSmsRule : RiskRule {
 override val id = RuleIds.READ_SMS
 override fun evaluate(input: ApkAnalysisInput): RiskFinding? {
  if (Permissions.READ_SMS !in input.requestedPermissions) return null
  return RiskFinding(
   ruleId = id, severity = RiskSeverity.HIGH,
   title = "SMS read capability declared",
   explanation = "This app declares ${Permissions.READ_SMS}, which requests the ability to read SMS messages stored on the device.",
   scoreContribution = 15,
   evidence = listOf(RiskEvidence("Declares ${Permissions.READ_SMS}")),
  )
 }
}

object ReceiveSmsRule : RiskRule {
 override val id = RuleIds.RECEIVE_SMS
 override fun evaluate(input: ApkAnalysisInput): RiskFinding? {
  if (Permissions.RECEIVE_SMS !in input.requestedPermissions) return null
  return RiskFinding(
   ruleId = id, severity = RiskSeverity.MEDIUM,
   title = "SMS receive capability declared",
   explanation = "This app declares ${Permissions.RECEIVE_SMS}, which requests the ability to be notified of incoming SMS messages as they arrive.",
   scoreContribution = 10,
   evidence = listOf(RiskEvidence("Declares ${Permissions.RECEIVE_SMS}")),
  )
 }
}

object SendSmsRule : RiskRule {
 override val id = RuleIds.SEND_SMS
 override fun evaluate(input: ApkAnalysisInput): RiskFinding? {
  if (Permissions.SEND_SMS !in input.requestedPermissions) return null
  return RiskFinding(
   ruleId = id, severity = RiskSeverity.HIGH,
   title = "SMS send capability declared",
   explanation = "This app declares ${Permissions.SEND_SMS}, which requests the ability to send SMS messages, including to premium-rate numbers.",
   scoreContribution = 15,
   evidence = listOf(RiskEvidence("Declares ${Permissions.SEND_SMS}")),
  )
 }
}

object RequestInstallPackagesRule : RiskRule {
 override val id = RuleIds.REQUEST_INSTALL_PACKAGES
 override fun evaluate(input: ApkAnalysisInput): RiskFinding? {
  if (Permissions.REQUEST_INSTALL_PACKAGES !in input.requestedPermissions) return null
  return RiskFinding(
   ruleId = id, severity = RiskSeverity.HIGH,
   title = "Package installation capability declared",
   explanation = "This app declares ${Permissions.REQUEST_INSTALL_PACKAGES}, which requests the ability to prompt installation of other APK files from within the app.",
   scoreContribution = 15,
   evidence = listOf(RiskEvidence("Declares ${Permissions.REQUEST_INSTALL_PACKAGES}")),
  )
 }
}

object BootReceiverRule : RiskRule {
 override val id = RuleIds.BOOT_RECEIVER
 override fun evaluate(input: ApkAnalysisInput): RiskFinding? {
  if (Permissions.RECEIVE_BOOT_COMPLETED !in input.requestedPermissions) return null
  return RiskFinding(
   ruleId = id, severity = RiskSeverity.LOW,
   title = "Starts after device boot",
   explanation = "This app declares ${Permissions.RECEIVE_BOOT_COMPLETED}, which requests the ability to start components automatically once the device finishes booting.",
   scoreContribution = 5,
   evidence = listOf(RiskEvidence("Declares ${Permissions.RECEIVE_BOOT_COMPLETED}")),
  )
 }
}

object BackgroundLocationRule : RiskRule {
 override val id = RuleIds.BACKGROUND_LOCATION
 override fun evaluate(input: ApkAnalysisInput): RiskFinding? {
  if (Permissions.ACCESS_BACKGROUND_LOCATION !in input.requestedPermissions) return null
  return RiskFinding(
   ruleId = id, severity = RiskSeverity.HIGH,
   title = "Background location capability declared",
   explanation = "This app declares ${Permissions.ACCESS_BACKGROUND_LOCATION}, which requests the ability to request device location while the app is not in the foreground.",
   scoreContribution = 15,
   evidence = listOf(RiskEvidence("Declares ${Permissions.ACCESS_BACKGROUND_LOCATION}")),
  )
 }
}

object QueryAllPackagesRule : RiskRule {
 override val id = RuleIds.QUERY_ALL_PACKAGES
 override fun evaluate(input: ApkAnalysisInput): RiskFinding? {
  if (Permissions.QUERY_ALL_PACKAGES !in input.requestedPermissions) return null
  return RiskFinding(
   ruleId = id, severity = RiskSeverity.MEDIUM,
   title = "Full package visibility capability declared",
   explanation = "This app declares ${Permissions.QUERY_ALL_PACKAGES}, which requests visibility into every other app installed on the device, beyond the platform's default package-visibility restrictions.",
   scoreContribution = 10,
   evidence = listOf(RiskEvidence("Declares ${Permissions.QUERY_ALL_PACKAGES}")),
  )
 }
}
