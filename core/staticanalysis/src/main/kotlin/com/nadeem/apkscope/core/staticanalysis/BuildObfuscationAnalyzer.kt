package com.nadeem.apkscope.core.staticanalysis

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import java.io.File
import java.io.Serializable

/**
 * Milestone 10 (Security Audit), Phase 10.3, MS10-BUILD01 — a naming-pattern heuristic over compiled
 * class names, reporting obfuscation *indicators*, never proof of correct R8/ProGuard configuration.
 *
 * **What this can and cannot establish, stated plainly per this requirement's own acceptance
 * criterion** ("the finding text explicitly disclaims proof of correct configuration"): a short,
 * generated-looking simple class name (the classic single/double-letter ProGuard/R8 convention, e.g.
 * `a`, `b`, `Aa`) is consistent with obfuscation having run, but this heuristic cannot and does not
 * verify that R8/ProGuard actually ran, that it was configured correctly (e.g. that sensitive string
 * constants, not just identifiers, were also protected), or that any specific class was deliberately
 * targeted for renaming rather than the developer simply choosing a short name. Symmetrically, a
 * class with no short names proves nothing about whether minification is *disabled* — a build could
 * enable minification with `-keep` rules broad enough to leave every name unchanged. **Named,
 * permanent limitation, not fixed by more heuristics**: this is a naming-pattern signal only.
 *
 * **Optional stronger evidence**: [analyzeWithOptionalMappingFile] additionally accepts a real
 * ProGuard/R8 `mapping.txt`-format file (`original.class.Name -> obfuscated.name:` lines) — parsed as
 * plain text only (regex line matching), never executed or evaluated as code, matching this
 * requirement's explicit "treated as untrusted data, never executed" — and, when supplied, upgrades
 * the assessment from a bare naming-pattern guess to a confirmed count of classes the mapping file
 * itself states were renamed and are actually present in this APK.
 *
 * **Product boundary, stated explicitly (accuracy hardening pass, 2026-09-14)**: [analyze] — the
 * normal analysis path — takes only the compiled APK file. It runs entirely on-device, needs no root,
 * no Gradle project, no source repository, and no external network access; a mapping file is never
 * required and this object has no way to obtain, request, or derive one from the APK itself. A real
 * `mapping.txt` is a build-time artifact that a compiled APK does not contain and cannot be
 * reconstructed from — [analyzeWithOptionalMappingFile]'s `mappingFileText` parameter exists solely
 * for a caller who separately already possesses one (e.g. the app developer's own retained build
 * output) to supply as external, optional, strictly-supplementary evidence. Nothing in this class
 * should be read as claiming mapping files are normally recoverable from an APK; they are not.
 */
object BuildObfuscationAnalyzer {

 /** The classic ProGuard/R8 short-name convention: one or two letters, case-insensitive (`a`, `b`, `Aa`, `Zz`). Deliberately narrow — a 3+ character name is common in ordinary, non-obfuscated code (`Foo`, `Bar`) and including it would inflate false positives on real, human-authored code. */
 private val SHORT_GENERATED_NAME_PATTERN = Regex("^[a-zA-Z]{1,2}$")

 /** Below this fraction of short-named classes, this heuristic calls the naming pattern "consistent with unobfuscated code" — chosen so a handful of legitimately short real class names (rare, but not impossible) does not itself trigger an obfuscation call. */
 private const val OBFUSCATION_LIKELY_THRESHOLD = 0.3

 /**
  * Common third-party/runtime library package prefixes excluded from the ratio — real, well-known
  * libraries (the Kotlin standard library alone commonly contributes 1000+ classes to even a minimal
  * app) whose own real, descriptive names would otherwise swamp a small app's own class count and
  * make the ratio meaningless in either direction. **Deliberately a denylist of known
  * non-app-owned code, not an allowlist of the app's own declared package**: a real, fully-obfuscated
  * release build commonly flattens/randomizes package names too, so restricting to "starts with the
  * app's own package name" would make this heuristic blind to exactly the full-obfuscation case it
  * most needs to catch. The real, disclosed limitation of the denylist approach instead: a bespoke or
  * less-common third-party SDK not on this list still dilutes the ratio — this list is small and
  * explicit on purpose, not a claim of covering every possible bundled library.
  */
 private val KNOWN_LIBRARY_PACKAGE_PREFIXES = listOf(
  "kotlin.", "kotlinx.", "androidx.", "android.", "com.android.", "com.google.android.",
  "okhttp3.", "okio.", "org.jetbrains.", "org.intellij.", "junit.", "org.junit.",
  // Platform-level annotation/system classes the toolchain references even in a minimal app with no
  // application code touching them directly (e.g. @dalvik.annotation.optimization.FastNative on a
  // JDK method, or dalvik.system.ZipPathValidator$Callback referenced by java.util.zip internals) —
  // found by direct inspection of a real minimal APK's compiled class list before adding this entry,
  // not guessed preemptively.
  "dalvik.",
 )

