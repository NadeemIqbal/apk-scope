package com.apksandbox.fixture

import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import java.security.MessageDigest
import javax.crypto.Cipher

/**
 * Milestone 10 (Security Audit), Phase 10.3, MS10-CODE01 — real compiled bytecode fixtures for
 * [com.nadeem.apkscope.core.staticanalysis.CodePatternAnalyzer] and `DexApiScanner`'s CRYPTOGRAPHY/WEBVIEW
 * categories. None of these functions/classes are ever invoked or instantiated at runtime — see
 * [NetworkTrustFixtures]'s doc for why that is fine for a static-analysis fixture (the scanner walks
 * every method regardless of reachability).
 */
object CodePatternFixtures {

 /** Positive case for the weak-algorithm co-occurrence HEURISTIC: a real Cipher.getInstance call in the same method as the literal string "DES". */
 fun weakCipherReference(): Cipher = Cipher.getInstance("DES")

 /** Negative case: a real Cipher.getInstance call with no weak-algorithm marker anywhere in the method — must not trigger the heuristic. */
 fun modernCipherReference(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")

 /** Positive case: a real MessageDigest.getInstance call co-occurring with the literal string "MD5". */
 fun weakDigestReference(): MessageDigest = MessageDigest.getInstance("MD5")

 /** Negative case: a real MessageDigest.getInstance call with no weak-algorithm marker in the method. */
 fun modernDigestReference(): MessageDigest = MessageDigest.getInstance("SHA-256")

 /** Real WebView.addJavascriptInterface / setJavaScriptEnabled reference-tier fixtures for `DexApiScanner`'s WEBVIEW category — never invoked, no WebView instance is ever actually created anywhere in this app. */
 fun configureWebViewReferenceOnly(webView: WebView) {
  webView.settings.javaScriptEnabled = true // setJavaScriptEnabled under the hood
  webView.addJavascriptInterface(Any(), "fixtureBridge")
 }
}

/**
 * Positive case for the CONFIRMED WebView SSL-error-bypass check: a real class extending
 * android.webkit.WebViewClient whose onReceivedSslError unconditionally calls handler.proceed() —
 * the classic MITM-enabling pattern (CWE-295). Never instantiated or attached to a real WebView
 * anywhere in this app.
 */
class UnsafeSslErrorWebViewClient : WebViewClient() {
 override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
  handler?.proceed() // intentionally unsafe — fixture only, never invoked or attached to a real WebView
 }
}

/**
 * Negative case: a class extending WebViewClient whose onReceivedSslError correctly calls
 * handler.cancel() instead of proceed() — must not trigger the WEBVIEW_SSL_ERROR_BYPASS check.
 */
class SafeSslErrorWebViewClient : WebViewClient() {
 override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
  handler?.cancel()
 }
}

/**
 * Negative case: a class that does NOT extend WebViewClient at all, but happens to have a method
 * named onReceivedSslError that calls SslErrorHandler.proceed — must not trigger the CONFIRMED check,
 * since the class-hierarchy fact (extends WebViewClient) is false. Proves the check is a real
 * structural conjunction, not a name-only match.
 */
class NotAWebViewClientButSameMethodName {
 fun onReceivedSslError(handler: SslErrorHandler?) {
  handler?.proceed()
 }
}
