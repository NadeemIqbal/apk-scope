package com.nadeem.apkscope.core.risk

import com.nadeem.apkscope.core.model.ApkAnalysisInput
import com.nadeem.apkscope.core.model.RiskEvidence
import com.nadeem.apkscope.core.model.RiskFinding
import com.nadeem.apkscope.core.model.RiskSeverity

/** APK-structure rules (item 9/12/13/14) — keyed on [ApkAnalysisInput] facts that come from the archive itself, not a manifest permission. */

object NativeLibrariesPresentRule : RiskRule {
 override val id = RuleIds.NATIVE_LIBRARIES_PRESENT
 override fun evaluate(input: ApkAnalysisInput): RiskFinding? {
  if (input.nativeLibraryAbis.isEmpty()) return null
  return RiskFinding(
   ruleId = id, severity = RiskSeverity.INFO,
   title = "Native libraries present",
   explanation = "This app contains native (non-Java/Kotlin) executable code for: ${input.nativeLibraryAbis.joinToString(", ")}. Native code cannot be inspected by this static analyzer the way manifest-declared capabilities can.",
   scoreContribution = 3,
   evidence = listOf(RiskEvidence("Contains native libraries for: ${input.nativeLibraryAbis.joinToString(", ")}")),
  )
 }
}

object DebuggableApkRule : RiskRule {
 override val id = RuleIds.DEBUGGABLE_APK
 override fun evaluate(input: ApkAnalysisInput): RiskFinding? {
  if (!input.debuggable) return null
  return RiskFinding(
   ruleId = id, severity = RiskSeverity.MEDIUM,
   title = "Debuggable APK",
   explanation = "This APK is built with android:debuggable=true, which exposes a debugger interface and typically indicates a non-release build. A debuggable build should not normally reach end users.",
   scoreContribution = 10,
   evidence = listOf(RiskEvidence("ApplicationInfo.FLAG_DEBUGGABLE is set")),
  )
 }
}

/**
 * Threshold below which a declared target SDK is old enough to matter (item 12) — chosen as
 * Android 10 (API 29), the last major release before scoped storage and the platform's broader
 * background-execution/privacy restrictions became the default for apps that target it. This is a
 * platform-capability signal, not evidence of malicious intent, and the finding says exactly what
 * was observed rather than implying one.
 */
const val OLD_TARGET_SDK_THRESHOLD = 29

object OldTargetSdkRule : RiskRule {
 override val id = RuleIds.OLD_TARGET_SDK
 override fun evaluate(input: ApkAnalysisInput): RiskFinding? {
  if (input.targetSdkVersion < 0 || input.targetSdkVersion >= OLD_TARGET_SDK_THRESHOLD) return null
  return RiskFinding(
   ruleId = id, severity = RiskSeverity.LOW,
   title = "Targets Android API ${input.targetSdkVersion}",
   explanation = "This app targets Android API ${input.targetSdkVersion}, below API $OLD_TARGET_SDK_THRESHOLD. Apps targeting older API levels are not subject to some of the platform's newer privacy and background-execution restrictions. This by itself does not indicate malicious intent.",
   scoreContribution = 5,
   evidence = listOf(RiskEvidence("targetSdkVersion = ${input.targetSdkVersion}")),
  )
 }
}

object SignatureVerificationFailedRule : RiskRule {
 override val id = RuleIds.SIGNATURE_VERIFICATION_FAILED
 override fun evaluate(input: ApkAnalysisInput): RiskFinding? {
  if (input.signatureVerified) return null
  return RiskFinding(
   ruleId = id, severity = RiskSeverity.HIGH,
   title = "APK signature verification failed",
   explanation = "This APK's signature could not be verified. A missing or invalid signature means the app's integrity and publisher identity cannot be confirmed by this device. This is a structural finding about the APK file itself, not a determination that the app is malicious.",
   scoreContribution = 20,
   evidence = listOf(RiskEvidence("Signature verification did not succeed")),
  )
 }
}

/**
 * Conservative, count-only exported-component rule (item 13) — the current `ApkAnalyzer` does not
 * yet capture per-component required-permission or intent-filter data, so this rule cannot (and
 * does not try to) judge whether any individual exported component is actually reachable or risky.
 * It only flags a high raw count. Threshold chosen so that a normal app's single exported launcher
 * activity (and perhaps one or two others) never trips it — flagging every exported component,
 * including a completely ordinary launcher activity, would manufacture certainty this analyzer
 * does not have (item 13's explicit instruction).
 */
const val MANY_EXPORTED_COMPONENTS_THRESHOLD = 5

object ManyExportedComponentsRule : RiskRule {
 override val id = RuleIds.MANY_EXPORTED_COMPONENTS
 override fun evaluate(input: ApkAnalysisInput): RiskFinding? {
  if (input.exportedComponentCount < MANY_EXPORTED_COMPONENTS_THRESHOLD) return null
  return RiskFinding(
   ruleId = id, severity = RiskSeverity.LOW,
   title = "${input.exportedComponentCount} exported components declared",
   explanation = "This app declares ${input.exportedComponentCount} exported components (out of ${input.totalComponentCount} total), meaning other apps on the device may be able to interact with them directly. This is a conservative, count-only signal: per-component protection level, required permissions, and intent-filter details are not yet available from this analyzer, so this finding does not assess whether any individual component is actually reachable or risky.",
   scoreContribution = 5,
   evidence = listOf(RiskEvidence("${input.exportedComponentCount} of ${input.totalComponentCount} components are exported")),
  )
 }
}
