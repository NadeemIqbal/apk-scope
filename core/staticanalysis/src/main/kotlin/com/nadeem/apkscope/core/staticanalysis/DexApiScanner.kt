package com.nadeem.apkscope.core.staticanalysis

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File

/**
 * Static scanner inspecting DEX method references and invocation instructions across 10 security categories.
 *
 * Implements:
 * - Direct inspection of instruction bytecodes (invoke-*) to capture exact calling classes and methods.
 * - Inspection of method reference tables for declared external dependencies.
 * - Categorization across 10 key security-sensitive surfaces.
 * - Explainable, neutral annotations distinguishing code references from executed behavior.
 * - Memory bounds and performance timeouts.
 */
object DexApiScanner {

    private const val MAX_FINDINGS_PER_CATEGORY = 200
    private const val MAX_TOTAL_FINDINGS = 1000
    private const val MAX_SCAN_TIME_MS = 30000L

    data class ApiRule(
        val category: ApiCategory,
        val targetClassDescriptor: String,
        val targetMethodNames: Set<String> = emptySet(),
        val explanation: String
    )

    private val RULES = listOf(
        // 1. Dynamic Code Loading
        ApiRule(
            category = ApiCategory.DYNAMIC_CODE_LOADING,
            targetClassDescriptor = "Ldalvik/system/DexClassLoader;",
            explanation = "Instantiates DexClassLoader to load classes dynamically from external JAR or APK files."
        ),
        ApiRule(
            category = ApiCategory.DYNAMIC_CODE_LOADING,
            targetClassDescriptor = "Ldalvik/system/PathClassLoader;",
            explanation = "Uses PathClassLoader to load classes or DEX archives from runtime filesystem paths."
        ),
        ApiRule(
            category = ApiCategory.DYNAMIC_CODE_LOADING,
            targetClassDescriptor = "Ldalvik/system/InMemoryDexClassLoader;",
            explanation = "Loads DEX bytecode directly from memory buffers without touching persistent storage."
        ),
        ApiRule(
            category = ApiCategory.DYNAMIC_CODE_LOADING,
            targetClassDescriptor = "Ljava/lang/ClassLoader;",
            targetMethodNames = setOf("loadClass", "defineClass"),
            explanation = "Invokes ClassLoader resolution methods to locate or define class implementations dynamically."
        ),

        // 2. Reflection
        ApiRule(
            category = ApiCategory.REFLECTION,
            targetClassDescriptor = "Ljava/lang/Class;",
            targetMethodNames = setOf("forName", "getMethod", "getDeclaredMethod", "getField", "getDeclaredField", "newInstance"),
            explanation = "Uses Java reflection to resolve classes, methods, or fields dynamically by name."
        ),
        ApiRule(
            category = ApiCategory.REFLECTION,
            targetClassDescriptor = "Ljava/lang/reflect/Method;",
            targetMethodNames = setOf("invoke"),
            explanation = "Dynamically invokes methods via Java Reflection."
        ),
        ApiRule(
            category = ApiCategory.REFLECTION,
            targetClassDescriptor = "Ljava/lang/reflect/Field;",
            targetMethodNames = setOf("get", "set", "setAccessible"),
            explanation = "Inspects or modifies object fields via reflection, potentially altering private members."
        ),

        // 3. Process Execution
        ApiRule(
            category = ApiCategory.PROCESS_EXECUTION,
            targetClassDescriptor = "Ljava/lang/Runtime;",
            targetMethodNames = setOf("exec"),
            explanation = "Spawns native operating system shell commands or external processes."
        ),
        ApiRule(
            category = ApiCategory.PROCESS_EXECUTION,
            targetClassDescriptor = "Ljava/lang/ProcessBuilder;",
            targetMethodNames = setOf("start"),
            explanation = "Constructs and executes child operating system processes."
        ),
        ApiRule(
            category = ApiCategory.PROCESS_EXECUTION,
            targetClassDescriptor = "Landroid/os/Process;",
            targetMethodNames = setOf("killProcess", "sendSignal"),
            explanation = "Interacts directly with Android Linux process management and signals."
        ),

        // 4. Native Library Loading
        ApiRule(
            category = ApiCategory.NATIVE_LIBRARY_LOADING,
            targetClassDescriptor = "Ljava/lang/System;",
            targetMethodNames = setOf("load", "loadLibrary"),
            explanation = "Loads compiled native shared libraries (.so ELF files) into the process memory space."
        ),
        ApiRule(
            category = ApiCategory.NATIVE_LIBRARY_LOADING,
            targetClassDescriptor = "Ljava/lang/Runtime;",
            targetMethodNames = setOf("load", "loadLibrary"),
            explanation = "Loads compiled native code via the Java Runtime interface."
        ),

        // 5. Accessibility APIs
        ApiRule(
            category = ApiCategory.ACCESSIBILITY,
            targetClassDescriptor = "Landroid/accessibilityservice/AccessibilityService;",
            explanation = "Extends or interacts with Android Accessibility Services which have broad UI observation capabilities."
        ),
        ApiRule(
            category = ApiCategory.ACCESSIBILITY,
            targetClassDescriptor = "Landroid/view/accessibility/AccessibilityNodeInfo;",
            targetMethodNames = setOf("performAction", "getText", "getContentDescription", "findAccessibilityNodeInfosByViewId"),
            explanation = "Queries or interacts with screen UI elements and text content through accessibility node interfaces."
        ),

        // 6. Device Administration
        ApiRule(
            category = ApiCategory.DEVICE_ADMINISTRATION,
            targetClassDescriptor = "Landroid/app/admin/DevicePolicyManager;",
            targetMethodNames = setOf("lockNow", "wipeData", "resetPassword", "installCaCert", "setCameraDisabled"),
            explanation = "Calls enterprise DevicePolicyManager administration APIs that control device locks, certificates, or storage."
        ),
        ApiRule(
            category = ApiCategory.DEVICE_ADMINISTRATION,
            targetClassDescriptor = "Landroid/app/admin/DeviceAdminReceiver;",
            explanation = "Implements device administration receiver for enterprise policy events."
        ),

        // 7. Sensitive Data Access
        ApiRule(
            category = ApiCategory.SENSITIVE_DATA_ACCESS,
            targetClassDescriptor = "Landroid/telephony/TelephonyManager;",
            targetMethodNames = setOf("getDeviceId", "getImei", "getSubscriberId", "getSimSerialNumber", "getLine1Number"),
            explanation = "Queries hardware device identifiers, SIM serials, or subscriber telephone metadata."
        ),
        ApiRule(
            category = ApiCategory.SENSITIVE_DATA_ACCESS,
            targetClassDescriptor = "Landroid/location/LocationManager;",
            targetMethodNames = setOf("getLastKnownLocation", "requestLocationUpdates"),
            explanation = "Accesses GPS or network provider location coordinates."
        ),
        ApiRule(
            category = ApiCategory.SENSITIVE_DATA_ACCESS,
            targetClassDescriptor = "Landroid/media/MediaRecorder;",
            targetMethodNames = setOf("setAudioSource", "start"),
            explanation = "Configures or initiates microphone audio capture."
        ),
        ApiRule(
            category = ApiCategory.SENSITIVE_DATA_ACCESS,
            targetClassDescriptor = "Landroid/content/ContentResolver;",
            targetMethodNames = setOf("query", "insert", "delete"),
            explanation = "Queries Android ContentProviders, which may include contacts, SMS, media, or calendar stores."
        ),

        // 8. Network Trust Configuration (Milestone 10, Phase 10.3, MS10-NET01) — corrected 2026-09-14
        // after review: the original wording claimed this rule detects installation of a "custom TLS
        // trust decision", which overstates what DexApiScanner can actually see. This scanner matches
        // method *references* (a symbol table entry) and *invocation instructions* (an invoke-* opcode
        // naming that method) — see [ApiFinding.isInvocation] and matchAndRecord() below. It performs
        // no argument-flow analysis: it cannot tell whether SSLContext.init was called with
        // `(null, null, null)` (the standard, fully-default TLS setup pattern shown in Android's own
        // SSLContext documentation) or with a real custom TrustManager array. A plain HTTPS client that
        // never touches trust configuration at all commonly still calls SSLContext.init once with all
        // three arguments null, purely to obtain a default SocketFactory — that call site produces the
        // identical reference/invocation evidence as a genuine custom-trust-manager installation. This
        // rule therefore establishes only "this APK's bytecode contains a reference to
        // SSLContext.init" — nothing about which arguments were supplied, whether a TrustManager was
        // installed, whether it is permissive or strict, or whether certificate pinning is present.
        // Not wired into `core:risk`'s scoring or any Security Audit rule today — kept purely
        // informational until argument-flow analysis (out of this scanner's current scope) can support
        // a stronger claim. See DexAnalysisTest's `dexApiScannerReportsDefaultTrustInitAsSameNeutralReference`
        // for the benign-default-init fixture proving this does not become an "insecure trust" finding.
        ApiRule(
            category = ApiCategory.NETWORK_TRUST,
            targetClassDescriptor = "Ljavax/net/ssl/SSLContext;",
            targetMethodNames = setOf("init"),
            explanation = "TLS context initialization reference (SSLContext.init) found — a symbol-table/invocation match only, not an inspection of the arguments passed. This is present for both fully-default TLS setup (all-null arguments) and for a custom TrustManager/KeyManager installation; it does not indicate that a custom trust manager was actually supplied, that certificate validation is weakened, or that pinning exists or is absent. Informational reference only, not a finding of risk."
        ),
        // Same evidence-level caveat as SSLContext.init above: a reference/invocation match only. A
        // benign HostnameVerifier that delegates to the platform default and a verifier that
        // unconditionally returns true produce the identical call-site evidence — see
        // `NetworkTrustFixtures.benignHostnameVerifier`/`installUnsafeHostnameVerifier` in the `fixture`
        // module, both real compiled bytecode, neither ever invoked at runtime.
        ApiRule(
            category = ApiCategory.NETWORK_TRUST,
            targetClassDescriptor = "Ljavax/net/ssl/HttpsURLConnection;",
            targetMethodNames = setOf("setDefaultHostnameVerifier", "setHostnameVerifier"),
            explanation = "Hostname-verifier override reference (HttpsURLConnection.setDefaultHostnameVerifier / setHostnameVerifier) found — a symbol-table/invocation match only, not an inspection of the verifier's own implementation. Present for both a verifier that correctly delegates to the platform default and one that unconditionally accepts every hostname; it does not indicate which."
        ),
        // Certificate-pin *declaration* signal (OkHttp's CertificatePinner, the most common pinning API
        // on Android) — this one is closer to a genuine configuration signal than the two above (calling
        // CertificatePinner.Builder.add always means a pin was declared, unlike SSLContext.init/hostname-
        // verifier overrides which are also used for entirely unrelated, non-trust-affecting purposes),
        // but still says nothing about which host(s), whether the pinned digest matches the app's real
        // certificate, or the pin set's expiration — see `NetworkTrustFixtures.certificatePinnerConfiguration`.
        ApiRule(
            category = ApiCategory.NETWORK_TRUST,
            targetClassDescriptor = "Lokhttp3/CertificatePinner\$Builder;",
            targetMethodNames = setOf("add"),
            explanation = "OkHttp certificate-pin declaration (CertificatePinner.Builder.add) found — confirms a pin was declared in code, but not for which host(s), whether the pinned digest is current/correct, or the pin set's expiration."
        ),

        // Milestone 10, Phase 10.3, MS10-CODE01 — cryptographic API references. REFERENCE tier only
        // (see CodePatternScanner's own doc for the required reference/reachable/executed distinction):
        // a symbol-table/invocation match on getInstance says nothing about which algorithm string was
        // passed, whether the surrounding method ever runs, or whether it ever actually executes.
        ApiRule(
            category = ApiCategory.CRYPTOGRAPHY,
            targetClassDescriptor = "Ljavax/crypto/Cipher;",
            targetMethodNames = setOf("getInstance"),
            explanation = "REFERENCE tier only: Cipher.getInstance reference found. This is a symbol-table/invocation match, not an inspection of the algorithm-name argument — it does not establish which cipher/mode/padding was requested, whether this code path is ever reached, or whether it ever executes."
        ),
        ApiRule(
            category = ApiCategory.CRYPTOGRAPHY,
            targetClassDescriptor = "Ljava/security/MessageDigest;",
            targetMethodNames = setOf("getInstance"),
            explanation = "REFERENCE tier only: MessageDigest.getInstance reference found. This is a symbol-table/invocation match, not an inspection of the algorithm-name argument — it does not establish which digest algorithm was requested, whether this code path is ever reached, or whether it ever executes."
        ),

        // Milestone 10, Phase 10.3, MS10-CODE01 — WebView configuration references. REFERENCE tier
        // only, same caveat as the cryptography rules above.
        ApiRule(
            category = ApiCategory.WEBVIEW,
            targetClassDescriptor = "Landroid/webkit/WebView;",
            targetMethodNames = setOf("addJavascriptInterface"),
            explanation = "REFERENCE tier only: WebView.addJavascriptInterface reference found — establishes a JavaScript-to-Java bridge object of a kind long documented as a code-execution risk on API levels below 17 (unrestricted reflection into the injected object from page JavaScript) and a real attack surface at any API level if the WebView ever loads untrusted content. This reference alone does not establish which object was exposed, what methods it grants access to, whether the WebView ever loads untrusted content, or whether this code path executes."
        ),
        ApiRule(
            // WebSettings, not WebView — verified against the real android.jar (android-35) stubs
            // before writing this rule: WebView itself has no setJavaScriptEnabled method; it is
            // declared on WebSettings (obtained via WebView.getSettings()).
            category = ApiCategory.WEBVIEW,
            targetClassDescriptor = "Landroid/webkit/WebSettings;",
            targetMethodNames = setOf("setJavaScriptEnabled"),
            explanation = "REFERENCE tier only: WebSettings.setJavaScriptEnabled reference found. This is a symbol-table/invocation match on the setter method itself — it does not establish whether it was called with true or false, since that requires tracing the boolean argument's value, not implemented here. Present identically whether JavaScript was enabled or explicitly disabled."
        )
    )

