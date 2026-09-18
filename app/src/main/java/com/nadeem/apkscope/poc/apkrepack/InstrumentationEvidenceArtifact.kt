package com.nadeem.apkscope.poc.apkrepack

import android.util.Log
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant

/**
 * Evidence artifact tracking Frida Gadget hook invocations.
 *
 * The Frida script writes hook events to a marker file in the target process's filesDir.
 * This artifact reads that file and records:
 * - Hook name (e.g., okhttp3.CertificatePinner.check)
 * - Invocation timestamp
 * - Context (hostname, certificate count, etc.)
 * - Session metadata for correlation with inspection evidence
 */
@Serializable
data class InstrumentationEvidenceArtifact(
    val version: String = "1.0",
    val hookName: String = "",
    val hookInvocations: List<HookInvocation> = emptyList(),
    val gadgetVersion: String = "17.18.0",
    val scriptVersion: String = "1.0",
    val status: String = "PENDING", // PENDING, ARMED, TRIGGERED, NO_SIGNAL
    val metadata: Map<String, String> = emptyMap()
) {
    @Serializable
    data class HookInvocation(
        val timestamp: String,
        val hostname: String?,
        val certificateCount: Int = 0,
        val details: String = ""
    )

    companion object {
        private const val TAG = "InstrumentationEvidence"
        private const val MARKER_FILENAME = "poc_hook_fired.txt"

        /**
         * Create evidence artifact by reading hook marker file from target process.
         *
         * @param targetFilesDir The target APK's filesDir (obtained via run-as or content provider)
         * @param sessionMetadata Additional metadata to include (e.g., APK hash, signer)
         * @return Artifact with hook invocations if file exists, empty artifact otherwise
         */
        fun fromTargetProcess(targetFilesDir: File, sessionMetadata: Map<String, String> = emptyMap()): InstrumentationEvidenceArtifact {
            Log.i(TAG, "Reading instrumentation evidence from: ${targetFilesDir.absolutePath}")

            val markerFile = File(targetFilesDir, MARKER_FILENAME)

            val invocations = if (markerFile.exists()) {
                try {
                    parseHookLog(markerFile)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse hook log: ${e.message}", e)
                    emptyList()
                }
            } else {
                Log.w(TAG, "Hook marker file not found: ${markerFile.absolutePath}")
                emptyList()
            }

            val status = when {
                invocations.isNotEmpty() -> "TRIGGERED"
                markerFile.exists() -> "ARMED"
                else -> "PENDING"
            }

            return InstrumentationEvidenceArtifact(
                version = "1.0",
                hookName = "okhttp3.CertificatePinner.check",
                hookInvocations = invocations,
                gadgetVersion = "17.18.0",
                scriptVersion = "1.0",
                status = status,
                metadata = sessionMetadata
            )
        }

        /**
         * Parse hook invocations from the marker file written by Frida script.
         *
         * Format per line:
         * Hook fired: <ISO8601-timestamp> | Hostname: <hostname> | Certs: <count>
         */
        private fun parseHookLog(markerFile: File): List<HookInvocation> {
            val invocations = mutableListOf<HookInvocation>()

            try {
                markerFile.forEachLine { line ->
                    if (line.startsWith("Hook fired:")) {
                        // Parse: "Hook fired: 2026-09-15T06:15:30.123Z | Hostname: example.com | Certs: 1"
                        val parts = line.split(" | ")

                        val timestamp = parts.getOrNull(0)?.removePrefix("Hook fired: ")?.trim() ?: Instant.now().toString()
                        val hostname = parts.getOrNull(1)?.removePrefix("Hostname: ")?.trim()
                        val certCountStr = parts.getOrNull(2)?.removePrefix("Certs: ")?.trim() ?: "0"
                        val certCount = certCountStr.toIntOrNull() ?: 0

                        invocations.add(HookInvocation(
                            timestamp = timestamp,
                            hostname = hostname,
                            certificateCount = certCount,
                            details = line
                        ))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing hook log: ${e.message}", e)
            }

            Log.i(TAG, "Parsed ${invocations.size} hook invocations")
            return invocations
        }

        /**
         * Clear the hook marker file (for cleanup between tests).
         */
        fun clearMarkerFile(targetFilesDir: File) {
            val markerFile = File(targetFilesDir, MARKER_FILENAME)
            if (markerFile.delete()) {
                Log.i(TAG, "Cleared hook marker file")
            }
        }
    }
}
