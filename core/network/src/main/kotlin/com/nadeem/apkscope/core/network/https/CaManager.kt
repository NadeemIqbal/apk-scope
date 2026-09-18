package com.nadeem.apkscope.core.network.https

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Manages the locally generated Certificate Authority (CA) and dynamic leaf certificate generation
 * for requested hostnames in the HTTPS inspection POC.
 *
 * Rules:
 * - CA is generated locally on device; private key is never committed, bundled, logged, or exported.
 * - Private key is stored in protected application internal storage with private file permissions.
 * - Dynamic leaf certificates are signed by this CA on-the-fly for intercepted SNI hostnames.
 * - Explicit reset removes keys, certificates, in-memory caches, and resets state.
 */
class CaManager(private val storageDir: File) {

 private val random = SecureRandom()
 private val certConverter = JcaX509CertificateConverter()
 private val leafCertCache = ConcurrentHashMap<String, SSLContext>()

 @Volatile
 private var caKeyPair: KeyPair? = null

 @Volatile
 private var caCertificate: X509Certificate? = null

 init {
  Security.removeProvider("BC")
  Security.insertProviderAt(BouncyCastleProvider(), 1)
  loadOrGenerateCa()
 }

 @Synchronized
 private fun loadOrGenerateCa() {
  val keyFile = File(storageDir, "poc_ca.key")
  val certFile = File(storageDir, "poc_ca.crt")

  if (keyFile.exists() && certFile.exists()) {
   try {
    val kf = KeyFactory.getInstance("RSA")
    val keyBytes = keyFile.readBytes()
    val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(keyBytes))

    val cf = CertificateFactory.getInstance("X.509")
    val cert = FileInputStream(certFile).use { cf.generateCertificate(it) as X509Certificate }
    caCertificate = cert
    caKeyPair = KeyPair(cert.publicKey, privKey)
    return
   } catch (_: Exception) {
    // If corrupted or unreadable, regenerate
    keyFile.delete()
    certFile.delete()
   }
  }

  generateNewCa()
 }

 @Synchronized
 fun generateNewCa() {
  storageDir.mkdirs()
  val kpg = KeyPairGenerator.getInstance("RSA")
  kpg.initialize(2048, random)
  val keyPair = kpg.generateKeyPair()

  val notBefore = Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000L)
  val notAfter = Date(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000L) // 30 days validity for POC
  val serial = BigInteger(64, random).abs()

  val issuer = X500Name("CN=APK Scope POC Inspection CA, O=APK Scope, OU=HTTPS Inspection POC")
  val subject = issuer

  val builder = JcaX509v3CertificateBuilder(
   issuer,
   serial,
   notBefore,
   notAfter,
   subject,
   keyPair.public
  )

  builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
  builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))

  val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
  val certHolder = builder.build(signer)
  val cert = certConverter.getCertificate(certHolder)

  // Save to private app storage
  val keyFile = File(storageDir, "poc_ca.key")
  val certFile = File(storageDir, "poc_ca.crt")

  FileOutputStream(keyFile).use { it.write(keyPair.private.encoded) }
  FileOutputStream(certFile).use { it.write(cert.encoded) }

  caKeyPair = keyPair
  caCertificate = cert
  leafCertCache.clear()
 }

 fun getCaCertificate(): X509Certificate? = caCertificate

 fun hasCaCertificate(): Boolean = caCertificate != null && File(storageDir, "poc_ca.crt").exists()

 fun getCaCertDer(): ByteArray? = caCertificate?.encoded

 fun getCaCertPem(): String? {
  val cert = caCertificate ?: return null
  val b64 = android.util.Base64.encodeToString(cert.encoded, android.util.Base64.DEFAULT)
  return "-----BEGIN CERTIFICATE-----\n$b64-----END CERTIFICATE-----\n"
 }

 /**
  * Returns or creates an [SSLContext] initialized with an on-the-fly leaf certificate
  * signed by our local CA for [hostname].
  */
 fun getOrCreateServerSslContext(hostname: String): SSLContext {
  val cached = leafCertCache[hostname]
  if (cached != null) return cached

  val ca = caCertificate ?: throw IllegalStateException("CA certificate not initialized")
  val caPair = caKeyPair ?: throw IllegalStateException("CA key pair not initialized")

  val kpg = KeyPairGenerator.getInstance("RSA")
  kpg.initialize(2048, random)
  val leafKeyPair = kpg.generateKeyPair()

  val notBefore = Date(System.currentTimeMillis() - 60 * 60 * 1000L)
  val notAfter = Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000L) // 7 days
  val serial = BigInteger(64, random).abs()

  val issuer = X500Name.getInstance(ca.subjectX500Principal.encoded)
  val subject = X500Name("CN=$hostname, O=APK Scope Dynamic, OU=POC HTTPS")

  val builder = JcaX509v3CertificateBuilder(
   ca,
   serial,
   notBefore,
   notAfter,
   subject,
   leafKeyPair.public
  )

  builder.addExtension(Extension.basicConstraints, false, BasicConstraints(false))
  builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
  builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))

  val san = GeneralNames(arrayOf(GeneralName(GeneralName.dNSName, hostname)))
  builder.addExtension(Extension.subjectAlternativeName, false, san)

  val signer = JcaContentSignerBuilder("SHA256withRSA").build(caPair.private)
  val leafCert = certConverter.getCertificate(builder.build(signer))

  val keyStore = try { KeyStore.getInstance("PKCS12", "BC") } catch (_: Exception) { KeyStore.getInstance("PKCS12") }
  keyStore.load(null, null)
  val chain = arrayOf(leafCert, ca)
  val password = "poc-password".toCharArray()
  keyStore.setKeyEntry("key", leafKeyPair.private, password, chain)

  val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
  kmf.init(keyStore, password)

  val sslContext = SSLContext.getInstance("TLS")
  sslContext.init(kmf.keyManagers, null, random)

  leafCertCache[hostname] = sslContext
  return sslContext
 }

 @Synchronized
 fun reset() {
  leafCertCache.clear()
  caKeyPair = null
  caCertificate = null
  try {
   File(storageDir, "poc_ca.key").delete()
   File(storageDir, "poc_ca.crt").delete()
  } catch (_: Exception) {}
 }
}
