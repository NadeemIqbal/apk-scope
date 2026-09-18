package com.nadeem.apkscope.core.staticanalysis

import java.io.Serializable

/**
 * Origin and relationship of an extracted URL candidate.
 */
enum class UrlProvenance : Serializable {
    /** Present in DEX string pool, but no direct code instruction reference resolved. */
    PRESENT_IN_DEX,
    /** Actively referenced by an opcode/instruction in method bytecode (e.g. const-string). */
    REFERENCED_BY_CODE,
    /** Cross-referenced destination observed during live Work Profile dynamic execution. */
    RUNTIME_OBSERVED
}

/**
 * Specific bytecode location where a URL, API, or string is referenced.
 */
data class CodeReferenceLocation(
    val dexEntry: String,
    val className: String,
    val methodName: String? = null,
    val isInstruction: Boolean = true
) : Serializable

/**
 * Precise runtime capture reference proving an exact URL was dynamically observed.
 *
 * **Evidence identity and retention policy** (Milestone 9, 2026-09-13): this is a *single*
 * reference, not a history — a [DexUrlCandidate] retains at most one [RuntimeEvidenceReference]
 * at a time. Identity is the pair (`transactionId`, [timestamp]): [com.nadeem.apkscope.core.staticanalysis]'s
 * consumer (`UrlEvidenceCorrelator`) treats two observations with the same `transactionId` as the
 * *same* evidence (re-importing it is a no-op, not a duplicate or a second reference), and between
 * two *different* transactionIds for the same URL, retains whichever has the later [timestamp] —
 * i.e. **the most recently observed exact match wins**, never an older one, and a genuinely later
 * observation (a different session, a later request) is never silently dropped in favor of an
 * earlier one. This intentionally does **not** track how many times the URL was requested, by
 * whom, or across how many sessions — do not read a present [RuntimeEvidenceReference] as a
 * complete observation history; it is proof the URL was requested *at least once*, with the most
 * recent such proof retained.
 */
data class RuntimeEvidenceReference(
    val sessionId: String,
    val transactionId: String,
    val url: String,
    val timestamp: Long,
    val method: String? = null,
    val statusCode: Int? = null
) : Serializable

/**
 * Separate host correlation metadata when a host was observed in runtime traffic
 * but this specific URL was not observed.
 *
 * **Evidence identity and retention policy** (Milestone 9, 2026-09-13): [observedTransactionCount]/
 * [sampleTransactionId] describe the *most recent import batch that had at least one host match*
 * for this candidate — **not** a cumulative, all-time count across every session ever imported.
 * An import batch with zero host matches for this candidate leaves a previously-recorded
 * [HostCorrelationInfo] untouched (absence of a match in one later, unrelated session is not
 * evidence the earlier match never happened), but a batch that *does* have matches replaces the
 * whole value with that batch's own count/sample, rather than adding to the old one — this avoids
 * double-counting a repeated import of the same evidence, at the cost of not reflecting a true
 * cumulative total across multiple distinct sessions. Do not read this as a complete observation
 * history across the analysis's lifetime; it reflects the most recent contributing import only.
 */
data class HostCorrelationInfo(
    val host: String,
    val observedTransactionCount: Int,
    val sampleTransactionId: String? = null,
    val sampleSessionId: String? = null
) : Serializable

/**
 * A structured URL candidate extracted from DEX files.
 */
data class DexUrlCandidate(
    val originalString: String,
    val normalizedUrl: String,
    val host: String,
    val scheme: String,
    val dexEntry: String,
    val provenance: UrlProvenance,
    val references: List<CodeReferenceLocation> = emptyList(),
    val extractionRule: String = "EXPLICIT_SCHEME",
    val runtimeEvidence: RuntimeEvidenceReference? = null,
    val hostCorrelation: HostCorrelationInfo? = null
) : Serializable

/**
 * Confidence level for SDK signature identification.
 */
enum class SdkConfidence : Serializable {
    HIGH,
    MEDIUM,
    LOW;

    /** User-facing evidence tier; enum names remain stable for persisted findings and matching. */
    val evidenceLabel: String
        get() = when (this) {
            HIGH -> "Confirmed"
            MEDIUM -> "Likely"
            LOW -> "Possible"
        }
}

/**
 * Detected third-party or platform SDK matching signature catalog rules.
 */
data class SdkFinding(
    val sdkName: String,
    val category: String,
    val confidence: SdkConfidence,
    val matchedSignatures: List<String>,
    val evidence: List<String>,
    val rationale: String,
    val catalogVersion: String = "1.1.0",
    val detectedVersion: String? = null
) : Serializable

/**
 * Category of security-relevant API references.
 */
enum class ApiCategory(val title: String) : Serializable {
    DYNAMIC_CODE_LOADING("Dynamic Code Loading"),
    REFLECTION("Reflection"),
    PROCESS_EXECUTION("Process Execution"),
    NATIVE_LIBRARY_LOADING("Native Library Loading"),
    ACCESSIBILITY("Accessibility APIs"),
    DEVICE_ADMINISTRATION("Device Administration"),
    SENSITIVE_DATA_ACCESS("Sensitive Data Access"),
    /** Milestone 10 (Security Audit), Phase 10.3, part of MS10-NET01's "recognizable trust-manager/hostname-verifier/pinning code references" scope. Corrected 2026-09-14: a finding in this category is a bare method-reference/invocation match (see [DexApiScanner]'s rule comment) with no argument-flow evidence — it cannot distinguish default, fully-null TLS initialization from a genuine custom-TrustManager installation, let alone a permissive one from a pinning one. Informational only; not consumed by `core:risk` scoring or any Security Audit rule. */
    NETWORK_TRUST("Network Trust Configuration"),
    /** Milestone 10 (Security Audit), Phase 10.3, MS10-CODE01 — a cryptographic API reference. Reference tier only (see [CodePatternScanner]'s own doc for the reference/reachable/executed distinction this catalog is required to state): a call to `Cipher.getInstance`/`MessageDigest.getInstance` says nothing about which algorithm was requested, whether the surrounding code ever runs, or whether it ever actually executes. */
    CRYPTOGRAPHY("Cryptography"),
    /** Milestone 10 (Security Audit), Phase 10.3, MS10-CODE01 — a WebView configuration API reference (e.g. `addJavascriptInterface`, `setJavaScriptEnabled`). Reference tier only, same caveat as [CRYPTOGRAPHY]. */
    WEBVIEW("WebView")
}

/**
 * An identified reference to or invocation of a security-sensitive API.
 */
data class ApiFinding(
    val apiName: String,
    val category: ApiCategory,
    val callingClass: String?,
    val callingMethod: String?,
    val dexEntry: String,
    val isInvocation: Boolean,
    val explanation: String
) : Serializable

/**
 * Execution coverage and bounded safety metrics for static DEX analysis.
 */
data class StaticAnalysisCoverage(
    val dexFilesInspected: List<String> = emptyList(),
    val entriesSkipped: List<String> = emptyList(),
    val parsingErrors: List<String> = emptyList(),
    val limitsReached: Boolean = false,
    val scanDurationMs: Long = 0L
) : Serializable
