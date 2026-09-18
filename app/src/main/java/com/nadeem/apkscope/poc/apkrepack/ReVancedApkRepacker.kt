package com.nadeem.apkscope.poc.apkrepack

import android.content.Context
import android.util.Log
import java.io.File
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.FilterOutputStream
import java.io.OutputStream
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
        try {
            Log.i(TAG, "Starting repack: ${inputApk.name}")
            onProgress(0f, "Reading original APK")

            val originalHash = inputApk.calculateSha256()
            onProgress(0.08f, "Injecting Frida Gadget and loader")

            val tempApk = File(inputApk.parentFile, "temp_${System.currentTimeMillis()}.apk")
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
        } catch (e: Exception) {
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

        try {
            val loaderDex = context.assets.open("frida_loader.dex").readBytes()
            finalFileInjections[nextClassesDexName] = loaderDex
            Log.i(TAG, "Injecting FridaLoaderFactory as $nextClassesDexName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load frida_loader.dex from assets", e)
        }

        ZipFile(inputApk).use { zipFile ->
            val fos = outputApk.outputStream()
            val countingOut = CountingOutputStream(fos)
            ZipOutputStream(countingOut).use { zipOutput ->
                zipOutput.setMethod(ZipOutputStream.DEFLATED)
                zipOutput.setLevel(9)

                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    // Skip entries being replaced by injections, and strip old META-INF signatures
                    val isSignatureFile = entry.name.startsWith("META-INF/") && 
                        (entry.name.endsWith(".SF") || entry.name.endsWith(".RSA") || entry.name.endsWith(".DSA") || entry.name.endsWith(".EC") || entry.name.endsWith(".MF"))
                    
                    if (!isSignatureFile && !finalFileInjections.containsKey(entry.name) && !writtenEntries.contains(entry.name)) {
                        if (entry.name == "AndroidManifest.xml") {
                            val manifestBytes = zipFile.getInputStream(entry).readBytes()
                            val modifiedManifest = try {
                                val manifest = com.reandroid.arsc.chunk.xml.AndroidManifestBlock.load(
                                    java.io.ByteArrayInputStream(manifestBytes)
                                )
                                val appElement = manifest.applicationElement
                                if (appElement != null) {
                                    // Preserve existing factory for delegation if present
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
                            
                            val newEntry = ZipEntry("AndroidManifest.xml")
                            newEntry.method = ZipEntry.DEFLATED
                            zipOutput.putNextEntry(newEntry)
                            zipOutput.write(modifiedManifest)
                            zipOutput.closeEntry()
                            writtenEntries.add("AndroidManifest.xml")
                            continue
                        }

                        if (entry.name == "resources.arsc") {
                            // Android 11+ (R+) requires resources.arsc to be STORED and 4-byte aligned
                            val arscBytes = zipFile.getInputStream(entry).readBytes()
                            val crc = CRC32().apply { update(arscBytes) }.value

                            val newEntry = ZipEntry("resources.arsc")
                            newEntry.method = ZipEntry.STORED
                            newEntry.size = arscBytes.size.toLong()
                            newEntry.compressedSize = arscBytes.size.toLong()
                            newEntry.crc = crc

                            val currentOffset = countingOut.count
                            val nameBytes = "resources.arsc".toByteArray(Charsets.UTF_8)
                            val headerLen = 30 + nameBytes.size
                            val padding = ((4 - ((currentOffset + headerLen) % 4)) % 4).toInt()
                            if (padding > 0) {
                                newEntry.extra = ByteArray(padding)
                            }

                            zipOutput.putNextEntry(newEntry)
                            zipOutput.write(arscBytes)
                            zipOutput.closeEntry()
                            writtenEntries.add("resources.arsc")
                            continue
                        }

                        val newEntry = ZipEntry(entry.name)
                        newEntry.method = entry.method
                        if (entry.method == ZipEntry.STORED) {
                            newEntry.size = entry.size
                            newEntry.compressedSize = entry.compressedSize
                            newEntry.crc = entry.crc

                            val currentOffset = countingOut.count
                            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
                            val headerLen = 30 + nameBytes.size
                            val padding = ((4 - ((currentOffset + headerLen) % 4)) % 4).toInt()
                            if (padding > 0) {
                                newEntry.extra = ByteArray(padding)
                            }
                        }

                        zipOutput.putNextEntry(newEntry)

                        // Copy entry data
                        zipFile.getInputStream(entry).use { zipInput ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (zipInput.read(buffer).also { bytesRead = it } != -1) {
                                zipOutput.write(buffer, 0, bytesRead)
                            }
                        }
                        zipOutput.closeEntry()
                        writtenEntries.add(entry.name)
                    }
                }

                // Add injected files (new or replacement entries)
                for ((path, content) in finalFileInjections) {
                    if (!writtenEntries.contains(path)) {
                        val newEntry = ZipEntry(path)
                        newEntry.method = ZipEntry.DEFLATED

                        zipOutput.putNextEntry(newEntry)
                        zipOutput.write(content)
                        zipOutput.closeEntry()
                        writtenEntries.add(path)
                    }
                }
            }
        }
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len.toLong()
        }
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
