package com.nadeem.apkscope.core.network.https

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.cert.X509Certificate

class CaManagerTest {

 @get:Rule
 val tempFolder = TemporaryFolder()

 @Test
 fun testCaGenerationAndBasicConstraints() {
  val caDir = tempFolder.newFolder("poc_ca")
  val caManager = CaManager(caDir)

  val caCert = caManager.getCaCertificate()
  assertNotNull("CA certificate should be generated", caCert)
  assertTrue("CA certificate must have isCA=true", caCert!!.basicConstraints != -1)
  assertTrue("Subject must contain APK Scope", caCert.subjectX500Principal.name.contains("APK Scope"))

  val keyFile = File(caDir, "poc_ca.key")
  val certFile = File(caDir, "poc_ca.crt")
  assertTrue("Private key file must exist in protected storage", keyFile.exists())
  assertTrue("CA cert file must exist in protected storage", certFile.exists())
 }

 @Test
 fun testLeafCertificateGenerationForHostname() {
  val caDir = tempFolder.newFolder("poc_ca_leaf")
  val caManager = CaManager(caDir)

  val sslContext = caManager.getOrCreateServerSslContext("httpbin.org")
  assertNotNull("SSLContext must be created for hostname", sslContext)

  // Verify caching: second call returns same instance
  val cached = caManager.getOrCreateServerSslContext("httpbin.org")
  assertTrue("SSLContext for same host should be cached", sslContext === cached)

  // Verify different host gets another context
  val otherContext = caManager.getOrCreateServerSslContext("example.com")
  assertNotNull("SSLContext for second host should be created", otherContext)
 }

 @Test
 fun testResetRemovesKeysAndCertificates() {
  val caDir = tempFolder.newFolder("poc_ca_reset")
  val caManager = CaManager(caDir)
  assertNotNull(caManager.getCaCertificate())

  caManager.reset()
  assertNull("CA cert must be null after reset", caManager.getCaCertificate())
  assertFalse("Private key file must be deleted", File(caDir, "poc_ca.key").exists())
  assertFalse("CA cert file must be deleted", File(caDir, "poc_ca.crt").exists())
 }
}
