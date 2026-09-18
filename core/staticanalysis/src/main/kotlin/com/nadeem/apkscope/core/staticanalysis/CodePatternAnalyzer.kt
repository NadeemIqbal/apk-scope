package com.nadeem.apkscope.core.staticanalysis

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.File
import java.io.Serializable

/**
 * Milestone 10 (Security Audit), Phase 10.3, MS10-CODE01 — two structural code patterns that need
 * more than a bare method reference (which `DexApiScanner`'s `CRYPTOGRAPHY`/`WEBVIEW` categories
 * already cover): a weak-algorithm-string co-occurrence heuristic, and a WebView SSL-error-bypass
 * check that reads real class-hierarchy/method-name facts, not just an isolated call.
 *
 * **Evidence tiers, stated per this requirement's own acceptance criterion** ("finding text states
 * which of the three tiers — reference/reachable/executed — it actually establishes"): every finding
 * this file (and `DexApiScanner`'s CRYPTOGRAPHY/WEBVIEW rules) can ever produce is [EvidenceTier.REFERENCE]
 * only. Nothing here performs control-flow reachability analysis (proving the referencing method is
 * ever actually called from an entry point) or runtime observation (proving the code path executed) —
 * both would require capabilities this static bytecode scanner does not have. A finding never claims
 * [EvidenceTier.REACHABLE] or [EvidenceTier.EXECUTED].
 *
 * **Evidence confidence, stated per this requirement's own "reference vs reachable vs executed"
 * framing and this milestone's parallel "confirmed vs heuristic vs not-tested" discipline**:
 * - [EvidenceConfidence.CONFIRMED]: every structural fact the finding states is directly, unambiguously
 *   readable from the bytecode with no inference — e.g. "this class's `superclass` field literally is
 *   `Landroid/webkit/WebViewClient;`, and one of its methods is literally named `onReceivedSslError`,
 *   and that method's instructions literally include an invocation of `SslErrorHandler.proceed`".
 * - [EvidenceConfidence.HEURISTIC]: the finding requires an inference this scanner cannot verify —
 *   e.g. "a weak-algorithm string constant appears in the same method as a `Cipher.getInstance`
 *   call" does not prove that string was the actual argument passed (that needs real dataflow
 *   tracing, not implemented); it is a plausible co-occurrence, not a proven binding.
 *
 * **[CodePatternFinding.confirmedFacts] vs [CodePatternFinding.interpretation], structurally
 * separated, not just a prose convention** (correction, 2026-09-14 accuracy hardening pass): a
 * [EvidenceConfidence.CONFIRMED] structural fact (e.g. "this method calls `SslErrorHandler.proceed`")
 * is not the same claim as its security *consequence* (e.g. "certificate validation is bypassed") —
 * the latter requires control-flow analysis this scanner does not perform (is `proceed()` reached
 * unconditionally? is it guarded by a debug-only check? does any validation happen first?), none of
 * which is established here. [confirmedFacts] states only what was directly read from the bytecode,
 * with no risk/behavioral language. [interpretation] is always explicitly labeled as interpretation —
 * a security *read* of the confirmed facts (e.g. "a strong indicator of an SSL bypass pattern"), never
 * phrased as itself proven. A caller must not merge the two into a single "this is a vulnerability"
 * claim; they are kept as separate fields specifically so neither can be silently dropped in a
 * downstream rendering.
 */
object CodePatternAnalyzer {

 enum class EvidenceTier { REFERENCE, REACHABLE, EXECUTED }
 enum class EvidenceConfidence { CONFIRMED, HEURISTIC }

 enum class CodePatternCategory(val title: String) : Serializable {
  WEAK_CRYPTOGRAPHIC_ALGORITHM_HEURISTIC("Possible Weak Cryptographic Algorithm"),
  WEBVIEW_SSL_ERROR_BYPASS("WebView SSL Error Bypass"),
 }

