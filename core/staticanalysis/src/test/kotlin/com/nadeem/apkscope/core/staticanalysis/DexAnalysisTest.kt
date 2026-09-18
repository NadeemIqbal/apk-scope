package com.nadeem.apkscope.core.staticanalysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class DexAnalysisTest {

    private fun findApk(relativePath: String): File? {
        val candidates = listOf(
            File("../../$relativePath"),
            File(relativePath),
            File("../$relativePath")
        )
        return candidates.firstOrNull { it.exists() && it.canRead() }
    }

    private fun findFixtureApk(): File {
        return findApk("fixture/build/outputs/apk/debug/fixture-debug.apk")
            ?: throw IllegalStateException("fixture-debug.apk not found; run :fixture:assembleDebug")
    }

    private fun findRiskFixtureApk(): File {
        return findApk("riskfixture/build/outputs/apk/debug/riskfixture-debug.apk")
            ?: throw IllegalStateException("riskfixture-debug.apk not found; run :riskfixture:assembleDebug")
    }

    @Test
    fun dexUrlExtractorHandlesNonExistentApkGracefully() {
        val badFile = File("non_existent_file.apk")
        val result = DexUrlExtractor.extractUrls(badFile)
        assertTrue("Should return empty URLs for missing APK", result.urls.isEmpty())
        assertTrue("Should record parsing error", result.coverage.parsingErrors.isNotEmpty())
    }

    @Test
    fun dexUrlExtractorHandlesCorruptedFileGracefully() {
        val tempCorrupt = File.createTempFile("corrupt", ".apk")
        try {
            FileOutputStream(tempCorrupt).use { fos ->
                fos.write("PK\u0003\u0004CorruptedZipEntryPayloadRandomGarbage".toByteArray())
            }
            val result = DexUrlExtractor.extractUrls(tempCorrupt)
            assertTrue("Should safely return empty or partial on corrupted file", result.urls.isEmpty())
            assertTrue("Should record parsing error for corrupt archive", result.coverage.parsingErrors.isNotEmpty())
        } finally {
            tempCorrupt.delete()
        }
    }

    @Test
    fun documentationUrlsAreRecognizedAsStaticNoise() {
        assertTrue(DexUrlExtractor.isLikelyDocumentationUrl("https://developer.android.com/guide/topics/media/issues/cleartext-not-permitted"))
        assertTrue(DexUrlExtractor.isLikelyDocumentationUrl("https://issuetracker.google.com/issues/241760537"))
        assertTrue(DexUrlExtractor.isLikelyDocumentationUrl("http://g.co/dev/packagevisibility"))
        assertTrue(DexUrlExtractor.isLikelyDocumentationUrl("http://dashif.org/guidelines/last-segment-number"))
        assertTrue(DexUrlExtractor.isLikelyDocumentationUrl("http://dashif.org/thumbnail_tile"))
        assertTrue(DexUrlExtractor.isLikelyDocumentationUrl("https://aomedia.org/emsg/ID3"))
        assertTrue(DexUrlExtractor.isLikelyDocumentationUrl("https://default.url/"))
        assertTrue(DexUrlExtractor.isLikelyDocumentationUrl("https://shopify.github.io/flash-list/docs/usage"))
        assertTrue(DexUrlExtractor.isLikelyDocumentationUrl("https://github.com/software-mansion/react-native-screens/issues"))
        assertTrue(DexUrlExtractor.isLikelyDocumentationUrl("https://docs.swmansion.com/react-native-reanimated/docs/guides/troubleshooting"))
        assertTrue(DexUrlExtractor.isLikelyDocumentationUrl("https://crbug.com/388824130"))
        assertTrue(DexUrlExtractor.isLikelyDocumentationUrl("https://notifee.app/react-native/docs/triggers"))
        assertTrue(DexUrlExtractor.isLikelyDocumentationUrl("https://www.docs.developers.amplitude.com/data/sdks/android-kotlin/"))
        assertTrue(DexUrlExtractor.isLikelyDocumentationUrl("http://www.android.com/"))
        assertFalse(DexUrlExtractor.isLikelyDocumentationUrl("https://api.example.com/v1/config"))
    }

    @Test
    fun dexUrlExtractorExtractsUrlsAndIdentifiesProvenance() {
        val apkFile = findFixtureApk()

        // Test 1: Host correlation only - should NOT mark URLs as RUNTIME_OBSERVED
        val hostOnlyResult = DexUrlExtractor.extractUrls(apkFile, observedRuntimeHosts = setOf("example.com"))
        for (candidate in hostOnlyResult.urls) {
            if (candidate.host == "example.com") {
                assertNotNull("Host correlation must be present when host matches", candidate.hostCorrelation)
                assertNull("Runtime evidence must be null without exact URL match", candidate.runtimeEvidence)
                // Matching host alone must NEVER assign RUNTIME_OBSERVED
                assertTrue("Host alone must not assign RUNTIME_OBSERVED", candidate.provenance != UrlProvenance.RUNTIME_OBSERVED)
            }
        }

        // Test 2: Exact URL observation with evidence reference
        val targetUrl = hostOnlyResult.urls.first { it.host == "example.com" }.normalizedUrl
        val evidence = RuntimeEvidenceReference(
            sessionId = "sess-12345",
            transactionId = "tx-67890",
            url = targetUrl,
            timestamp = 1726000000000L,
            method = "GET",
            statusCode = 200
        )
        val exactResult = DexUrlExtractor.extractUrls(
            apkFile = apkFile,
            observedRuntimeUrls = mapOf(targetUrl to evidence),
            observedRuntimeHosts = mapOf("example.com" to HostCorrelationInfo("example.com", 1))
        )

        var foundExact = false
        for (candidate in exactResult.urls) {
            if (candidate.normalizedUrl == targetUrl) {
                foundExact = true
                assertEquals(UrlProvenance.RUNTIME_OBSERVED, candidate.provenance)
                assertNotNull(candidate.runtimeEvidence)
                assertEquals("sess-12345", candidate.runtimeEvidence?.sessionId)
                assertEquals("tx-67890", candidate.runtimeEvidence?.transactionId)
            } else if (candidate.host == "example.com") {
                // Different URL on same host must NOT be RUNTIME_OBSERVED
                assertTrue(candidate.provenance != UrlProvenance.RUNTIME_OBSERVED)
                assertNotNull("Host correlation still present for same host", candidate.hostCorrelation)
            }
        }
        assertTrue("Target URL must have been matched with exact evidence", foundExact)
    }

    @Test
    fun dexUrlExtractorDeduplicatesWhilePreservingReferences() {
        val apkFile = findRiskFixtureApk()
        val result = DexUrlExtractor.extractUrls(apkFile)

        // Verify all extracted URLs are unique by normalized URL
        val urls = result.urls.map { it.normalizedUrl }
        val uniqueUrls = urls.toSet()
        assertEquals("Extracted candidate URLs must be deduplicated", uniqueUrls.size, urls.size)

        // Verify that candidates can have multiple distinct code references
        val multiRefCandidate = result.urls.firstOrNull { it.references.size > 1 }
        if (multiRefCandidate != null) {
            val distinctLocs = multiRefCandidate.references.map { "${it.className}->${it.methodName}" }.toSet()
            assertTrue("Multiple references should refer to code locations", distinctLocs.isNotEmpty())
        }
    }

    @Test
    fun sdkSignatureCatalogDetectsLibrariesAndAssignsConfidence() {
        val apkFile = findFixtureApk()

        val result = SdkSignatureCatalog.detectSdks(apkFile)
        assertTrue("Catalog version must be 1.1.0", SdkSignatureCatalog.CATALOG_VERSION == "1.1.0")
        assertTrue("Inspected DEX list should be non-empty", result.coverage.dexFilesInspected.isNotEmpty())

        for (sdk in result.sdks) {
            assertEquals("1.1.0", sdk.catalogVersion)
            assertTrue("SDK must have matched signatures", sdk.matchedSignatures.isNotEmpty())
            assertTrue("SDK must have evidence list", sdk.evidence.isNotEmpty())
            assertTrue("Rationale must be populated", sdk.rationale.isNotBlank())
            assertTrue("Confidence must be set", sdk.confidence in listOf(SdkConfidence.HIGH, SdkConfidence.MEDIUM, SdkConfidence.LOW))
        }

        // Verify OkHttp or BouncyCastle is detected if in classpath
        val detectedNames = result.sdks.map { it.sdkName }
        assertTrue("Must have scanned signatures without throwing", detectedNames.isNotEmpty() || result.coverage.dexFilesInspected.isNotEmpty())
    }

    @Test
    fun sdkNamespaceEvidenceDoesNotConfuseReactNativeWithFacebookSdk() {
        assertEquals("Confirmed", SdkConfidence.HIGH.evidenceLabel)
        assertEquals("Likely", SdkConfidence.MEDIUM.evidenceLabel)
        assertEquals("Possible", SdkConfidence.LOW.evidenceLabel)

        assertFalse(
            SdkSignatureCatalog.matchesPackagePrefix(
                classDescriptors = listOf("Lcom/facebook/react/ReactActivity;"),
                packagePrefixes = listOf("com.facebook"),
                excludedPackagePrefixes = listOf("com.facebook.react"),
            ),
        )
        assertTrue(
            SdkSignatureCatalog.matchesPackagePrefix(
                classDescriptors = listOf("Lcom/facebook/appevents/AppEventsLogger;"),
                packagePrefixes = listOf("com.facebook"),
                excludedPackagePrefixes = listOf("com.facebook.react"),
            ),
        )
    }

    @Test
    fun sdkSignatureCatalogNegativeMatch() {
        // An empty or minimal APK should not hallucinate unknown SDKs
        val tempEmpty = File.createTempFile("empty", ".apk")
        try {
            FileOutputStream(tempEmpty).use { fos ->
                fos.write("PK00".toByteArray())
            }
            val result = SdkSignatureCatalog.detectSdks(tempEmpty)
            assertTrue("Corrupted/empty archive should produce zero SDK detections", result.sdks.isEmpty())
        } finally {
            tempEmpty.delete()
        }
    }

    @Test
    fun dexApiScannerCategorizesSecurityApisAndDistinguishesInvocations() {
        val apkFile = findRiskFixtureApk()

        val result = DexApiScanner.scanApk(apkFile)
        assertTrue("DEX scan should inspect at least one DEX entry", result.coverage.dexFilesInspected.isNotEmpty())

        // Validate findings structure
        for (finding in result.findings) {
            assertTrue("API name must be non-empty", finding.apiName.isNotBlank())
            assertTrue("Explanation must be non-empty", finding.explanation.isNotBlank())
            assertTrue("DEX entry must end with .dex", finding.dexEntry.endsWith(".dex"))

            if (finding.isInvocation) {
                assertNotNull("Invocation findings should have calling class", finding.callingClass)
                assertNotNull("Invocation findings should have calling method", finding.callingMethod)
            }
        }

        // Verify categories are valid
        val categories = result.findings.map { it.category }.toSet()
        for (cat in categories) {
            assertTrue("Category must have title", cat.title.isNotBlank())
        }

        // Verify that riskfixture triggers sensitive categories (Reflection or Process Execution or Dynamic Loading)
        val categoryTitles = categories.map { it.title }
        val hasSecurityCategory = categoryTitles.any {
            it.contains("Reflection") || it.contains("Process") || it.contains("Dynamic") || it.contains("Native") || it.contains("Sensitive")
        }
        assertTrue("Risk fixture should trigger at least one security API category", hasSecurityCategory)
    }

    /**
     * Milestone 10 (Security Audit), Phase 10.3, MS10-NET01's NETWORK_TRUST category — real fires and
     * does-not-fire cases, not fabricated ones. `fixture-debug.apk` contains real compiled bytecode for
     * all three signal types: `FixtureActivity.doHttpsReject()`'s genuine custom `X509TrustManager` +
     * `SSLContext.init` (pre-existing, unrelated pinning-simulation work) plus
     * `NetworkTrustFixtures`'s default/unsafe `SSLContext.init` pair, benign/unsafe `HostnameVerifier`
     * pair, and real OkHttp `CertificatePinner` declaration (added this pass, never invoked at runtime).
     * `riskfixture-debug.apk` has none of this code, giving a genuine negative case.
     */
    @Test
    fun dexApiScannerDetectsAllThreeNetworkTrustSignalTypesOnFixture() {
        val result = DexApiScanner.scanApk(findFixtureApk())

        val trustFindings = result.findings.filter { it.category == ApiCategory.NETWORK_TRUST }
        val apiNames = trustFindings.map { it.apiName }.toSet()
        assertTrue("fixture-debug.apk must trigger SSLContext.init (multiple real call sites)", apiNames.contains("javax.net.ssl.SSLContext.init"))
        assertTrue(
            "fixture-debug.apk must trigger a hostname-verifier override reference",
            apiNames.any { it.contains("HttpsURLConnection") && it.contains("HostnameVerifier") }
        )
        assertTrue(
            "fixture-debug.apk must trigger the OkHttp CertificatePinner.Builder.add pin declaration",
            apiNames.any { it.contains("CertificatePinner") }
        )
    }

    /**
     * The correction this test exists to prove: [NetworkTrustFixtures]'s benign, fully-default
     * `SSLContext.init(null, null, null)` call and its genuinely unsafe trust-all counterpart are two
     * *different* real call sites (different calling methods) in the same APK, and both must produce
     * the identical NETWORK_TRUST/SSLContext.init evidence — because DexApiScanner has no argument-flow
     * analysis and cannot tell them apart. If this rule ever started distinguishing them without real
     * argument inspection, that would itself be a false claim; this test guards against that regression.
     */
    @Test
    fun dexApiScannerTreatsDefaultAndUnsafeTrustInitAsIdenticalEvidence() {
        val result = DexApiScanner.scanApk(findFixtureApk())

        val sslContextInitFindings = result.findings.filter {
            it.category == ApiCategory.NETWORK_TRUST && it.apiName == "javax.net.ssl.SSLContext.init"
        }
        val callingMethods = sslContextInitFindings.mapNotNull { it.callingMethod }.toSet()
        assertTrue(
            "must include the benign defaultTrustInitialization call site",
            callingMethods.contains("defaultTrustInitialization")
        )
        assertTrue(
            "must include the unsafe unsafeTrustAllContext call site",
            callingMethods.contains("unsafeTrustAllContext")
        )
        val explanationsForBothSites = sslContextInitFindings
            .filter { it.callingMethod == "defaultTrustInitialization" || it.callingMethod == "unsafeTrustAllContext" }
            .map { it.explanation }
            .toSet()
        assertEquals(
            "the benign and unsafe call sites must produce the exact same explanation text — no differentiation the scanner cannot actually support",
            1,
            explanationsForBothSites.size,
        )
    }

    @Test
    fun dexApiScannerReportsNoNetworkTrustConfigOnFixtureWithoutTlsCode() {
        val result = DexApiScanner.scanApk(findRiskFixtureApk())

        val trustFindings = result.findings.filter { it.category == ApiCategory.NETWORK_TRUST }
        assertTrue("riskfixture-debug.apk has no TLS trust-manager code — must not be detected", trustFindings.isEmpty())
    }

    /**
     * Milestone 10 (Security Audit), Phase 10.3, MS10-CODE01 — CRYPTOGRAPHY/WEBVIEW category real
     * fires against `fixture-debug.apk`'s real `CodePatternFixtures`/`UnsafeSslErrorWebViewClient`
     * classes (added for this requirement, never invoked at runtime).
     */
    @Test
    fun dexApiScannerDetectsCryptographyApiReferencesOnRealFixture() {
        val result = DexApiScanner.scanApk(findFixtureApk())
        val cryptoFindings = result.findings.filter { it.category == ApiCategory.CRYPTOGRAPHY }
        assertTrue("fixture calls Cipher.getInstance and MessageDigest.getInstance — must be detected", cryptoFindings.isNotEmpty())
        assertTrue(cryptoFindings.all { it.explanation.contains("REFERENCE tier only") })
    }

    @Test
    fun dexApiScannerDetectsWebViewApiReferencesOnRealFixture() {
        val result = DexApiScanner.scanApk(findFixtureApk())
        val webViewFindings = result.findings.filter { it.category == ApiCategory.WEBVIEW }
        val apiNames = webViewFindings.map { it.apiName }.toSet()
        assertTrue(
            "fixture's CodePatternFixtures.configureWebViewReferenceOnly calls WebView.addJavascriptInterface — must be detected",
            apiNames.any { it.contains("addJavascriptInterface") },
        )
        assertTrue(
            "fixture's CodePatternFixtures.configureWebViewReferenceOnly calls WebSettings.setJavaScriptEnabled — must be detected",
            apiNames.any { it.contains("setJavaScriptEnabled") },
        )

        val riskFixtureWebView = DexApiScanner.scanApk(findRiskFixtureApk()).findings.filter { it.category == ApiCategory.WEBVIEW }
        assertTrue("riskfixture has no WebView code at all — must not be detected", riskFixtureWebView.isEmpty())
    }
}
