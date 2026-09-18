package com.nadeem.apkscope.core.staticanalysis

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import java.io.File
import java.util.zip.ZipFile

/**
 * Bundled versioned signature catalog for detecting commonly used third-party SDKs.
 *
 * Implements:
 * - Version 1.1.0 catalog covering advertising, attribution, networking, and analytics SDKs.
 * - Multi-evidence rules combining class descriptors, characteristic methods, and manifest corroboration.
 * - Transparent evidence ranking (Confirmed, Likely, Possible) with explicit evidence rationale.
 * - Strict separation of framework/runtime detection from SDK identification.
 */
object SdkSignatureCatalog {

    const val CATALOG_VERSION = "1.1.0"

    data class SdkDefinition(
        val sdkName: String,
        val category: String,
        val characteristicClasses: List<String>,
        val characteristicMethods: List<String> = emptyList(),
        val manifestCorroborationTags: List<String> = emptyList(),
        val packagePrefixes: List<String> = emptyList(),
        /** Broad namespaces that must not count as this SDK's package evidence. */
        val excludedPackagePrefixes: List<String> = emptyList(),
        val versionMetadataKey: String? = null
    )

    private val CATALOG = listOf(
        SdkDefinition(
            sdkName = "Google Play Services",
            category = "Platform / Services",
            characteristicClasses = listOf(
                "Lcom/google/android/gms/common/GoogleApiAvailability;",
                "Lcom/google/android/gms/common/api/GoogleApiClient;",
                "Lcom/google/android/gms/common/GooglePlayServicesUtil;"
            ),
            characteristicMethods = listOf("isGooglePlayServicesAvailable", "getInstance"),
            manifestCorroborationTags = listOf("com.google.android.gms.version"),
            packagePrefixes = listOf("com.google.android.gms.common"),
            versionMetadataKey = "com.google.android.gms.version"
        ),
        SdkDefinition(
            sdkName = "Firebase",
            category = "Analytics / Cloud Services",
            characteristicClasses = listOf(
                "Lcom/google/firebase/FirebaseApp;",
                "Lcom/google/firebase/analytics/FirebaseAnalytics;",
                "Lcom/google/firebase/crashlytics/FirebaseCrashlytics;",
                "Lcom/google/firebase/messaging/FirebaseMessaging;"
            ),
            characteristicMethods = listOf("initializeApp", "getInstance"),
            manifestCorroborationTags = listOf("com.google.firebase.provider.FirebaseInitProvider", "com.google.firebase.messaging"),
            packagePrefixes = listOf("com.google.firebase")
        ),
        SdkDefinition(
            sdkName = "OkHttp",
            category = "Networking / HTTP",
            characteristicClasses = listOf(
                "Lokhttp3/OkHttpClient;",
                "Lokhttp3/OkHttpClient\$Builder;",
                "Lokhttp3/Request;",
                "Lokhttp3/Response;"
            ),
            characteristicMethods = listOf("newCall", "execute", "enqueue"),
            packagePrefixes = listOf("okhttp3")
        ),
        SdkDefinition(
            sdkName = "Retrofit",
            category = "Networking / REST",
            characteristicClasses = listOf(
                "Lretrofit2/Retrofit;",
                "Lretrofit2/Retrofit\$Builder;",
                "Lretrofit2/Call;"
            ),
            characteristicMethods = listOf("create", "build"),
            packagePrefixes = listOf("retrofit2")
        ),
        SdkDefinition(
            sdkName = "Facebook SDK",
            category = "Social / Advertising",
            characteristicClasses = listOf(
                "Lcom/facebook/FacebookSdk;",
                "Lcom/facebook/appevents/AppEventsLogger;",
                "Lcom/facebook/login/LoginManager;"
            ),
            characteristicMethods = listOf("sdkInitialize", "activateApp"),
            manifestCorroborationTags = listOf("com.facebook.FacebookActivity", "com.facebook.sdk.ApplicationId"),
            packagePrefixes = listOf("com.facebook"),
            // React Native owns the com.facebook.react namespace but is not the Facebook SDK.
            excludedPackagePrefixes = listOf("com.facebook.react"),
            versionMetadataKey = "com.facebook.sdk.ApplicationId"
        ),
        SdkDefinition(
            sdkName = "Adjust",
            category = "Attribution / Analytics",
            characteristicClasses = listOf(
                "Lcom/adjust/sdk/Adjust;",
                "Lcom/adjust/sdk/AdjustConfig;",
                "Lcom/adjust/sdk/AdjustEvent;"
            ),
            characteristicMethods = listOf("onCreate", "trackEvent", "onResume"),
            manifestCorroborationTags = listOf("com.adjust.sdk.AdjustReferrerReceiver"),
            packagePrefixes = listOf("com.adjust.sdk")
        ),
        SdkDefinition(
            sdkName = "AppsFlyer",
            category = "Attribution / Analytics",
            characteristicClasses = listOf(
                "Lcom/appsflyer/AppsFlyerLib;",
                "Lcom/appsflyer/AppsFlyerConversionListener;"
            ),
            characteristicMethods = listOf("init", "start", "logEvent"),
            manifestCorroborationTags = listOf("com.appsflyer.SingleInstallBroadcastReceiver"),
            packagePrefixes = listOf("com.appsflyer")
        ),
        SdkDefinition(
            sdkName = "Unity Ads",
            category = "Advertising / Monetization",
            characteristicClasses = listOf(
                "Lcom/unity3d/services/ads/UnityAds;",
                "Lcom/unity3d/services/UnityServices;"
            ),
            characteristicMethods = listOf("initialize", "show", "load"),
            manifestCorroborationTags = listOf("com.unity3d.services.ads.adunit.AdUnitActivity"),
            packagePrefixes = listOf("com.unity3d.services.ads")
        ),
        SdkDefinition(
            sdkName = "IronSource",
            category = "Advertising / Mediation",
            characteristicClasses = listOf(
                "Lcom/ironsource/mediationsdk/IronSource;",
                "Lcom/ironsource/mediationsdk/integration/IntegrationHelper;"
            ),
            characteristicMethods = listOf("init", "validateIntegration"),
            manifestCorroborationTags = listOf("com.ironsource.adapters"),
            packagePrefixes = listOf("com.ironsource.mediationsdk")
        ),
        SdkDefinition(
            sdkName = "Bouncy Castle Crypto",
            category = "Cryptography / Security",
            characteristicClasses = listOf(
                "Lorg/bouncycastle/jce/provider/BouncyCastleProvider;",
                "Lorg/bouncycastle/crypto/Digest;",
                "Lorg/bouncycastle/asn1/ASN1Primitive;"
            ),
            characteristicMethods = listOf("getInstance", "getDigestSize"),
            packagePrefixes = listOf("org.bouncycastle")
        )
    )

