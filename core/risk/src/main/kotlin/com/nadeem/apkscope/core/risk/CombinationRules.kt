package com.nadeem.apkscope.core.risk

import com.nadeem.apkscope.core.model.ApkAnalysisInput
import com.nadeem.apkscope.core.model.RiskEvidence
import com.nadeem.apkscope.core.model.RiskFinding
import com.nadeem.apkscope.core.model.RiskSeverity

/**
 * Combination rules (item 10) — each requires *every* named capability to be declared; if only one
 * side is present the rule returns `null` (tested explicitly in `CombinationRulesTest`). A
 * combination's [RiskFinding.scoreContribution] is **added on top of** the individual rules' own
 * contributions, not a replacement for them — see this module's `README`-equivalent doc on
 * `DefaultRiskEngine` for the worked example and why that's the intentional policy, not accidental
 * double-counting (item 10's explicit instruction to document this choice).
 */

object AccessibilityOverlayCombinationRule : RiskRule {
 override val id = RuleIds.ACCESSIBILITY_OVERLAY_COMBINATION
 override fun evaluate(input: ApkAnalysisInput): RiskFinding? {
  val perms = input.requestedPermissions
  if (Permissions.BIND_ACCESSIBILITY_SERVICE !in perms || Permissions.SYSTEM_ALERT_WINDOW !in perms) return null
  return RiskFinding(
   ruleId = id, severity = RiskSeverity.HIGH,
   title = "Accessibility and overlay capabilities declared together",
   explanation = "This app declares both an accessibility-service capability and an overlay (draw-over-other-apps) capability. Together these expose the ability to both render content on top of other apps and observe/interact with on-screen content — the combination behind overlay/tapjacking-style attacks, distinct from either capability declared alone.",
   scoreContribution = 20,
   evidence = listOf(RiskEvidence("Declares ${Permissions.BIND_ACCESSIBILITY_SERVICE}"), RiskEvidence("Declares ${Permissions.SYSTEM_ALERT_WINDOW}")),
  )
 }
}

object AccessibilityBootCombinationRule : RiskRule {
 override val id = RuleIds.ACCESSIBILITY_BOOT_COMBINATION
 override fun evaluate(input: ApkAnalysisInput): RiskFinding? {
  val perms = input.requestedPermissions
  if (Permissions.BIND_ACCESSIBILITY_SERVICE !in perms || Permissions.RECEIVE_BOOT_COMPLETED !in perms) return null
  return RiskFinding(
   ruleId = id, severity = RiskSeverity.MEDIUM,
   title = "Accessibility and boot-start capabilities declared together",
   explanation = "This app declares both an accessibility-service capability and the ability to start after device boot. Together these expose the ability to re-establish a persistent, interactive accessibility capability across device restarts without further user action, distinct from either capability declared alone.",
   scoreContribution = 15,
   evidence = listOf(RiskEvidence("Declares ${Permissions.BIND_ACCESSIBILITY_SERVICE}"), RiskEvidence("Declares ${Permissions.RECEIVE_BOOT_COMPLETED}")),
  )
 }
}

object SmsInternetCombinationRule : RiskRule {
 override val id = RuleIds.SMS_INTERNET_COMBINATION
 override fun evaluate(input: ApkAnalysisInput): RiskFinding? {
  val perms = input.requestedPermissions
  val smsCapability = perms.containsAny(Permissions.READ_SMS, Permissions.RECEIVE_SMS, Permissions.SEND_SMS)
  if (!smsCapability || Permissions.INTERNET !in perms) return null
  val smsPermission = listOf(Permissions.READ_SMS, Permissions.RECEIVE_SMS, Permissions.SEND_SMS).first { it in perms }
  return RiskFinding(
   ruleId = id, severity = RiskSeverity.HIGH,
   title = "SMS and internet capabilities declared together",
   explanation = "This app declares both an SMS capability and internet access. Together these expose a path for SMS content to leave the device over the network, distinct from either capability declared alone.",
   scoreContribution = 15,
   evidence = listOf(RiskEvidence("Declares $smsPermission"), RiskEvidence("Declares ${Permissions.INTERNET}")),
  )
 }
}

object ContactsInternetCombinationRule : RiskRule {
 override val id = RuleIds.CONTACTS_INTERNET_COMBINATION
 override fun evaluate(input: ApkAnalysisInput): RiskFinding? {
  val perms = input.requestedPermissions
  if (Permissions.READ_CONTACTS !in perms || Permissions.INTERNET !in perms) return null
  return RiskFinding(
   ruleId = id, severity = RiskSeverity.MEDIUM,
   title = "Contacts and internet capabilities declared together",
   explanation = "This app declares both a contacts-read capability and internet access. Together these expose a path for contact data to leave the device over the network, distinct from either capability declared alone.",
   scoreContribution = 10,
   evidence = listOf(RiskEvidence("Declares ${Permissions.READ_CONTACTS}"), RiskEvidence("Declares ${Permissions.INTERNET}")),
  )
 }
}

object BackgroundLocationInternetCombinationRule : RiskRule {
 override val id = RuleIds.BACKGROUND_LOCATION_INTERNET_COMBINATION
 override fun evaluate(input: ApkAnalysisInput): RiskFinding? {
  val perms = input.requestedPermissions
  if (Permissions.ACCESS_BACKGROUND_LOCATION !in perms || Permissions.INTERNET !in perms) return null
  return RiskFinding(
   ruleId = id, severity = RiskSeverity.HIGH,
   title = "Background location and internet capabilities declared together",
   explanation = "This app declares both a background-location capability and internet access. Together these expose a path for device location to leave the device over the network even while the app is not in the foreground, distinct from either capability declared alone.",
   scoreContribution = 15,
   evidence = listOf(RiskEvidence("Declares ${Permissions.ACCESS_BACKGROUND_LOCATION}"), RiskEvidence("Declares ${Permissions.INTERNET}")),
  )
 }
}