 data class ObfuscationAssessment(
  val totalClassesInspected: Int,
  val shortGeneratedLookingNameCount: Int,
  val shortNameRatio: Double,
  /** `true` when [shortNameRatio] meets [OBFUSCATION_LIKELY_THRESHOLD] — a heuristic call, not a confirmed fact; see this object's own doc. */
  val namingPatternConsistentWithObfuscation: Boolean,
  /** Populated only by [analyzeWithOptionalMappingFile] when a mapping file was supplied and at least one of its renamed classes was found present in this APK — a confirmed count, not a heuristic one. `null` when no mapping file was supplied. */
  val mappingConfirmedRenamedClassCount: Int? = null,
  val coverage: StaticAnalysisCoverage,
 ) : Serializable {
  /** The one-sentence disclaimer this requirement's acceptance criterion requires every finding to carry, regardless of outcome. */
  val disclaimer: String
   get() = "Naming-pattern heuristic only — does not prove R8/ProGuard ran, was configured correctly, or that any specific identifier was deliberately protected."
 }

 fun analyze(apkFile: File): ObfuscationAssessment = analyzeWithOptionalMappingFile(apkFile, mappingFileText = null)

 /**
  * @param mappingFileText the raw text content of a real ProGuard/R8 `mapping.txt`, if the caller has
  * one available (e.g. a user-supplied build artifact) — parsed as plain text only, never executed.
  * Malformed or unrecognized lines are silently skipped, not treated as a parse failure — a mapping
  * file's exact format varies slightly across R8 versions, and this is optional supplementary
  * evidence, not the primary result.
  */
 fun analyzeWithOptionalMappingFile(apkFile: File, mappingFileText: String?): ObfuscationAssessment {
  val startTime = System.currentTimeMillis()
  val inspectedDexFiles = mutableListOf<String>()
  val parsingErrors = mutableListOf<String>()
  val allSimpleNames = mutableListOf<String>()
  val allDottedClassNames = mutableListOf<String>()

  if (!apkFile.exists() || !apkFile.canRead()) {
   return ObfuscationAssessment(
    0, 0, 0.0, false, null,
    StaticAnalysisCoverage(parsingErrors = listOf("APK file missing or unreadable: ${apkFile.absolutePath}"), scanDurationMs = System.currentTimeMillis() - startTime),
   )
  }

  try {
   val container = DexFileFactory.loadDexContainer(apkFile, Opcodes.getDefault())
   for (entryName in container.dexEntryNames.sorted()) {
    try {
     val entry = container.getEntry(entryName) ?: continue
     val dexFile = entry.dexFile
     inspectedDexFiles.add(entryName)
     if (dexFile is DexBackedDexFile) {
      for (classDef in dexFile.classes) {
       val dotted = classDef.type.removePrefix("L").removeSuffix(";").replace('/', '.')
       // Skip R (and R$inner) classes and BuildConfig — these are always compiler-generated with a
       // fixed, short-by-convention name regardless of obfuscation, and including them would inflate
       // the short-name ratio with a signal that has nothing to do with R8/ProGuard.
       val simpleName = dotted.substringAfterLast('.')
       if (simpleName == "R" || simpleName.startsWith("R$") || simpleName == "BuildConfig") continue
       if (KNOWN_LIBRARY_PACKAGE_PREFIXES.any { dotted.startsWith(it) }) continue
       allDottedClassNames.add(dotted)
       allSimpleNames.add(simpleName)
      }
     }
    } catch (e: Exception) {
     parsingErrors.add("$entryName: ${e.javaClass.simpleName}: ${e.message}")
    }
   }
  } catch (e: Exception) {
   parsingErrors.add("DEX container load error: ${e.javaClass.simpleName}: ${e.message}")
  }

  val shortNames = allSimpleNames.filter { SHORT_GENERATED_NAME_PATTERN.matches(it) }
  val ratio = if (allSimpleNames.isEmpty()) 0.0 else shortNames.size.toDouble() / allSimpleNames.size

  val mappingConfirmedCount = mappingFileText?.let { text -> countMappingConfirmedRenames(text, allDottedClassNames) }

  return ObfuscationAssessment(
   totalClassesInspected = allSimpleNames.size,
   shortGeneratedLookingNameCount = shortNames.size,
   shortNameRatio = ratio,
   namingPatternConsistentWithObfuscation = ratio >= OBFUSCATION_LIKELY_THRESHOLD,
   mappingConfirmedRenamedClassCount = mappingConfirmedCount,
   coverage = StaticAnalysisCoverage(dexFilesInspected = inspectedDexFiles, parsingErrors = parsingErrors, scanDurationMs = System.currentTimeMillis() - startTime),
  )
 }

 /** A real `mapping.txt` line for a class looks like `original.pkg.Name -> a.b.c:` (no leading whitespace, ends with a colon — member-mapping lines that follow each class line start with whitespace and are not class lines, so this pattern alone will not misinterpret them as classes). */
 private val MAPPING_CLASS_LINE = Regex("""^(\S+) -> (\S+):$""")

 private fun countMappingConfirmedRenames(mappingText: String, actualClassNamesInApk: List<String>): Int {
  val actualSet = actualClassNamesInApk.toHashSet()
  var confirmed = 0
  for (line in mappingText.lineSequence()) {
   if (line.isNotEmpty() && line[0].isWhitespace()) continue // a member (field/method) mapping line, not a class line
   val match = MAPPING_CLASS_LINE.matchEntire(line.trim()) ?: continue
   val obfuscatedName = match.groupValues[2]
   if (obfuscatedName in actualSet) confirmed++
  }
  return confirmed
 }
}
