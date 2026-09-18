package com.nadeem.apkscope.core.staticanalysis

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.regex.Pattern

/**
 * Extracts and deduplicates embedded URLs across all classes*.dex entries in an APK.
 *
 * Implements:
 * - Structured candidate regex parsing against DEX string pools.
 * - Instruction-level bytecode correlation to resolve calling class and method.
 * - Normalization, scheme filtering, and filtering of known Android/XML namespaces.
 * - Explicit distinction between DEX string vs code-referenced vs runtime-observed.
 * - Strict limits on entry counts, string lengths, unique results, and execution time.
 */
object DexUrlExtractor {

    private const val MAX_DEX_ENTRIES = 50
    private const val MAX_STRING_LENGTH = 4096
    private const val MAX_RETAINED_URLS = 2000
    private const val MAX_SCAN_TIME_MS = 30000L

    // Strict URL regex pattern requiring valid http/https/ws/wss scheme and host
    private val URL_REGEX = Pattern.compile(
        "\\b(https?|wss?)://[a-zA-Z0-9][-a-zA-Z0-9.]*\\.[a-zA-Z]{2,}(?::[0-9]{1,5})?(?:/[^\\s\"'<>{}|\\]\\[^`\\\\]*)?",
        Pattern.CASE_INSENSITIVE
    )

    // Namespace schemas to exclude from security findings
    private val EXCLUDED_PREFIXES = listOf(
        "http://schemas.android.com/",
        "https://schemas.android.com/",
        "http://www.w3.org/",
        "https://www.w3.org/",
        "http://xmlpull.org/",
        "https://xmlpull.org/",
        "http://schemas.google.com/",
        "https://schemas.openxmlformats.org/",
        "http://apache.org/xml/",
        "https://apache.org/xml/"
    )

    // Library documentation, standards namespaces, and issue-tracker links are commonly compiled
    // into error messages, diagnostics, and help text. They are not useful as application
    // endpoints in the Embedded URLs view. Exact runtime evidence still wins and keeps a URL
    // visible if the app actually contacted one of these hosts during sandbox execution.
    private val DOCUMENTATION_HOSTS = setOf(
        "aomedia.org",
        "android.com",
        "crbug.com",
        "dashif.org",
        "default.url",
        "developer.android.com",
        "developer.apple.com",
        "developers.android.com",
        "developers.google.com",
        "docs.flutter.dev",
        "docs.gradle.org",
        "docs.oracle.com",
        "docs.swmansion.com",
        "issuetracker.google.com",
        "learn.microsoft.com",
        "ns.adobe.com",
        "schemas.microsoft.com",
        "source.android.com",
    )

    /** Returns true for known documentation/standards URLs that are static-analysis noise. */
    fun isLikelyDocumentationUrl(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        val documentationPathMarkers = listOf(
            "/doc/",
            "/docs/",
            "/documentation/",
            "/guide/",
            "/guidelines/",
            "/issue/",
            "/issues/",
            "/support/",
            "/troubleshooting/",
            "/wiki/",
        )
        return host in DOCUMENTATION_HOSTS ||
            host == "www.android.com" ||
            host == "www.crbug.com" ||
            host.startsWith("docs.") ||
            host.contains(".docs.") ||
            host.endsWith(".github.io") ||
            host.endsWith(".gitlab.io") ||
            (host == "g.co" && path.startsWith("/dev/")) ||
            (host == "github.com" && listOf("/issues", "/pull", "/wiki", "/blob", "/tree").any(path::contains)) ||
            documentationPathMarkers.any(path::contains)
    }

    data class ExtractionResult(
        val urls: List<DexUrlCandidate>,
        val coverage: StaticAnalysisCoverage
    )

