package com.nadeem.apkscope.poc.apkrepack

import android.content.Context
import android.util.Log
import java.io.File
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.ZipEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.util.zip.CRC32
import java.util.zip.ZipFile
import javax.security.auth.x500.X500Principal

/**
 * Unified APK repacker that handles extraction, file injection, alignment, and signing
 * in one integrated workflow (replacing the multi-stage manual pipeline).
 *
 * For POC.4+, this will support Frida Gadget injection (lib/ + assets/ + marker asset).
 */
class ReVancedApkRepacker(private val context: Context) {

    private val TAG = "ReVancedApkRepacker"
    private val copyBufferSize = 64 * 1024

    data class RepackResult(
        val success: Boolean,
        val outputApk: File,
        val originalHash: String,
        val modifiedHash: String,
        val signerSubject: String,
        val error: String? = null
    )

    fun repack(
        inputApk: File,
        outputApk: File,
        fileInjections: Map<String, ByteArray> = emptyMap(),
        onProgress: (fraction: Float, message: String) -> Unit = { _, _ -> },
    ): RepackResult {
        var tempApk: File? = null
        try {
            Log.i(TAG, "Starting repack: ${inputApk.name}")
            onProgress(0f, "Reading original APK")

            val originalHash = inputApk.calculateSha256()
            onProgress(0.08f, "Injecting Frida Gadget and loader")

            tempApk = File(inputApk.parentFile, "temp_${System.currentTimeMillis()}.apk")
            repackApkWithInjections(inputApk, tempApk, fileInjections)
            Log.i(TAG, "✓ APK repacked with file injections")
            onProgress(0.58f, "Aligning APK contents")

            val (privKey, cert) = generateSigningIdentity()
            onProgress(0.68f, "Signing patched APK")
            signApkWithApksig(tempApk, outputApk, privKey, cert)
            Log.i(TAG, "✓ APK signed with POC identity")
            onProgress(0.93f, "Verifying patched APK")

            tempApk.delete()

            val modifiedHash = outputApk.calculateSha256()
            onProgress(1f, "Patch complete")

            return RepackResult(
                success = true,
                outputApk = outputApk,
                originalHash = originalHash,
                modifiedHash = modifiedHash,
                signerSubject = cert.subjectX500Principal.name
            )
        } catch (e: OutOfMemoryError) {
            tempApk?.delete()
            outputApk.delete()
            Log.e(TAG, "Repack ran out of memory for ${inputApk.name}", e)
            return RepackResult(
                success = false,
                outputApk = outputApk,
                originalHash = "error",
                modifiedHash = "error",
                signerSubject = "error",
                error = "This APK is too large to patch on the device. Try a smaller APK or free device memory and retry."
            )
        } catch (e: Exception) {
            tempApk?.delete()
            outputApk.delete()
            Log.e(TAG, "Repack failed: ${e.message}", e)
            return RepackResult(
                success = false,
                outputApk = outputApk,
                originalHash = "error",
                modifiedHash = "error",
                signerSubject = "error",
                error = e.message ?: "Unknown error"
            )
        }
    }