 data class CodePatternFinding(
  val category: CodePatternCategory,
  val tier: EvidenceTier,
  val confidence: EvidenceConfidence,
  val dexEntry: String,
  val className: String,
  val methodName: String,
  /** Exactly what was directly, unambiguously observed in the bytecode — no risk or runtime-behavior language, no security-impact claim. See this object's own doc for why this is a separate field from [interpretation], not merged prose. */
  val confirmedFacts: String,
  /** A security *read* of [confirmedFacts], always explicitly labeled as interpretation, never phrased as itself proven — the runtime/security consequence this scanner infers but cannot establish (no control-flow analysis, no runtime observation). */
  val interpretation: String,
 ) : Serializable

 data class AnalysisResult(val findings: List<CodePatternFinding>, val coverage: StaticAnalysisCoverage)

 private const val MAX_FINDINGS = 500
 private const val MAX_SCAN_TIME_MS = 30000L

 /** Weak/deprecated algorithm identifiers a `Cipher`/`MessageDigest` call might have been passed — real, well-known deprecated primitives (DES/RC4 broken ciphers; MD5/SHA-1 broken/weakened digests), not an exhaustive cryptographic weakness catalog. */
 private val WEAK_ALGORITHM_MARKERS = setOf("DES", "DESede", "RC4", "MD5", "SHA1", "SHA-1")
 private val CRYPTO_API_CLASSES = setOf("Ljavax/crypto/Cipher;", "Ljava/security/MessageDigest;")
 private const val WEBVIEW_CLIENT_DESCRIPTOR = "Landroid/webkit/WebViewClient;"
 private const val SSL_ERROR_HANDLER_DESCRIPTOR = "Landroid/webkit/SslErrorHandler;"

 fun analyzeApk(apkFile: File): AnalysisResult {
  val startTime = System.currentTimeMillis()
  val inspectedDexFiles = mutableListOf<String>()
  val skippedEntries = mutableListOf<String>()
  val parsingErrors = mutableListOf<String>()
  var limitsReached = false
  val findings = mutableListOf<CodePatternFinding>()

  if (!apkFile.exists() || !apkFile.canRead()) {
   return AnalysisResult(
    emptyList(),
    StaticAnalysisCoverage(parsingErrors = listOf("APK file missing or unreadable: ${apkFile.absolutePath}"), scanDurationMs = System.currentTimeMillis() - startTime),
   )
  }

  try {
   val opcodes = Opcodes.getDefault()
   val container = DexFileFactory.loadDexContainer(apkFile, opcodes)
   for (entryName in container.dexEntryNames.sorted()) {
    if (System.currentTimeMillis() - startTime > MAX_SCAN_TIME_MS) {
     skippedEntries.add("$entryName (scan timeout exceeded)")
     limitsReached = true
     break
    }
    try {
     val entry = container.getEntry(entryName) ?: continue
     val dexFile = entry.dexFile
     inspectedDexFiles.add(entryName)
     if (dexFile is DexBackedDexFile) {
      analyzeDexFile(dexFile, entryName, findings)
      if (findings.size >= MAX_FINDINGS) { limitsReached = true; break }
     }
    } catch (e: Exception) {
     parsingErrors.add("$entryName: ${e.javaClass.simpleName}: ${e.message}")
    }
   }
  } catch (e: Exception) {
   parsingErrors.add("DEX container load error: ${e.javaClass.simpleName}: ${e.message}")
  }

  return AnalysisResult(
   findings,
   StaticAnalysisCoverage(
    dexFilesInspected = inspectedDexFiles,
    entriesSkipped = skippedEntries,
    parsingErrors = parsingErrors,
    limitsReached = limitsReached,
    scanDurationMs = System.currentTimeMillis() - startTime,
   ),
  )
 }

