package com.nadeem.apkscope.poc.apkrepack

/**
 * Result of an APK repack operation.
 *
 * Carries original hash/signer, modified hash/signer, transformation version,
 * Gadget version, applied modifications list, and verification result —
 * the record described in task section 6 (Evidence Semantics).
 *
 * This class is used to build the evidence that distinguishes a modified APK
 * from the original, and to track what transformations were applied.
 */
data class RepackResult(
    // Original APK identity
    val originalApkSha256: String,
    val originalApkSignerSubject: String,
    val originalApkPackageName: String,
    val originalApkVersionCode: Int,

    // Modified APK identity
    val modifiedApkSha256: String,
    val modifiedApkSignerSubject: String,

    // Transformation metadata
    val transformationVersion: String = "POC-v1",
    val gadgetVersion: String = "frida-gadget-16.x.x-android-arm64", // TODO: set actual version
    val appliedModifications: List<Modification> = emptyList(),

    // Verification result
    val signatureVerificationStatus: String = "UNKNOWN",
    val signatureVerificationDetails: String = "",

    // Private patched APK path used only for Work Profile handoff.
    val exportedFilePath: String? = null,

    // Timestamps and session context
    val repackTimestampMs: Long = System.currentTimeMillis(),
    val transformationReason: String = "POC: Instrumented HTTPS inspection with Gadget hooks"
) {
    /**
     * A single modification applied to the APK.
     */
    data class Modification(
        val type: ModificationType,
        val description: String,
        val entryPath: String? = null
    )

    enum class ModificationType {
        LIBRARY_INJECTION,
        ASSET_INJECTION,
        MARKER_ASSET,
        SCRIPT_INJECTION,
        ALIGNMENT,
        SIGNING
    }
}
