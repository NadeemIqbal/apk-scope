package com.nadeem.apkscope.core.risk.audit

import com.nadeem.apkscope.core.model.ApkAnalysisInput
import com.nadeem.apkscope.core.risk.Permissions
import com.nadeem.apkscope.core.risk.containsAny

/**
 * FINDING_DETECTED (HIGH severity) when the analyzed APK is a debug build — see
 * docs/SECURITY_AUDIT_RULES.md#AUDIT_DEBUGGABLE_BUILD. A debuggable release should never reach end
 * users; unlike static-v1's additive `STATIC_DEBUGGABLE_APK` weight, this is a direct, individually
 * reportable finding. Confidence is HIGH: the flag is read directly, no inference involved.
 */
object DebuggableBuildRule : AuditRule {
 override val id = AuditRuleIds.DEBUGGABLE_BUILD
 override fun evaluate(input: ApkAnalysisInput) = AuditFinding(
  ruleId = id,
  outcome = if (input.debuggable) AuditOutcome.FINDING_DETECTED else AuditOutcome.CHECK_PASSED,
  severity = if (input.debuggable) Severity.HIGH else Severity.INFO,
  confidence = Confidence.HIGH,
  title = "Debuggable build",
  detail = if (input.debuggable) {
   "android:debuggable=true — a debugger can attach to this app's process, and its data is not protected by the platform's release-build hardening."
  } else {
   "Not declared debuggable."
  },
  remediation = if (input.debuggable) {
   "Remove android:debuggable (or set it to false) before releasing this build."
  } else {
   "No action needed."
  },
 )
}

/**
 * FINDING_DETECTED (CRITICAL) when the APK's signature could not be verified — package integrity is
 * not established, so every other finding in this report describes content whose origin is
 * unconfirmed.
 */
object SignatureIntegrityRule : AuditRule {
 override val id = AuditRuleIds.SIGNATURE_INTEGRITY
 override fun evaluate(input: ApkAnalysisInput) = AuditFinding(
  ruleId = id,
  outcome = if (input.signatureVerified) AuditOutcome.CHECK_PASSED else AuditOutcome.FINDING_DETECTED,
  severity = if (input.signatureVerified) Severity.INFO else Severity.CRITICAL,
  confidence = Confidence.HIGH,
  title = "Signature integrity",
  detail = if (input.signatureVerified) {
   "APK signature verified."
  } else {
   "APK signature could not be verified — this package's integrity and publisher identity are not established."
  },
  remediation = if (input.signatureVerified) {
   "No action needed."
  } else {
   "Re-obtain this APK from a trusted source; do not trust results from an artifact whose signature cannot be verified."
  },
 )
}

/**
 * NEEDS_REVIEW (not FINDING_DETECTED) when a majority of the APK's declared components are
 * exported — an exported component alone is not a vulnerability (per this catalog's explicit
 * applicability rule), only a widened surface worth a human looking at it. Distinct from static-v1's
 * absolute-count `STATIC_MANY_EXPORTED_COMPONENTS`, which uses a ratio instead of a count.
 */
object ExportedSurfaceRatioRule : AuditRule {
 override val id = AuditRuleIds.EXPORTED_SURFACE_RATIO
 override fun evaluate(input: ApkAnalysisInput): AuditFinding {
  val total = input.totalComponentCount
  val exported = input.exportedComponentCount
  val majorityExported = total > 0 && exported * 2 > total
  return AuditFinding(
   ruleId = id,
   outcome = if (majorityExported) AuditOutcome.NEEDS_REVIEW else AuditOutcome.CHECK_PASSED,
   severity = if (majorityExported) Severity.MEDIUM else Severity.INFO,
   confidence = Confidence.HIGH,
   title = "Exported component surface",
   detail = if (majorityExported) {
    "$exported of $total declared components are exported — more than half. An exported component alone is not a vulnerability; review whether each one enforces its own access control (permission, signature, or explicit caller check)."
   } else {
    "$exported of $total declared components are exported."
   },
   remediation = if (majorityExported) {
    "Review each exported component's own access control; add android:permission or an explicit signature-level check where public exposure isn't required."
   } else {
    "No action needed."
   },
  )
 }
}

/**
 * FINDING_DETECTED (HIGH) when both accessibility-service and overlay capabilities are declared
 * together — the specific combination behind tapjacking/overlay-based attacks. Mirrors combined-v1's
 * `COMBINED_ACCESSIBILITY_OVERLAY_NETWORK` pairing but as a standalone static finding, independent
 * of any runtime signal.
 */