    data class DetectionResult(
        val sdks: List<SdkFinding>,
        val coverage: StaticAnalysisCoverage
    )

    fun detectSdks(apkFile: File, manifestRawXml: String? = null): DetectionResult {
        val startTime = System.currentTimeMillis()
        val inspectedDexFiles = mutableListOf<String>()
        val skippedEntries = mutableListOf<String>()
        val parsingErrors = mutableListOf<String>()

        val allClasses = HashSet<String>()
        val allMethodNames = HashSet<String>()

        if (!apkFile.exists() || !apkFile.canRead()) {
            return DetectionResult(
                sdks = emptyList(),
                coverage = StaticAnalysisCoverage(
                    parsingErrors = listOf("APK file missing or unreadable: ${apkFile.absolutePath}"),
                    scanDurationMs = System.currentTimeMillis() - startTime
                )
            )
        }

        try {
            val opcodes = Opcodes.getDefault()
            val container = DexFileFactory.loadDexContainer(apkFile, opcodes)
            val entryNames = container.dexEntryNames.sorted()

            for (entryName in entryNames) {
                try {
                    val entry = container.getEntry(entryName) ?: continue
                    val dexFile = entry.dexFile
                    inspectedDexFiles.add(entryName)

                    if (dexFile is DexBackedDexFile) {
                        for (classDef in dexFile.classes) {
                            allClasses.add(classDef.type)
                            for (m in classDef.methods) {
                                allMethodNames.add(m.name)
                            }
                        }
                    }
                } catch (e: Exception) {
                    parsingErrors.add("$entryName: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            parsingErrors.add("DEX container load error: ${e.javaClass.simpleName}: ${e.message}")
        }

        val findings = mutableListOf<SdkFinding>()

        for (def in CATALOG) {
            val matchedClasses = def.characteristicClasses.filter { allClasses.contains(it) }
            val matchedMethods = def.characteristicMethods.filter { allMethodNames.contains(it) }
            val matchedManifest = if (!manifestRawXml.isNullOrBlank()) {
                def.manifestCorroborationTags.filter { manifestRawXml.contains(it) }
            } else emptyList()

            val packageMatches = matchesPackagePrefix(
                classDescriptors = allClasses,
                packagePrefixes = def.packagePrefixes,
                excludedPackagePrefixes = def.excludedPackagePrefixes,
            )

            if (matchedClasses.isEmpty() && matchedManifest.isEmpty() && !packageMatches) {
                continue // Negative match
            }

            val evidenceList = mutableListOf<String>()
            val matchedSigs = mutableListOf<String>()

            if (matchedClasses.isNotEmpty()) {
                matchedSigs.addAll(matchedClasses)
                evidenceList.add("Classes: ${matchedClasses.joinToString { it.removePrefix("L").removeSuffix(";") }}")
            }
            if (matchedMethods.isNotEmpty()) {
                evidenceList.add("Methods: ${matchedMethods.joinToString()}")
            }
            if (matchedManifest.isNotEmpty()) {
                matchedSigs.addAll(matchedManifest)
                evidenceList.add("Manifest: ${matchedManifest.joinToString()}")
            }
            if (packageMatches) {
                evidenceList.add("Namespace package matched: ${def.packagePrefixes.joinToString()}")
            }

            // Confidence classification
            val (confidence, rationale) = when {
                matchedClasses.size >= 2 && (matchedManifest.isNotEmpty() || matchedMethods.isNotEmpty()) -> {
                    SdkConfidence.HIGH to "Confirmed match: multiple characteristic classes (${matchedClasses.size}) corroborated by ${if (matchedManifest.isNotEmpty()) "manifest declarations" else "characteristic methods"}."
                }
                matchedClasses.isNotEmpty() || matchedManifest.isNotEmpty() -> {
                    SdkConfidence.MEDIUM to "Likely match: a characteristic signature or manifest declaration matched, but full corroboration is unavailable."
                }
                else -> {
                    SdkConfidence.LOW to "Possible match: only a package namespace was observed; this does not confirm SDK inclusion."
                }
            }

            findings.add(
                SdkFinding(
                    sdkName = def.sdkName,
                    category = def.category,
                    confidence = confidence,
                    matchedSignatures = matchedSigs,
                    evidence = evidenceList,
                    rationale = rationale,
                    catalogVersion = CATALOG_VERSION,
                    detectedVersion = null // We do not guess version without explicit version constant evidence
                )
            )
        }

        val coverage = StaticAnalysisCoverage(
            dexFilesInspected = inspectedDexFiles,
            entriesSkipped = skippedEntries,
            parsingErrors = parsingErrors,
            limitsReached = false,
            scanDurationMs = System.currentTimeMillis() - startTime
        )

        return DetectionResult(sdks = findings.sortedBy { it.sdkName }, coverage = coverage)
    }

    /**
     * Matches a package namespace at a descriptor boundary and ignores explicitly excluded
     * namespaces. This prevents broad prefixes such as `com.facebook` from claiming sibling
     * frameworks such as React Native (`com.facebook.react`).
     */
    internal fun matchesPackagePrefix(
        classDescriptors: Collection<String>,
        packagePrefixes: List<String>,
        excludedPackagePrefixes: List<String> = emptyList(),
    ): Boolean {
        val prefixes = packagePrefixes.map { "L${it.replace('.', '/')}/" }
        val excluded = excludedPackagePrefixes.map { "L${it.replace('.', '/')}/" }
        return classDescriptors.any { descriptor ->
            prefixes.any { descriptor.startsWith(it) } && excluded.none { descriptor.startsWith(it) }
        }
    }
}
