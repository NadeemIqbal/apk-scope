package com.apksandbox.fixture

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/**
 * Milestone 10 (Security Audit), Phase 10.3, MS10-NET01 — real compiled bytecode fixtures for the
 * network-trust static-analysis rules in `core:staticanalysis`'s `DexApiScanner`. None of these
 * functions are ever invoked at runtime (no call site anywhere in this app's lifecycle/UI) — DexApiScanner
 * scans every method's bytecode regardless of reachability, so a fixture only needs to exist in the
 * compiled dex, not actually execute, to be real evidence for a static reference-detection rule. This
 * keeps every intentionally-unsafe example confined to this fixture app and never running.
 *
 * `FixtureActivity.doHttpsReject()` already provides a real custom-TrustManager-with-non-trivial-
 * validation example (invoked, not dead code, from unrelated milestone 8/9 pinning-simulation work) —
 * not duplicated here. This file adds the patterns that were still missing real fixture coverage: a
 * fully-default (benign) TLS init, a genuinely unsafe trust-all bypass, a benign and an unsafe
 * HostnameVerifier, and a real OkHttp CertificatePinner declaration.
 */
object NetworkTrustFixtures {

 /**
  * Benign case for [com.nadeem.apkscope.core.staticanalysis.ApiCategory.NETWORK_TRUST]'s `SSLContext.init`
  * rule: the standard, fully-default pattern shown in Android/OkHttp's own documentation for obtaining
  * a default `SSLSocketFactory` — no custom TrustManager, no KeyManager, no SecureRandom override.
  * Produces the identical reference/invocation-level evidence as [unsafeTrustAllContext] below; the
  * static scanner cannot and does not distinguish them (see `DexApiScanner`'s rule comment).
  */
 fun defaultTrustInitialization(): SSLContext {
  val context = SSLContext.getInstance("TLS")
  context.init(null, null, null)
  return context
 }

 /**
  * Genuinely unsafe case, confined to this fixture and never invoked: a classic trust-all
  * `X509TrustManager` (empty validation bodies accept any certificate chain) installed via
  * `SSLContext.init`. Included so this project's own test suite can prove the static NETWORK_TRUST
  * rule fires identically for this and for [defaultTrustInitialization] — the whole point of the
  * "reference only, not a verdict" correction: this scanner has no way to tell them apart from the
  * bytecode reference alone.
  */
 fun unsafeTrustAllContext(): SSLContext {
  val trustAll = object : X509TrustManager {
   override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { /* intentionally accepts everything — fixture only, never invoked */ }
   override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) { /* intentionally accepts everything — fixture only, never invoked */ }
   override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
  }
  val context = SSLContext.getInstance("TLS")
  context.init(null, arrayOf(trustAll), SecureRandom())
  return context
 }

 /** Benign hostname verifier: delegates to the platform default rather than overriding validation. */
 fun benignHostnameVerifier(): HostnameVerifier = HostnameVerifier { hostname, session: SSLSession ->
  HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
 }

 /**
  * Genuinely unsafe hostname verifier, confined to this fixture and never invoked: unconditionally
  * accepts every hostname, the classic MITM-vulnerable pattern. Installed via
  * `HttpsURLConnection.setDefaultHostnameVerifier`, the exact call site the static rule detects.
  */
 fun installUnsafeHostnameVerifier() {
  HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
 }

 /**
  * Real OkHttp certificate-pinning declaration (`CertificatePinner`), using an obviously-fake SHA-256
  * pin value (`AAAA...=`, clearly not a real certificate digest) — a syntactically valid pin
  * declaration is all this fixture needs to be, since it is never used to make a real connection.
  */
 fun certificatePinnerConfiguration(): OkHttpClient {
  val pinner = CertificatePinner.Builder()
   .add("fixture.invalid", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
   .build()
  return OkHttpClient.Builder().certificatePinner(pinner).build()
 }
}