object AccessibilityOverlayTapjackingRule : AuditRule {
 override val id = AuditRuleIds.ACCESSIBILITY_OVERLAY_TAPJACKING
 override fun evaluate(input: ApkAnalysisInput): AuditFinding {
  val hasBoth = input.requestedPermissions.containsAny(Permissions.BIND_ACCESSIBILITY_SERVICE) &&
   input.requestedPermissions.containsAny(Permissions.SYSTEM_ALERT_WINDOW)
  return AuditFinding(
   ruleId = id,
   outcome = if (hasBoth) AuditOutcome.FINDING_DETECTED else AuditOutcome.CHECK_PASSED,
   severity = if (hasBoth) Severity.HIGH else Severity.INFO,
   confidence = Confidence.HIGH,
   title = "Accessibility + overlay combination",
   detail = if (hasBoth) {
    "Declares both BIND_ACCESSIBILITY_SERVICE and SYSTEM_ALERT_WINDOW — together these can render content on top of other apps while also observing and interacting with on-screen content, the combination behind overlay/tapjacking-style attacks."
   } else {
    "Does not declare both accessibility-service and overlay capabilities."
   },
   remediation = if (hasBoth) {
    "Avoid requesting both unless both are functionally required; if both are needed, document why and consider HIDE_OVERLAY_WINDOWS/setHideOverlayWindows (API 31+)."
   } else {
    "No action needed."
   },
  )
 }
}

/**
 * NEEDS_REVIEW (MEDIUM) when SMS read/receive/send and INTERNET are both declared — the pairing
 * needed to intercept and exfiltrate SMS content (commonly 2FA codes) off-device. NEEDS_REVIEW
 * rather than FINDING_DETECTED: many legitimate apps (OTP autofill, SMS-based verification) declare
 * exactly this pairing for a real, non-malicious purpose.
 */
object SmsInternetExfilSurfaceRule : AuditRule {
 override val id = AuditRuleIds.SMS_INTERNET_EXFIL_SURFACE
 override fun evaluate(input: ApkAnalysisInput): AuditFinding {
  val hasSms = input.requestedPermissions.containsAny(Permissions.READ_SMS, Permissions.RECEIVE_SMS, Permissions.SEND_SMS)
  val hasInternet = input.requestedPermissions.containsAny(Permissions.INTERNET)
  val flagged = hasSms && hasInternet
  return AuditFinding(
   ruleId = id,
   outcome = if (flagged) AuditOutcome.NEEDS_REVIEW else AuditOutcome.CHECK_PASSED,
   severity = if (flagged) Severity.MEDIUM else Severity.INFO,
   confidence = Confidence.MEDIUM,
   title = "SMS + network exfiltration surface",
   detail = if (flagged) {
    "Declares SMS read/receive/send alongside INTERNET — this app can read SMS content and also has a network path to send it off-device. Common legitimate use (OTP autofill) exists; review the app's actual purpose before treating this as a finding."
   } else {
    "Does not declare both SMS access and INTERNET."
   },
   remediation = if (flagged) {
    "If the goal is OTP autofill, use the SMS Retriever API instead of broad READ_SMS to scope access more tightly."
   } else {
    "No action needed."
   },
  )
 }
}

/**
 * NEEDS_REVIEW (LOW) when boot-persistence and INTERNET are both declared — the app can start
 * without user action and reach the network on every boot. Common for legitimate background
 * services; flagged for review, not asserted as a finding.
 */
object BootPersistenceNetworkRule : AuditRule {
 override val id = AuditRuleIds.BOOT_PERSISTENCE_NETWORK
 override fun evaluate(input: ApkAnalysisInput): AuditFinding {
  val flagged = input.requestedPermissions.containsAny(Permissions.RECEIVE_BOOT_COMPLETED) &&
   input.requestedPermissions.containsAny(Permissions.INTERNET)
  return AuditFinding(
   ruleId = id,
   outcome = if (flagged) AuditOutcome.NEEDS_REVIEW else AuditOutcome.CHECK_PASSED,
   severity = if (flagged) Severity.LOW else Severity.INFO,
   confidence = Confidence.MEDIUM,
   title = "Boot persistence + network",
   detail = if (flagged) {
    "Declares RECEIVE_BOOT_COMPLETED alongside INTERNET — this app can automatically start on every device boot and has a network path, without any user-initiated launch."
   } else {
    "Does not declare both boot-persistence and INTERNET."
   },
   remediation = if (flagged) {
    "Document the legitimate need for auto-start plus network access; consider deferring network access until explicit user interaction."
   } else {
    "No action needed."
   },
  )
 }
}

