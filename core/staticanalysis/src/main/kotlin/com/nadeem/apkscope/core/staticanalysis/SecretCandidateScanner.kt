package com.nadeem.apkscope.core.staticanalysis

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.File
import java.util.regex.Pattern

/**
 * Milestone 10 (Security Audit), Phase 10.3, MS10-SECRET01-03 — scans DEX string constants for
 * credential-shaped values. Reuses [DexUrlExtractor]'s exact traversal pattern (pool-level scan for
 * coverage, then instruction-level correlation for a calling class/method) rather than building new
 * DEX-walking infrastructure — see that class for the established convention this mirrors.
 *
 * **Format-based, not entropy-based** (see [SecretCategory]'s own doc): every pattern below matches a
 * *specific, publicly-documented credential format* — this is what lets a public identifier (a
 * Firebase project id, a package name, a version string) stay unflagged without a separate allow-list;
 * those values simply never have AWS/Google/Slack/JWT/PEM shape. This intentionally does **not** catch
 * a bespoke internal API key with no recognizable format — a real, named limitation (MS10-SECRET01's
 * "supported" scope), not a claim of exhaustive secret detection.
 *
 * **MS10-SECRET02 (masking)**: [SecretFinding.maskedValue] is the only representation this scanner
 * ever returns or retains — the raw matched value exists only inside [mask] itself, for the single
 * expression that computes the masked form, and is never stored in any field, log statement, or
 * intermediate collection.
 *
 * **MS10-SECRET03 (no network access)**: this file has no import of `java.net`, `okhttp3`, or any
 * other networking API — verified by `SecretCandidateScannerTest.secretScannerCompiledBytecodeContainsNoNetworkingClassReferences`,
 * which inspects this class's own compiled bytecode constant pool directly, not merely a code-review
 * claim.
 */
object SecretCandidateScanner {

 private const val MAX_STRING_LENGTH = 4096
 private const val MAX_RETAINED_FINDINGS = 500
 private const val MAX_SCAN_TIME_MS = 30000L

 private data class SecretRule(val category: SecretCategory, val pattern: Pattern)

 private val RULES = listOf(
  SecretRule(SecretCategory.PRIVATE_KEY_BLOCK, Pattern.compile("-----BEGIN (RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----")),
  SecretRule(SecretCategory.AWS_ACCESS_KEY, Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")),
  SecretRule(SecretCategory.GOOGLE_API_KEY, Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b")),
  SecretRule(SecretCategory.SLACK_TOKEN, Pattern.compile("\\bxox[baprs]-[0-9A-Za-z-]{10,48}\\b")),
  SecretRule(SecretCategory.JWT, Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b")),
 )

 /** Whether [value] itself matches any recognized secret format, in full — distinct from [scanApk]'s substring search within longer DEX strings. Exposed as a real, standalone utility (not just for testing) so a caller with one string in hand (e.g. a manually-pasted candidate) does not need to build a fake APK to classify it. */
 fun matchesAnyKnownFormat(value: String): Boolean = RULES.any { it.pattern.matcher(value).find() }

 /** Never stores or logs [value] — computes and returns only the masked form. First/last 2 characters survive for a human to recognize *which* candidate a repeated finding refers to across screens; everything else is replaced, and any value of 8 characters or fewer is fully masked (too short for a partial reveal to still hide anything meaningful). */
 fun mask(value: String): String = if (value.length <= 8) {
  "*".repeat(value.length)
 } else {
  value.take(2) + "*".repeat(value.length - 4) + value.takeLast(2)
 }

 data class ScanResult(val findings: List<SecretFinding>, val coverage: StaticAnalysisCoverage)

 fun scanApk(apkFile: File): ScanResult {
  val startTime = System.currentTimeMillis()
  val inspectedDexFiles = mutableListOf<String>()
  val skippedEntries = mutableListOf<String>()
  val parsingErrors = mutableListOf<String>()
  var limitsReached = false
  val findings = mutableListOf<SecretFinding>()
  val seenKeys = HashSet<String>()

  if (!apkFile.exists() || !apkFile.canRead()) {
   return ScanResult(
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
      if (!scanDexFile(dexFile, entryName, findings, seenKeys)) { limitsReached = true; break }
     }
    } catch (e: Exception) {
     parsingErrors.add("$entryName: ${e.javaClass.simpleName}: ${e.message}")
    }
   }
  } catch (e: Exception) {
   parsingErrors.add("DEX container load error: ${e.javaClass.simpleName}: ${e.message}")
  }

  return ScanResult(
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

 /** Returns false once [MAX_RETAINED_FINDINGS] is hit, so the caller can stop scanning further entries. */
 private fun scanDexFile(dexFile: DexBackedDexFile, entryName: String, findings: MutableList<SecretFinding>, seenKeys: MutableSet<String>): Boolean {
  val stringRefs = try { dexFile.stringReferences } catch (_: Exception) { emptyList() }
  for (stringRef in stringRefs) {
   val str = try { stringRef.string } catch (_: Exception) { continue }
   if (str.length > MAX_STRING_LENGTH) continue
   if (!record(str, entryName, null, null, findings, seenKeys)) return false
  }

  try {
   for (classDef in dexFile.classes) {
    val className = classDef.type.removePrefix("L").removeSuffix(";").replace('/', '.')
    for (method in classDef.methods) {
     val impl = method.implementation ?: continue
     for (instruction in impl.instructions) {
      if (instruction is ReferenceInstruction) {
       val ref = instruction.reference
       if (ref is StringReference) {
        val str = ref.string
        if (str.length > MAX_STRING_LENGTH) continue
        if (!record(str, entryName, className, method.name, findings, seenKeys)) return false
       }
      }
     }
    }
   }
  } catch (_: Exception) {
   // Partial instruction scan error should not discard pool-level matches already found.
  }
  return true
 }

 private fun record(
  raw: String,
  entryName: String,
  callingClass: String?,
  callingMethod: String?,
  findings: MutableList<SecretFinding>,
  seenKeys: MutableSet<String>,
 ): Boolean {
  for (rule in RULES) {
   val matcher = rule.pattern.matcher(raw)
   while (matcher.find()) {
    val matched = matcher.group()
    // The masked form is computed once, right here, and is the only thing that ever leaves this
    // function — `matched`/`raw` never get stored, logged, or returned.
    val masked = mask(matched)
    val key = "${rule.category}|$masked|$callingClass|$callingMethod|$entryName"
    if (seenKeys.add(key)) {
     if (findings.size >= MAX_RETAINED_FINDINGS) return false
     findings.add(SecretFinding(rule.category, masked, entryName, callingClass, callingMethod))
    }
   }
  }
  return true
 }
}
