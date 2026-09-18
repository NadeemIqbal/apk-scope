package com.nadeem.apkscope.core.network.https

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.util.Date
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

class HttpsTlsValidationTest {

 companion object {
  @BeforeClass
  @JvmStatic
  fun setupProvider() {
   if (Security.getProvider("BC") == null) {
    Security.addProvider(BouncyCastleProvider())
   }
  }
 }

 @Test
 fun testUpstreamHostnameMismatchRejection() {
  val verifier = HttpsURLConnection.getDefaultHostnameVerifier()

  // Verify that an SSLSession for evil.com is rejected when connecting to target.com
  val kpg = KeyPairGenerator.getInstance("RSA")
  kpg.initialize(2048)
  val kp = kpg.generateKeyPair()

  val notBefore = Date(System.currentTimeMillis() - 10000)
  val notAfter = Date(System.currentTimeMillis() + 100000)
  val name = X500Name("CN=evil.com")
  val builder = JcaX509v3CertificateBuilder(name, BigInteger.ONE, notBefore, notAfter, name, kp.public)
  val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(kp.private)
  val cert = JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer))

  val ks = KeyStore.getInstance(KeyStore.getDefaultType())
  ks.load(null, null)
  ks.setKeyEntry("key", kp.private, "pwd".toCharArray(), arrayOf(cert))
  val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
  kmf.init(ks, "pwd".toCharArray())
  val serverCtx = SSLContext.getInstance("TLS")
  serverCtx.init(kmf.keyManagers, null, SecureRandom())

  val serverSocket = serverCtx.serverSocketFactory.createServerSocket(0)
  val port = serverSocket.localPort

  val clientCtx = SSLContext.getInstance("TLS")
  // Trust all manager ONLY for the dummy local test server to examine hostname verification
  clientCtx.init(null, arrayOf(object : javax.net.ssl.X509TrustManager {
   override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
   override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
   override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
  }), SecureRandom())

  var mismatchDetected = false
  val clientThread = Thread {
   try {
    val sock = clientCtx.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
    sock.startHandshake()
    // Verification against target.com must FAIL because cert CN is evil.com
    val verified = verifier.verify("target.com", sock.session)
    if (!verified) {
     mismatchDetected = true
    }
    sock.close()
   } catch (_: Exception) {}
  }

  clientThread.start()
  val s = serverSocket.accept()
  (s as SSLSocket).startHandshake()
  clientThread.join(3000)
  s.close()
  serverSocket.close()

  assertTrue("Hostname verifier must reject cert when requested host differs from cert subject", mismatchDetected)
 }

 @Test
 fun testInvalidUpstreamCertificateRejection() {
  // Verifies that standard system TrustManager rejects an untrusted self-signed certificate
  val kpg = KeyPairGenerator.getInstance("RSA")
  kpg.initialize(2048)
  val kp = kpg.generateKeyPair()

  val notBefore = Date(System.currentTimeMillis() - 10000)
  val notAfter = Date(System.currentTimeMillis() + 100000)
  val name = X500Name("CN=untrusted-self-signed.test")
  val builder = JcaX509v3CertificateBuilder(name, BigInteger.ONE, notBefore, notAfter, name, kp.public)
  val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(kp.private)
  val cert = JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer))

  val ks = KeyStore.getInstance(KeyStore.getDefaultType())
  ks.load(null, null)
  ks.setKeyEntry("key", kp.private, "pwd".toCharArray(), arrayOf(cert))
  val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
  kmf.init(ks, "pwd".toCharArray())
  val serverCtx = SSLContext.getInstance("TLS")
  serverCtx.init(kmf.keyManagers, null, SecureRandom())

  val serverSocket = serverCtx.serverSocketFactory.createServerSocket(0)
  val port = serverSocket.localPort

  // Default system trust SSLContext (never trust-all!)
  val standardCtx = SSLContext.getDefault()

  var rejected = false
  val clientThread = Thread {
   try {
    val sock = standardCtx.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
    sock.startHandshake()
    fail("Default trust manager must not accept untrusted self-signed cert")
   } catch (e: SSLHandshakeException) {
    rejected = true
   } catch (_: Exception) {
    rejected = true
   }
  }

  clientThread.start()
  try {
   val s = serverSocket.accept()
   (s as SSLSocket).startHandshake()
   s.close()
  } catch (_: Exception) {}

  clientThread.join(3000)
  serverSocket.close()

  assertTrue("Standard TLS validation must reject untrusted upstream certificates", rejected)
 }
}