/**
 * NEEDS_REVIEW (LOW, HIGH confidence) when the declared target SDK predates Android 10 (API 29) —
 * misses several platform privacy/permission-scoping defaults tightened from that release onward
 * (scoped storage, background-location prompts, foreground-service typing). This rule's threshold is
 * independent of static-v1's `STATIC_OLD_TARGET_SDK`.
 */
object OutdatedTargetSdkRule : AuditRule {
 override val id = AuditRuleIds.OUTDATED_TARGET_SDK
 private const val MIN_HARDENED_TARGET_SDK = 29
 override fun evaluate(input: ApkAnalysisInput): AuditFinding {
  val outdated = input.targetSdkVersion < MIN_HARDENED_TARGET_SDK
  return AuditFinding(
   ruleId = id,
   outcome = if (outdated) AuditOutcome.NEEDS_REVIEW else AuditOutcome.CHECK_PASSED,
   severity = if (outdated) Severity.LOW else Severity.INFO,
   confidence = Confidence.HIGH,
   title = "Target SDK predates privacy hardening",
   detail = if (outdated) {
    "targetSdkVersion=${input.targetSdkVersion}, below API $MIN_HARDENED_TARGET_SDK (Android 10) — this app opts out of several platform privacy and permission-scoping defaults introduced from that release onward."
   } else {
    "targetSdkVersion=${input.targetSdkVersion}."
   },
   remediation = if (outdated) {
    "Raise targetSdkVersion to the current supported range, then re-test — raising it can itself change runtime behavior (e.g. Network Security Config defaults)."
   } else {
    "No action needed."
   },
  )
 }
}

/**
 * NOT_TESTED (not FINDING_DETECTED or CHECK_PASSED) when native code is present — this is the
 * catalog stating honestly that it did not check something, not that it checked and found nothing.
 * This catalog's static checks cannot see inside native library internals, so any behavior
 * implemented there stays untested until a guided runtime session (Milestone 10, Phase 10.2)
 * observes it directly (see docs/SUPERVISOR_CONTEXT.md item 18, declaration versus observation).
 */
object NativeCodeUnverifiedRule : AuditRule {
 override val id = AuditRuleIds.NATIVE_CODE_UNVERIFIED
 override fun evaluate(input: ApkAnalysisInput): AuditFinding {
  val present = input.nativeLibraryAbis.isNotEmpty()
  return AuditFinding(
   ruleId = id,
   outcome = if (present) AuditOutcome.NOT_TESTED else AuditOutcome.CHECK_PASSED,
   severity = Severity.INFO,
   confidence = Confidence.HIGH,
   title = "Native code present",
   detail = if (present) {
    "Native libraries present (${input.nativeLibraryAbis.joinToString()}) — this catalog's static checks cannot see inside native code; any behavior implemented there is untested until a guided runtime session observes it."
   } else {
    "No native libraries declared."
   },
   remediation = if (present) {
    "Not applicable as a fix — the actionable follow-up is exercising native-code paths via a guided runtime session, not changing the app."
   } else {
    "No action needed."
   },
  )
 }
}

/**
 * FINDING_DETECTED (HIGH, MEDIUM confidence) when the manifest declares
 * `android:usesCleartextTraffic="true"` — a static, declared-configuration check. Confidence is
 * MEDIUM, not HIGH: the declaration permits cleartext, it does not prove any request actually used
 * it — that requires the planned `AUDIT_RUNTIME_CLEARTEXT_OBSERVED` guided-session rule (Phase
 * 10.2+), not yet implemented.
 *
 * Correction (Phase 10.3, found in review): [ApkAnalysisInput.staticSecurityFieldsKnown] is checked
 * first — when `false`, [input.usesCleartextTraffic] is a pre-Security-Audit row's migration-era
 * default, not a real analyzer-read value, and reporting `CHECK_PASSED` for it would be an
 * unsupportable false pass. Reports [AuditOutcome.NOT_TESTED] instead in that case.
 */