 private fun analyzeDexFile(dexFile: DexBackedDexFile, entryName: String, findings: MutableList<CodePatternFinding>) {
  for (classDef in dexFile.classes) {
   val className = classDef.type.removePrefix("L").removeSuffix(";").replace('/', '.')
   val extendsWebViewClient = classDef.superclass == WEBVIEW_CLIENT_DESCRIPTOR

   for (method in classDef.methods) {
    val impl = method.implementation ?: continue
    val stringConstantsInMethod = HashSet<String>()
    var callsCryptoGetInstance = false
    var callsSslErrorHandlerProceed = false

    for (instruction in impl.instructions) {
     if (instruction !is ReferenceInstruction) continue
     when (val ref = instruction.reference) {
      is StringReference -> stringConstantsInMethod.add(ref.string)
      is MethodReference -> {
       if (ref.definingClass in CRYPTO_API_CLASSES && ref.name == "getInstance") callsCryptoGetInstance = true
       if (ref.definingClass == SSL_ERROR_HANDLER_DESCRIPTOR && ref.name == "proceed") callsSslErrorHandlerProceed = true
      }
      else -> Unit
     }
     if (findings.size >= MAX_FINDINGS) return
    }

    // --- HEURISTIC: weak-algorithm string co-occurring with a crypto getInstance call ---
    if (callsCryptoGetInstance) {
     val weakMarkersPresent = WEAK_ALGORITHM_MARKERS.filter { marker -> stringConstantsInMethod.any { it == marker || it.startsWith("$marker/") } }
     if (weakMarkersPresent.isNotEmpty()) {
      findings.add(
       CodePatternFinding(
        category = CodePatternCategory.WEAK_CRYPTOGRAPHIC_ALGORITHM_HEURISTIC,
        tier = EvidenceTier.REFERENCE,
        confidence = EvidenceConfidence.HEURISTIC,
        dexEntry = entryName,
        className = className,
        methodName = method.name,
        confirmedFacts = "Confirmed: method ${method.name} contains an invocation of Cipher.getInstance or MessageDigest.getInstance, and the same method also contains the string constant(s) ${weakMarkersPresent.joinToString()}.",
        interpretation = "Interpretation (HEURISTIC, not proven): ${weakMarkersPresent.joinToString()} is a deprecated/weak cryptographic algorithm identifier, so this co-occurrence is a plausible indicator that a weak algorithm was requested — but this scanner does not trace dataflow, so it cannot establish that this string constant was actually passed as the getInstance argument. The weak-looking string could be unrelated to this call entirely; this is not a confirmed argument binding.",
       ),
      )
     }
    }

    // --- CONFIRMED: WebViewClient.onReceivedSslError calling SslErrorHandler.proceed ---
    if (extendsWebViewClient && method.name == "onReceivedSslError" && callsSslErrorHandlerProceed) {
     findings.add(
      CodePatternFinding(
       category = CodePatternCategory.WEBVIEW_SSL_ERROR_BYPASS,
       tier = EvidenceTier.REFERENCE,
       confidence = EvidenceConfidence.CONFIRMED,
       dexEntry = entryName,
       className = className,
       methodName = method.name,
       // Exactly the two facts this scanner actually proves — nothing about what proceed() does at
       // runtime, nothing about whether it is reached unconditionally. See CodePatternAnalyzerTest's
       // own accuracy-hardening tests for the exact wording this was corrected to require.
       confirmedFacts = "Confirmed: class $className extends android.webkit.WebViewClient and overrides onReceivedSslError. Confirmed call to SslErrorHandler.proceed() from onReceivedSslError.",
       interpretation = "Interpretation (not proven by this scanner): calling SslErrorHandler.proceed() instructs the WebView to continue loading despite the TLS certificate error, so this is a strong indicator of an SSL/TLS validation bypass — a well-documented Android anti-pattern (CWE-295). This scanner performs no control-flow analysis: it does not establish that proceed() is reached unconditionally, that it is not guarded by a condition (e.g. a debug-only build check), that any meaningful validation happens before it, that this WebViewClient is ever attached to a real WebView, or that onReceivedSslError is ever actually invoked by the framework. Do not read this finding as confirming certificate validation is bypassed at runtime — only that the code contains this call.",
      ),
     )
    }
    if (findings.size >= MAX_FINDINGS) return
   }
  }
 }
}