    data class ScanResult(
        val findings: List<ApiFinding>,
        val coverage: StaticAnalysisCoverage
    )

    fun scanApk(apkFile: File): ScanResult {
        val startTime = System.currentTimeMillis()
        val inspectedDexFiles = mutableListOf<String>()
        val skippedEntries = mutableListOf<String>()
        val parsingErrors = mutableListOf<String>()
        var limitsReached = false

        val findings = mutableListOf<ApiFinding>()
        val seenKeys = HashSet<String>()

        if (!apkFile.exists() || !apkFile.canRead()) {
            return ScanResult(
                findings = emptyList(),
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
                if (System.currentTimeMillis() - startTime > MAX_SCAN_TIME_MS) {
                    skippedEntries.add("$entryName (scan timeout exceeded)")
                    limitsReached = true
                    break
                }

                try {
                    val entry = container.getEntry(entryName) ?: continue
                    val dexFile = entry.dexFile
                    inspectedDexFiles.add(entryName)

                    if (dexFile is DexBackedDexFile) {
                        scanDexFile(dexFile, entryName, findings, seenKeys)
                    }

                    if (findings.size >= MAX_TOTAL_FINDINGS) {
                        limitsReached = true
                        break
                    }
                } catch (e: Exception) {
                    parsingErrors.add("$entryName: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            parsingErrors.add("DEX container load error: ${e.javaClass.simpleName}: ${e.message}")
        }

        val coverage = StaticAnalysisCoverage(
            dexFilesInspected = inspectedDexFiles,
            entriesSkipped = skippedEntries,
            parsingErrors = parsingErrors,
            limitsReached = limitsReached,
            scanDurationMs = System.currentTimeMillis() - startTime
        )

        return ScanResult(findings = findings, coverage = coverage)
    }

    private fun scanDexFile(
        dexFile: DexBackedDexFile,
        entryName: String,
        findings: MutableList<ApiFinding>,
        seenKeys: MutableSet<String>
    ) {
        // Step 1: Scan class bytecode for actual invocation instructions
        for (classDef in dexFile.classes) {
            val callingClass = classDef.type.removePrefix("L").removeSuffix(";").replace('/', '.')
            for (method in classDef.methods) {
                val impl = method.implementation ?: continue
                for (instruction in impl.instructions) {
                    if (instruction.opcode.name.startsWith("invoke-") && instruction is ReferenceInstruction) {
                        val ref = instruction.reference
                        if (ref is MethodReference) {
                            matchAndRecord(
                                ref = ref,
                                callingClass = callingClass,
                                callingMethod = method.name,
                                dexEntry = entryName,
                                isInvocation = true,
                                findings = findings,
                                seenKeys = seenKeys
                            )
                            if (findings.size >= MAX_TOTAL_FINDINGS) return
                        }
                    }
                }
            }
        }

        // Step 2: Also scan method reference section for table entries not caught by instruction walk
        try {
            val methodSection = dexFile.methodSection
            for (i in 0 until methodSection.size) {
                val methodRef = methodSection[i]
                matchAndRecord(
                    ref = methodRef,
                    callingClass = null,
                    callingMethod = null,
                    dexEntry = entryName,
                    isInvocation = false,
                    findings = findings,
                    seenKeys = seenKeys
                )
                if (findings.size >= MAX_TOTAL_FINDINGS) return
            }
        } catch (_: Exception) {
            // Method section reading failure is non-fatal
        }
    }

    private fun matchAndRecord(
        ref: MethodReference,
        callingClass: String?,
        callingMethod: String?,
        dexEntry: String,
        isInvocation: Boolean,
        findings: MutableList<ApiFinding>,
        seenKeys: MutableSet<String>
    ) {
        val definingClass = ref.definingClass
        val methodName = ref.name

        for (rule in RULES) {
            if (rule.targetClassDescriptor == definingClass) {
                if (rule.targetMethodNames.isEmpty() || rule.targetMethodNames.contains(methodName)) {
                    val formattedApi = "${definingClass.removePrefix("L").removeSuffix(";").replace('/', '.')}.$methodName"
                    val key = "$formattedApi|$callingClass|$callingMethod|$dexEntry|$isInvocation"
                    if (seenKeys.add(key)) {
                        val currentCategoryCount = findings.count { it.category == rule.category }
                        if (currentCategoryCount < MAX_FINDINGS_PER_CATEGORY) {
                            findings.add(
                                ApiFinding(
                                    apiName = formattedApi,
                                    category = rule.category,
                                    callingClass = callingClass,
                                    callingMethod = callingMethod,
                                    dexEntry = dexEntry,
                                    isInvocation = isInvocation,
                                    explanation = rule.explanation
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