object CleartextTrafficEnabledRule : AuditRule {
 override val id = AuditRuleIds.CLEARTEXT_TRAFFIC_ENABLED
 override fun evaluate(input: ApkAnalysisInput): AuditFinding {
  if (!input.staticSecurityFieldsKnown) {
   return AuditFinding(
    ruleId = id,
    outcome = AuditOutcome.NOT_TESTED,
    severity = Severity.INFO,
    confidence = Confidence.LOW,
    title = "Cleartext traffic permitted by declaration",
    detail = "This analysis predates Security Audit's usesCleartextTraffic field — the real manifest value was never captured for it, so it cannot be reported as pass or fail without re-importing the APK.",
    remediation = "Re-import this APK to capture a real value for this check.",
   )
  }
  return AuditFinding(
   ruleId = id,
   outcome = if (input.usesCleartextTraffic) AuditOutcome.FINDING_DETECTED else AuditOutcome.CHECK_PASSED,
   severity = if (input.usesCleartextTraffic) Severity.HIGH else Severity.INFO,
   confidence = Confidence.MEDIUM,
   title = "Cleartext traffic permitted by declaration",
   detail = if (input.usesCleartextTraffic) {
    "android:usesCleartextTraffic resolves to true — this app declares that it may send plaintext, unencrypted HTTP traffic. Confirms only the declaration; whether any request actually goes out in cleartext requires a guided runtime session (AUDIT_RUNTIME_CLEARTEXT_OBSERVED, not yet implemented)."
   } else {
    "android:usesCleartextTraffic resolves to false."
   },
   remediation = if (input.usesCleartextTraffic) {
    "Set android:usesCleartextTraffic=\"false\" (or omit it on API 28+, where false is the default) and use HTTPS exclusively."
   } else {
    "No action needed."
   },
  )
 }
}

/**
 * NEEDS_REVIEW (LOW, HIGH confidence) when the app declares `android:allowBackup="true"` (or the
 * platform default, which is `true` when the manifest omits the attribute) — app data may be
 * extracted via `adb backup` or the platform's auto-backup mechanism on a device where that path is
 * reachable. Not a finding: backup is a legitimate, common feature; this catalog does not have
 * enough static context to know whether the specific data this app stores warrants disabling it.
 *
 * Correction (Phase 10.3, found in review): same [ApkAnalysisInput.staticSecurityFieldsKnown] check
 * as [CleartextTrafficEnabledRule] — a defaulted `true` here would only ever over-flag as
 * NEEDS_REVIEW (the safe direction), never produce a false pass, but reporting [AuditOutcome.NOT_TESTED]
 * for an unknown value is still the honest result rather than a guess presented as a real check.
 */
object BackupEnabledRule : AuditRule {
 override val id = AuditRuleIds.BACKUP_ENABLED
 override fun evaluate(input: ApkAnalysisInput): AuditFinding {
  if (!input.staticSecurityFieldsKnown) {
   return AuditFinding(
    ruleId = id,
    outcome = AuditOutcome.NOT_TESTED,
    severity = Severity.INFO,
    confidence = Confidence.LOW,
    title = "Backup allowed",
    detail = "This analysis predates Security Audit's allowBackup field — the real manifest value was never captured for it, so it cannot be reported as pass or review without re-importing the APK.",
    remediation = "Re-import this APK to capture a real value for this check.",
   )
  }
  return AuditFinding(
   ruleId = id,
   outcome = if (input.allowBackup) AuditOutcome.NEEDS_REVIEW else AuditOutcome.CHECK_PASSED,
   severity = if (input.allowBackup) Severity.LOW else Severity.INFO,
   confidence = Confidence.HIGH,
   title = "Backup allowed",
   detail = if (input.allowBackup) {
    "android:allowBackup resolves to true — this app's data may be extracted via adb backup or the platform's auto-backup mechanism where reachable. Not inherently a defect; review whether the data this app stores warrants disabling it or scoping it with backup rules."
   } else {
    "android:allowBackup resolves to false."
   },
   remediation = if (input.allowBackup) {
    "Set android:allowBackup=\"false\", or scope backup content with android:fullBackupContent/backup_rules.xml to exclude sensitive files."
   } else {
    "No action needed."
   },
  )
 }
}

