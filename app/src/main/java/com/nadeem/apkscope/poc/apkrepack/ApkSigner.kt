package com.nadeem.apkscope.poc.apkrepack

import android.content.Context
import android.util.Log
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import javax.security.auth.x500.X500Principal

/**
 * POC APK signer using apksig library and a freshly-generated POC signing identity.
 *
 * Generates a POC-specific RSA keypair + self-signed certificate (not the product CA, not the
 * suite's shared upload key), applies APK Signature Scheme v2/v3, verifies, and reports the result.
 */
class ApkSigner(private val context: Context) {

    private val TAG = "ApkSigner"
    private val keyStoreDir = File(context.filesDir, "poc_repack/signing")

    init {
        keyStoreDir.mkdirs()
    }

    /**
     * Sign an APK with a POC signing identity.
     *
     * @param apkFile The APK to sign
     * @param outputApk Where to write the signed APK
     * @return SigningResult with hash, signer info, and verification status
     * @throws Exception if signing fails
     */
    fun sign(apkFile: File, outputApk: File): SigningResult {
        if (!apkFile.exists() || !apkFile.isFile) {
            throw IllegalArgumentException("APK file does not exist: ${apkFile.absolutePath}")
        }

        try {
            // Get or create the POC signing identity
            val (privKey, cert) = getPocSigningIdentity()

            // Sign the APK using apksig
            val signer = ApkSigner.Builder(listOf(ApkSigner.SignerConfig.Builder(
                "POC Signer",
                privKey,
                listOf(cert)
            ).build()))
                .setInputApk(apkFile)
                .setOutputApk(outputApk)
                .setMinSdkVersion(30) // Match fixture min SDK
                .build()

            signer.sign()
            Log.i(TAG, "APK signed successfully: ${outputApk.absolutePath}")

            // Verify the signed APK immediately
            val verification = verifyApk(outputApk, cert)

            return SigningResult(
                success = verification.isSuccessful,
                signedApkHash = outputApk.calculateSha256(),
                signerSubject = cert.subjectX500Principal.name,
                verificationStatus = if (verification.isSuccessful) "VERIFIED" else "VERIFICATION_FAILED",
                verificationDetails = verification.errors.joinToString(", ") { it.message }
            )
        } catch (e: Exception) {
            Log.e(TAG, "APK signing failed: ${e.message}", e)
            throw e
        }
    }

    /**
     * Get or create the POC signing identity (RSA 2048 keypair + self-signed cert).
     *
     * For POC v1, generates a fresh identity each session (no persistence).
     * Future enhancement: persist identity across sessions for reproducible test results.
     */
    private fun getPocSigningIdentity(): Pair<PrivateKey, X509Certificate> {
        Log.i(TAG, "Generating fresh POC signing identity (session-scoped)")
        return generateNewSigningIdentity()
    }

    /**
     * Generate a new RSA 2048 keypair and self-signed certificate.
     */
    private fun generateNewSigningIdentity(): Pair<PrivateKey, X509Certificate> {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.genKeyPair()

        // Build a self-signed certificate
        val now = System.currentTimeMillis()
        val startDate = Date(now)
        val endDate = Date(now + 10L * 365L * 24L * 60L * 60L * 1000L) // 10 years

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

        Log.i(TAG, "Generated POC signing cert: ${cert.subjectX500Principal.name}")
        return Pair(keyPair.private, cert)
    }

    // TODO: Persistence (POC.3 enhancement)
    // For POC v1, we generate fresh identities each session.
    // Future implementation should serialize/deserialize signing identity via PKCS#8/PEM
    // for reproducible test results across APK repack sessions.

    /**
     * Verify a signed APK using apksig.
     */
    private fun verifyApk(apkFile: File, expectedSigner: X509Certificate): ApkVerificationResult {
        return try {
            val verifier = ApkVerifier.Builder(apkFile).build()
            val result = verifier.verify()

            ApkVerificationResult(
                isSuccessful = result.isVerified,
                errors = result.errors.map { ApkVerificationError(it.toString()) }
            )
        } catch (e: Exception) {
            ApkVerificationResult(
                isSuccessful = false,
                errors = listOf(ApkVerificationError("Verification exception: ${e.message}"))
            )
        }
    }

    data class SigningResult(
        val success: Boolean,
        val signedApkHash: String,
        val signerSubject: String,
        val verificationStatus: String,
        val verificationDetails: String
    )

    private data class ApkVerificationError(val message: String)

    private data class ApkVerificationResult(
        val isSuccessful: Boolean,
        val errors: List<ApkVerificationError> = emptyList()
    )

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
