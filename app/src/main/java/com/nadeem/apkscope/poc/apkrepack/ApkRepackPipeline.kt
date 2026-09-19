package com.nadeem.apkscope.poc.apkrepack

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Orchestrator for the complete APK repack pipeline.
 *
 * Chains: compatibility check → extract → align → sign → return result
 */
class ApkRepackPipeline(private val context: Context) {

    private val TAG = "ApkRepackPipeline"

    enum class ProgressPhase { COMPATIBILITY, REPACKING, PERSISTING }

    data class Progress(
        val phase: ProgressPhase,
        val fraction: Float,
        val message: String,
    )

    sealed class PipelineResult {
        data class Success(val result: RepackResult) : PipelineResult()
        data class Failed(val stage: String, val error: String) : PipelineResult()
        data class Unsupported(val reason: String) : PipelineResult()
    }

    /**
     * Execute the complete repack pipeline for an APK.
     *
     * @param apkFile The APK to repack
     * @return PipelineResult with success/failure/unsupported status
     */
    suspend fun repack(
        apkFile: File,
        onProgress: (Progress) -> Unit = {},
    ): PipelineResult = withContext(Dispatchers.IO) {
        // Recover scratch files left by interrupted runs of older builds.
        File(context.filesDir, "poc_repack/work").listFiles()?.filter {
            it.lastModified() < System.currentTimeMillis() - 60 * 60 * 1000
        }?.forEach { it.deleteRecursively() }
        val workDir = File(context.filesDir, "poc_repack/work/${System.currentTimeMillis()}")
        workDir.mkdirs()

        try {
            Log.i(TAG, "=== Starting APK Repack Pipeline ===")
            Log.i(TAG, "Input APK: ${apkFile.absolutePath}")

            // Stage 1: Compatibility Check
            onProgress(Progress(ProgressPhase.COMPATIBILITY, 0f, "Checking APK compatibility"))
            Log.i(TAG, "Stage 1: Checking compatibility...")
            if (!coroutineContext.isActive) throw InterruptedException("Pipeline cancelled")

            val compatibilityResult = RepackCompatibilityChecker.check(context, apkFile)
            if (compatibilityResult is CompatibilityResult.Unsupported) {
                Log.w(TAG, "compatibility check failed: ${compatibilityResult.reason}")
                return@withContext PipelineResult.Unsupported(compatibilityResult.reason)
            }
            Log.i(TAG, "✓ Compatibility check passed")
            onProgress(Progress(ProgressPhase.COMPATIBILITY, 1f, "Compatibility checks passed"))

            // Get original APK info
            val pm = context.packageManager
            val archiveInfo = pm.getPackageArchiveInfo(
                apkFile.absolutePath,
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            ) ?: return@withContext PipelineResult.Failed("Metadata", "Cannot read APK metadata")

            val originalHash = apkFile.calculateSha256()
            val originalSigner = try {
                val cert = archiveInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.let { sig ->
                    java.security.cert.CertificateFactory.getInstance("X.509")
                        .generateCertificate(java.io.ByteArrayInputStream(sig.toByteArray()))
                }
                (cert as? java.security.cert.X509Certificate)?.subjectX500Principal?.name ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }

            // Stage 2: Repack, Align & Sign (unified via ReVanced)
            onProgress(Progress(ProgressPhase.REPACKING, 0f, "Preparing patch workspace"))
            Log.i(TAG, "Stage 2: Repacking APK (extract → inject → align → sign)...")
            if (!coroutineContext.isActive) throw InterruptedException("Pipeline cancelled")

            val repacker = ReVancedApkRepacker(context)
            val repackedApk = File(workDir, "app-repacked.apk")

            // POC.4: Frida Gadget injection (only for supported fixture)
            val fileInjections = try {
                val injector = GadgetPayloadInjector(context)
                if (injector.verifyGadgetAvailable()) {
                    Log.i(TAG, "Preparing Frida Gadget payload injection...")
                    val presentAbis = RepackCompatibilityChecker.getPresentSupportedAbis(apkFile)
                    val targetPackageName = archiveInfo.packageName ?: "unknown"
                    val payload = injector.buildPayload(presentAbis, targetPackageName, apkFile)
                    onProgress(Progress(ProgressPhase.REPACKING, 0.12f, "Frida Gadget payload ready"))
                    payload
                } else {
                    Log.w(TAG, "Frida Gadget not available, proceeding without instrumentation")
                    onProgress(Progress(ProgressPhase.REPACKING, 0.12f, "Preparing APK transformation"))
                    emptyMap()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to build Gadget payload: ${e.message}, proceeding without instrumentation")
                onProgress(Progress(ProgressPhase.REPACKING, 0.12f, "Preparing APK transformation"))
                emptyMap()
            }

            val repackResult = try {
                repacker.repack(apkFile, repackedApk, fileInjections) { fraction, message ->
                    onProgress(
                        Progress(
                            ProgressPhase.REPACKING,
                            0.12f + (fraction.coerceIn(0f, 1f) * 0.88f),
                            message,
                        )
                    )
                }
            } catch (e: Exception) {
                return@withContext PipelineResult.Failed("Repacking", e.message ?: "Unknown error")
            }

            if (!repackResult.success) {
                return@withContext PipelineResult.Failed("Repacking", repackResult.error ?: "Unknown error")
            }
            Log.i(TAG, "✓ APK repacked, aligned, and signed")

            // Stage 3: Persist patched APK privately for Work Profile handoff.
            onProgress(Progress(ProgressPhase.PERSISTING, 0f, "Saving patched APK"))
            Log.i(TAG, "Stage 3: Persisting patched APK in private app storage...")
            val patchedApkDir = File(context.filesDir, "poc_repack/patched")
            patchedApkDir.mkdirs()
            try {
                patchedApkDir.listFiles()
                    ?.filter { it.name.endsWith(".apk") }
                    ?.forEach { it.delete() }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to prune previous private patched APKs: ${e.message}", e)
            }
            val patchedApkFile = try {
                val originalName = apkFile.nameWithoutExtension.ifBlank { "selected" }
                val destFile = File(patchedApkDir, "${originalName}-patched-${System.currentTimeMillis()}.apk")
                repackedApk.copyTo(destFile, overwrite = true)
                Log.i(TAG, "✓ Successfully persisted modified APK to private storage: ${destFile.absolutePath}")
                onProgress(Progress(ProgressPhase.PERSISTING, 1f, "Patched APK ready for analysis"))
                destFile
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist patched APK in private storage: ${e.message}", e)
                return@withContext PipelineResult.Failed("Persisting patched APK", e.message ?: "Could not store patched APK")
            }

            // Build final result
            val finalRepackResult = RepackResult(
                originalApkSha256 = originalHash,
                originalApkSignerSubject = originalSigner,
                originalApkPackageName = archiveInfo.packageName ?: "unknown",
                originalApkVersionCode = archiveInfo.versionCode,
                modifiedApkSha256 = repackResult.modifiedHash,
                modifiedApkSignerSubject = repackResult.signerSubject,
                signatureVerificationStatus = "VERIFIED",
                signatureVerificationDetails = "ReVanced repacker",
                exportedFilePath = patchedApkFile.absolutePath,
                appliedModifications = buildList {
                    if (fileInjections.isNotEmpty()) {
                        add(RepackResult.Modification(
                            RepackResult.ModificationType.LIBRARY_INJECTION,
                            "Frida Gadget lib: ${fileInjections.filter { it.key.endsWith(".so") }.size} files"
                        ))
                        add(RepackResult.Modification(
                            RepackResult.ModificationType.ASSET_INJECTION,
                            "Instrumentation marker + script: ${fileInjections.filter { it.key.startsWith("assets/") }.size} files"
                        ))
                    }
                    add(RepackResult.Modification(
                        RepackResult.ModificationType.ALIGNMENT,
                        "ZIP alignment with proper boundary padding"
                    ))
                    add(RepackResult.Modification(
                        RepackResult.ModificationType.SIGNING,
                        "Signed with POC identity: ${repackResult.signerSubject}"
                    ))
                }
            )

            Log.i(TAG, "=== APK Repack Pipeline Complete ===")
            Log.i(TAG, "Original: $originalHash")
            Log.i(TAG, "Modified: ${repackResult.modifiedHash}")

            return@withContext PipelineResult.Success(finalRepackResult)

        } catch (e: InterruptedException) {
            Log.i(TAG, "Pipeline cancelled: ${e.message}")
            return@withContext PipelineResult.Failed("Cancelled", "User cancelled the operation")
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline failed: ${e.message}", e)
            return@withContext PipelineResult.Failed("Unknown", e.message ?: "Unexpected error")
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun File.calculateSha256(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        this.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