/**
 * Milestone 10, Phase 10.3, MS10-NET01 correction — the real `network_security_config.xml` *content*
 * (`core:staticanalysis.NetworkSecurityConfigParser`, resolved through `ResourceTableParser`), not
 * just the manifest attribute's presence. Reports [AuditOutcome.NOT_TESTED] whenever the config's
 * content could not actually be inspected — Section 3's explicit "do not claim absent pinning when
 * the configuration could not be inspected" applies here as directly as anywhere in this catalog.
 *
 * Priority, in order:
 * 1. No `android:networkSecurityConfig` attribute at all → [AuditOutcome.CHECK_PASSED] (INFO) — a
 *    real, neutral fact (platform defaults apply), not itself a finding.
 * 2. Attribute present but content unavailable (unresolvable resource id, unsupported
 *    resources.arsc/XML variant, or a parse failure) → [AuditOutcome.NOT_TESTED] — never guessed at.
 * 3. Content parsed: [AuditOutcome.NEEDS_REVIEW] (LOW) if the base config permits cleartext traffic
 *    or `<debug-overrides>` trusts user-installed CAs — both are legitimate in some contexts (staging
 *    environments, this project's own MITM-detection tooling) but worth a human look, never an
 *    automatic FAIL. Otherwise [AuditOutcome.CHECK_PASSED] (INFO), noting whether a `<pin-set>` was
 *    found (a positive signal, not a defect for its absence — MS10-NET01's own "absence of pinning is
 *    never reported as an automatic FAIL" acceptance criterion).
 */
object NetworkSecurityConfigReviewRule : AuditRule {
 override val id = AuditRuleIds.NETWORK_SECURITY_CONFIG_REVIEW

 override fun evaluate(input: ApkAnalysisInput): AuditFinding {
  if (input.networkSecurityConfigPresent != true) {
   return AuditFinding(
    ruleId = id,
    outcome = AuditOutcome.CHECK_PASSED,
    severity = Severity.INFO,
    confidence = Confidence.HIGH,
    title = "Network security configuration",
    detail = "No android:networkSecurityConfig attribute declared — this app relies on the platform's own default TLS trust behavior.",
    remediation = "No action needed.",
   )
  }
  if (input.networkSecurityConfigUnavailableReason != null) {
   return AuditFinding(
    ruleId = id,
    outcome = AuditOutcome.NOT_TESTED,
    severity = Severity.INFO,
    confidence = Confidence.LOW,
    title = "Network security configuration",
    detail = "A network security configuration is declared, but its content could not be inspected: ${input.networkSecurityConfigUnavailableReason}. Absence of pinning must not be inferred from this — the configuration was not actually read.",
    remediation = "No automated remediation — this reflects a static-analysis coverage limit, not a property of the app. Manual review of the APK's network_security_config.xml is needed to assess this app's real network trust configuration.",
   )
  }

  val cleartextPermitted = input.networkSecurityConfigCleartextPermitted == true
  val debugTrustsUser = input.networkSecurityConfigDebugOverridesTrustsUserCerts
  val needsReview = cleartextPermitted || debugTrustsUser
  val detailParts = mutableListOf<String>()
  if (cleartextPermitted) detailParts.add("the base configuration permits cleartext (unencrypted HTTP) traffic")
  if (debugTrustsUser) detailParts.add("debug-overrides trust user-installed CA certificates (inert in a release build)")
  if (input.networkSecurityConfigHasPinSet) detailParts.add("at least one certificate pin-set is configured")
  if (detailParts.isEmpty()) detailParts.add("no cleartext override, debug-trust extension, or pin-set was found in the parsed configuration")

  return AuditFinding(
   ruleId = id,
   outcome = if (needsReview) AuditOutcome.NEEDS_REVIEW else AuditOutcome.CHECK_PASSED,
   severity = if (needsReview) Severity.LOW else Severity.INFO,
   confidence = Confidence.MEDIUM, // real content was read, but this parser's documented subset does not cover every real NSC construct — see NetworkSecurityConfigSummary.unsupportedNotes, not threaded into this flat input
   title = "Network security configuration",
   detail = "Network security configuration parsed: " + detailParts.joinToString("; ") + ".",
   remediation = if (needsReview) {
    "Review whether cleartext traffic and/or debug-CA trust are intentional for this app's real deployment; neither is an automatic defect."
   } else {
    "No action needed."
   },
  )
 }
}

/**
 * The full v1 static audit catalog, in a fixed, stable order — see docs/SECURITY_AUDIT_RULES.md for
 * the matching human-readable table. Order is cosmetic (report display order); no rule depends on
 * another rule's result.
 */
object StaticAuditRules {
 val all: List<AuditRule> = listOf(
  DebuggableBuildRule,
  SignatureIntegrityRule,
  ExportedSurfaceRatioRule,
  AccessibilityOverlayTapjackingRule,
  SmsInternetExfilSurfaceRule,
  BootPersistenceNetworkRule,
  OutdatedTargetSdkRule,
  NativeCodeUnverifiedRule,
  CleartextTrafficEnabledRule,
  BackupEnabledRule,
  NetworkSecurityConfigReviewRule,
 )
}