    fun extractUrls(
        apkFile: File,
        observedRuntimeUrls: Map<String, RuntimeEvidenceReference> = emptyMap(),
        observedRuntimeHosts: Map<String, HostCorrelationInfo> = emptyMap()
    ): ExtractionResult {
        val startTime = System.currentTimeMillis()
        val inspectedDexFiles = mutableListOf<String>()
        val skippedEntries = mutableListOf<String>()
        val parsingErrors = mutableListOf<String>()
        var limitsReached = false

        val candidateMap = LinkedHashMap<String, MutableCandidate>()

        if (!apkFile.exists() || !apkFile.canRead()) {
            return ExtractionResult(
                urls = emptyList(),
                coverage = StaticAnalysisCoverage(
                    parsingErrors = listOf("APK file missing or unreadable: ${apkFile.absolutePath}"),
                    scanDurationMs = System.currentTimeMillis() - startTime
                )
            )
        }

        try {
            val opcodes = Opcodes.getDefault()
            val container = DexFileFactory.loadDexContainer(apkFile, opcodes)
            val entryNames = container.dexEntryNames.sorted()

            for ((index, entryName) in entryNames.withIndex()) {
                if (index >= MAX_DEX_ENTRIES) {
                    skippedEntries.add("$entryName (exceeded max entry limit of $MAX_DEX_ENTRIES)")
                    limitsReached = true
                    continue
                }

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
                        scanDexFile(dexFile, entryName, candidateMap)
                    }

                    if (candidateMap.size >= MAX_RETAINED_URLS) {
                        limitsReached = true
                        break
                    }
                } catch (e: Exception) {
                    parsingErrors.add("$entryName: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            parsingErrors.add("DEX container loading failed: ${e.javaClass.simpleName}: ${e.message}")
        }

        val finalCandidates = candidateMap.values
            .map { it.toCandidate(observedRuntimeUrls, observedRuntimeHosts) }
            .filterNot { candidate ->
                candidate.runtimeEvidence == null && isLikelyDocumentationUrl(candidate.normalizedUrl)
            }

        val coverage = StaticAnalysisCoverage(
            dexFilesInspected = inspectedDexFiles,
            entriesSkipped = skippedEntries,
            parsingErrors = parsingErrors,
            limitsReached = limitsReached,
            scanDurationMs = System.currentTimeMillis() - startTime
        )

        return ExtractionResult(urls = finalCandidates, coverage = coverage)
    }

    fun extractUrls(
        apkFile: File,
        observedRuntimeHosts: Set<String>
    ): ExtractionResult {
        val hostMap = observedRuntimeHosts.associateWith { host ->
            HostCorrelationInfo(host = host, observedTransactionCount = 1)
        }
        return extractUrls(apkFile, observedRuntimeUrls = emptyMap(), observedRuntimeHosts = hostMap)
    }

    private fun scanDexFile(
        dexFile: DexBackedDexFile,
        entryName: String,
        candidateMap: MutableMap<String, MutableCandidate>
    ) {
        // Step 1: Scan all strings in string pool
        val stringRefs = try {
            dexFile.stringReferences
        } catch (_: Exception) {
            emptyList()
        }

        for (stringRef in stringRefs) {
            val str = try {
                stringRef.string
            } catch (_: Exception) {
                continue
            }
            if (str.length > MAX_STRING_LENGTH) continue

            val matcher = URL_REGEX.matcher(str)
            while (matcher.find()) {
                val candidateStr = matcher.group()
                processCandidate(candidateStr, entryName, candidateMap, location = null)
                if (candidateMap.size >= MAX_RETAINED_URLS) return
            }
        }

        // Step 2: Correlate with method instructions to locate code references
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
                                val matcher = URL_REGEX.matcher(str)
                                while (matcher.find()) {
                                    val candidateStr = matcher.group()
                                    val loc = CodeReferenceLocation(
                                        dexEntry = entryName,
                                        className = className,
                                        methodName = method.name,
                                        isInstruction = true
                                    )
                                    processCandidate(candidateStr, entryName, candidateMap, location = loc)
                                    if (candidateMap.size >= MAX_RETAINED_URLS) return
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Partial instruction scan error should not discard pool matches
        }
    }

    private fun processCandidate(
        rawUrl: String,
        entryName: String,
        candidateMap: MutableMap<String, MutableCandidate>,
        location: CodeReferenceLocation?
    ) {
        if (EXCLUDED_PREFIXES.any { rawUrl.startsWith(it, ignoreCase = true) }) {
            return
        }

        val normalized = normalizeUrl(rawUrl) ?: return
        val host = extractHost(normalized) ?: return
        val scheme = normalized.substringBefore("://").lowercase(Locale.ROOT)

        val key = normalized.lowercase(Locale.ROOT)
        val existing = candidateMap[key]

        if (existing != null) {
            if (location != null && !existing.references.contains(location)) {
                existing.references.add(location)
            }
        } else {
            val refs = if (location != null) mutableListOf(location) else mutableListOf()
            candidateMap[key] = MutableCandidate(
                originalString = rawUrl,
                normalizedUrl = normalized,
                host = host,
                scheme = scheme,
                dexEntry = entryName,
                references = refs
            )
        }
    }

    private fun normalizeUrl(urlStr: String): String? {
        return try {
            val uri = URI(urlStr)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme !in listOf("http", "https", "ws", "wss")) return null
            val host = uri.host ?: return null
            if (host.isBlank() || !host.contains('.')) return null

            // Build normalized representation
            val portPart = if (uri.port != -1 && !isDefaultPort(scheme, uri.port)) ":${uri.port}" else ""
            val pathPart = if (uri.path.isNullOrEmpty()) "/" else uri.path
            val queryPart = if (uri.query != null) "?${uri.query}" else ""
            "$scheme://${host.lowercase(Locale.ROOT)}$portPart$pathPart$queryPart"
        } catch (_: Exception) {
            null
        }
    }

    private fun extractHost(urlStr: String): String? {
        return try {
            URI(urlStr).host
        } catch (_: Exception) {
            null
        }
    }

    private fun isDefaultPort(scheme: String, port: Int): Boolean {
        return (scheme == "http" && port == 80) ||
               (scheme == "https" && port == 443) ||
               (scheme == "ws" && port == 80) ||
               (scheme == "wss" && port == 443)
    }

    private class MutableCandidate(
        val originalString: String,
        val normalizedUrl: String,
        val host: String,
        val scheme: String,
        val dexEntry: String,
        val references: MutableList<CodeReferenceLocation>
    ) {
        fun toCandidate(
            observedRuntimeUrls: Map<String, RuntimeEvidenceReference>,
            observedRuntimeHosts: Map<String, HostCorrelationInfo>
        ): DexUrlCandidate {
            val exactEvidence = observedRuntimeUrls[normalizedUrl]
                ?: observedRuntimeUrls[normalizedUrl.trimEnd('/')]
                ?: observedRuntimeUrls[originalString]

            val hostInfo = observedRuntimeHosts[host.lowercase(Locale.ROOT)]

            val provenance = when {
                exactEvidence != null -> UrlProvenance.RUNTIME_OBSERVED
                references.isNotEmpty() -> UrlProvenance.REFERENCED_BY_CODE
                else -> UrlProvenance.PRESENT_IN_DEX
            }
            return DexUrlCandidate(
                originalString = originalString,
                normalizedUrl = normalizedUrl,
                host = host,
                scheme = scheme,
                dexEntry = dexEntry,
                provenance = provenance,
                references = references.toList(),
                extractionRule = "STRUCTURED_URI_NORMALIZER",
                runtimeEvidence = exactEvidence,
                hostCorrelation = hostInfo
            )
        }
    }
}