    private fun repackApkWithInjections(
        inputApk: File,
        outputApk: File,
        fileInjections: Map<String, ByteArray>
    ) {
        val writtenEntries = mutableSetOf<String>()

        var finalFileInjections = fileInjections.toMutableMap()

        // Pass 1: Find the max classes.dex index to determine where to inject frida_loader.dex
        var maxClassesDexIndex = 0
        ZipFile(inputApk).use { zipFile ->
            zipFile.entries().asSequence().forEach { entry ->
                val name = entry.name
                if (name.startsWith("classes") && name.endsWith(".dex")) {
                    val numStr = name.removePrefix("classes").removeSuffix(".dex")
                    if (numStr.isEmpty()) maxClassesDexIndex = Math.max(maxClassesDexIndex, 1)
                    else maxClassesDexIndex = Math.max(maxClassesDexIndex, numStr.toIntOrNull() ?: 1)
                }
            }
        }
        val nextClassesDexName = if (maxClassesDexIndex == 0) "classes.dex" else "classes${maxClassesDexIndex + 1}.dex"

        // Fail-safe: the manifest patch below rewrites android:appComponentFactory to name
        // com.nadeem.apkscope.FridaLoaderFactory, and this dex is the ONLY thing that delivers
        // that class into the target. The two must be atomic. If the loader dex cannot be
        // obtained, abort the whole repack — never emit an APK whose manifest names a class
        // that lives in no dex, which crashes at launch (ClassNotFoundException in
        // LoadedApk.createAppFactory) before any app code runs. The dex is built from source
        // by the :app:generateFridaLoaderDex Gradle task and packaged into app assets.
        val loaderDex: ByteArray = try {
            context.assets.open("frida_loader.dex").use { it.readBytes() }
        } catch (e: Exception) {
            throw IllegalStateException(
                "frida_loader.dex is missing from app assets — the Frida loader was not built " +
                    "into this build, so the AppComponentFactory class cannot be injected. " +
                    "Rebuild the app (the :app:generateFridaLoaderDex task produces it). " +
                    "Refusing to patch the manifest and ship a launch-crashing APK.",
                e,
            )
        }
        // Validate the DEX magic ("dex\n") so a truncated or wrong asset fails here, loudly,
        // rather than producing an APK the platform rejects or that crashes at launch.
        require(
            loaderDex.size >= 8 &&
                loaderDex[0] == 'd'.code.toByte() &&
                loaderDex[1] == 'e'.code.toByte() &&
                loaderDex[2] == 'x'.code.toByte() &&
                loaderDex[3] == 0x0a.toByte()
        ) {
            "frida_loader.dex is present but is not a valid DEX file (bad magic); refusing to inject."
        }
        finalFileInjections[nextClassesDexName] = loaderDex
        Log.i(TAG, "Injecting FridaLoaderFactory as $nextClassesDexName (${loaderDex.size} bytes)")

        ZipFile(inputApk).use { zipFile ->
            // Apache commons-compress writes with random access to the output file, so it back-
            // patches each entry's sizes/CRC into the local header instead of appending a ZIP data
            // descriptor. java.util.zip.ZipOutputStream cannot do this — it sets general-purpose
            // flag bit 3 (data descriptor) on every DEFLATED entry, and Android's XML asset loader
            // (nativeOpenXmlAsset) then rejects such entries as "Corrupt XML binary file" even when
            // the AXML is valid. commons-compress reproduces exactly what aapt emits.
            ZipArchiveOutputStream(outputApk).use { zipOutput ->
                zipOutput.setUseZip64(Zip64Mode.AsNeeded)
                zipOutput.setLevel(9)
                // Entry names are ASCII; do NOT set general-purpose flag bit 11 (UTF-8/EFS).
                // Android's XML asset loader rejects res/xml entries whose flag bits differ from
                // what aapt emits (0x0000). With this off + DEFLATED-without-data-descriptor
                // (commons-compress back-patches sizes), repacked entries match aapt exactly.
                zipOutput.setUseLanguageEncodingFlag(false)
                zipOutput.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER)

                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    // Skip entries being replaced by injections, and strip old META-INF signatures.
                    val isSignatureFile = entry.name.startsWith("META-INF/") &&
                        (entry.name.endsWith(".SF") || entry.name.endsWith(".RSA") || entry.name.endsWith(".DSA") || entry.name.endsWith(".EC") || entry.name.endsWith(".MF"))
                    if (isSignatureFile || finalFileInjections.containsKey(entry.name) || writtenEntries.contains(entry.name)) {
                        continue
                    }

                    if (entry.name == "AndroidManifest.xml") {
                        val manifestBytes = zipFile.getInputStream(entry).readBytes()
                        val modifiedManifest = try {
                            val manifest = com.reandroid.arsc.chunk.xml.AndroidManifestBlock.load(
                                java.io.ByteArrayInputStream(manifestBytes)
                            )
                            val appElement = manifest.applicationElement
                            if (appElement != null) {
                                // Preserve existing factory for delegation if present.
                                val existingFactoryAttr = appElement.searchAttributeByResourceId(0x0101057a)
                                    ?: appElement.searchAttributeByName("appComponentFactory")
                                val existingFactory = existingFactoryAttr?.valueAsString
                                if (!existingFactory.isNullOrBlank() && existingFactory != "com.nadeem.apkscope.FridaLoaderFactory") {
                                    Log.i(TAG, "Preserving existing appComponentFactory: $existingFactory")
                                    finalFileInjections["assets/poc_orig_factory.txt"] = existingFactory.toByteArray(Charsets.UTF_8)
                                }

                                val attr = appElement.getOrCreateAndroidAttribute("appComponentFactory", 0x0101057a)
                                attr.setValueAsString("com.nadeem.apkscope.FridaLoaderFactory")

                                val extractAttr = appElement.getOrCreateAndroidAttribute("extractNativeLibs", 0x010104ea)
                                extractAttr.setValueAsBoolean(true)

                                manifest.refresh()
                                Log.i(TAG, "Successfully injected AppComponentFactory and extractNativeLibs into AndroidManifest.xml")
                                manifest.bytes
                            } else {
                                Log.w(TAG, "No <application> tag found in AndroidManifest.xml")
                                manifestBytes
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse AndroidManifest.xml with ARSCLib: ${e.message}", e)
                            manifestBytes
                        }
                        writeDeflated(zipOutput, "AndroidManifest.xml", modifiedManifest)
                        writtenEntries.add("AndroidManifest.xml")
                        continue
                    }

                    if (entry.name == "resources.arsc") {
                        // Android 11+ requires resources.arsc to be STORED and 4-byte aligned.
                        copyEntry(zipFile, entry, zipOutput, forceStoredAligned = true)
                        writtenEntries.add("resources.arsc")
                        continue
                    }

                    // Preserve the original compression method without materializing the entire
                    // APK entry. Large native libraries and APK assets are copied in chunks so a
                    // large target cannot exhaust the app heap during repacking.
                    copyEntry(zipFile, entry, zipOutput)
                    writtenEntries.add(entry.name)
                }

                // Injected / replacement entries (loader dex, gadget libs, scripts, NSC).
                for ((path, content) in finalFileInjections) {
                    if (writtenEntries.contains(path)) continue
                    writeDeflated(zipOutput, path, content)
                    writtenEntries.add(path)
                }
            }
        }
    }

    private fun copyEntry(
        zipFile: ZipFile,
        sourceEntry: ZipEntry,
        zipOutput: ZipArchiveOutputStream,
        forceStoredAligned: Boolean = false,
    ) {
        val stored = forceStoredAligned || sourceEntry.method == ZipEntry.STORED
        val outputEntry = ZipArchiveEntry(sourceEntry.name)
        outputEntry.method = if (stored) ZipArchiveEntry.STORED else ZipArchiveEntry.DEFLATED
        if (stored) {
            require(sourceEntry.size >= 0 && sourceEntry.crc >= 0) {
                "Stored APK entry ${sourceEntry.name} is missing size or CRC metadata"
            }
            outputEntry.size = sourceEntry.size
            outputEntry.crc = sourceEntry.crc
            outputEntry.setAlignment(4)
        }
        zipOutput.putArchiveEntry(outputEntry)
        zipFile.getInputStream(sourceEntry).use { input ->
            val buffer = ByteArray(copyBufferSize)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                zipOutput.write(buffer, 0, read)
            }
        }
        zipOutput.closeArchiveEntry()
    }

    private fun writeDeflated(zipOutput: ZipArchiveOutputStream, name: String, content: ByteArray) {
        val entry = ZipArchiveEntry(name)
        entry.method = ZipArchiveEntry.DEFLATED
        zipOutput.putArchiveEntry(entry)
        zipOutput.write(content)
        zipOutput.closeArchiveEntry()
    }

    private fun writeStoredAligned(zipOutput: ZipArchiveOutputStream, name: String, content: ByteArray) {
        val entry = ZipArchiveEntry(name)
        entry.method = ZipArchiveEntry.STORED
        entry.size = content.size.toLong()
        entry.crc = CRC32().apply { update(content) }.value
        entry.setAlignment(4)
        zipOutput.putArchiveEntry(entry)
        zipOutput.write(content)
        zipOutput.closeArchiveEntry()
    }

    private fun signApkWithApksig(
        inputApk: File,
        outputApk: File,
        privKey: PrivateKey,
        cert: X509Certificate
    ) {
        val signerConfig = com.android.apksig.ApkSigner.SignerConfig.Builder(
            "POC Signer",
            privKey,
            listOf(cert)
        ).build()

        val signer = com.android.apksig.ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setMinSdkVersion(30)
            .build()

        signer.sign()
        Log.i(TAG, "Signed APK: ${outputApk.absolutePath}")
    }

    private fun generateSigningIdentity(): Pair<PrivateKey, X509Certificate> {
        val keyStoreFile = File(context.filesDir, "poc_repack/signing/poc_keystore.p12")
        val password = "poc_password".toCharArray()
        val alias = "poc_key"
        
        val keyStore = java.security.KeyStore.getInstance("PKCS12")
        if (keyStoreFile.exists()) {
            try {
                keyStoreFile.inputStream().use { keyStore.load(it, password) }
                val privKey = keyStore.getKey(alias, password) as? PrivateKey
                val cert = keyStore.getCertificate(alias) as? X509Certificate
                if (privKey != null && cert != null) {
                    Log.i(TAG, "Loaded existing POC signing cert: ${cert.subjectX500Principal.name}")
                    return Pair(privKey, cert)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load keystore, generating new one", e)
            }
        }
        
        keyStore.load(null, null)
        
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.genKeyPair()

        val now = System.currentTimeMillis()
        val startDate = Date(now)
        val endDate = Date(now + 10L * 365L * 24L * 60L * 60L * 1000L)

        val issuer = X500Principal("CN=APK Scope POC Signer, OU=POC, O=APK Scope")
        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            java.math.BigInteger.valueOf(now),
            startDate,
            endDate,
            issuer,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

        keyStore.setKeyEntry(alias, keyPair.private, password, arrayOf(cert))
        keyStoreFile.parentFile?.mkdirs()
        keyStoreFile.outputStream().use { keyStore.store(it, password) }

        Log.i(TAG, "Generated POC signing cert: ${cert.subjectX500Principal.name}")
        return Pair(keyPair.private, cert)
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
