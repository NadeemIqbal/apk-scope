package com.nadeem.apkscope.poc.apkrepack

import android.content.Context
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * Injects Frida Gadget payload into an APK for instrumentation.
 *
 * The payload includes:
 * - lib/arm64-v8a/libgadget.so — Frida runtime (arm64 prebuilt)
 * - assets/poc_instrumentation.marker — Triggers PocLoaderApplication to load Gadget
 * - assets/frida-script.js — Gadget initialization script (future enhancement)
 *
 * The fixture's PocLoaderApplication checks for the marker asset at runtime and
 * conditionally loads the Gadget library via System.loadLibrary("gadget").
 */
class GadgetPayloadInjector(private val context: Context) {

    private val TAG = "GadgetPayloadInjector"

    /**
     * Build the Frida Gadget payload (files to inject into APK).
     *
     * @param presentAbis List of ABIs supported by the target APK.
     * @return Map of APK-relative paths to file contents (ready for ReVancedApkRepacker.repack)
     */
    fun buildPayload(presentAbis: List<String>, targetPackageName: String, apkFile: File): Map<String, ByteArray> {
        Log.i(TAG, "Building Frida Gadget payload for ABIs: $presentAbis, package: $targetPackageName...")

        val payload = mutableMapOf<String, ByteArray>()
        val abisToInject = if (presentAbis.isEmpty()) {
            listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        } else {
            presentAbis
        }

        // 1. Frida live traffic capture script.
        val fridaScript = try {
            context.assets.open("frida-live-capture-bundle.js").use { stream ->
                stream.readBytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frida script not found in assets: ${e.message}")
            """
            // Frida live capture script not found
            console.log("Frida Gadget loaded (live capture unavailable)");
            """.trimIndent().toByteArray()
        }
        // Never embed the channel token in the repacked APK. The Work profile creates and stores
        // the token after receiving this APK; the injected script obtains it through the
        // caller-authenticated FridaChannelProvider once the target process starts.
        payload["assets/frida-live-capture.js"] = fridaScript
        payload["assets/poc_gadget_hook.js"] = fridaScript
        Log.i(TAG, "✓ Added Frida live capture script without embedded credentials: ${fridaScript.size} bytes")

        // 2. Frida Gadget shared library (lib/<abi>/libgadget.so), config and embedded script
        val gadgetConfig = """
            {
              "interaction": {
                "type": "script",
                "path": "libgadget.script.so",
                "on_change": "ignore"
              }
            }
        """.trimIndent().toByteArray()

        for (abi in abisToInject) {
            val assetName = when (abi) {
                "arm64-v8a" -> "frida-gadget-arm64.so"
                "armeabi-v7a" -> "frida-gadget-arm.so"
                "x86_64" -> "frida-gadget-x86_64.so"
                "x86" -> "frida-gadget-x86.so"
                else -> null
            }
            
            if (assetName != null) {
                try {
                    val gadgetBinary = context.assets.open(assetName).use { stream ->
                        val bytes = stream.readBytes()
                        if (bytes.size < 1000) {
                            Log.w(TAG, "⚠️ WARNING: Frida Gadget binary ($assetName) appears to be a placeholder (${bytes.size} bytes). SSL bypass will not work.")
                        }
                        bytes
                    }
                    payload["lib/$abi/libgadget.so"] = gadgetBinary
                    payload["lib/$abi/libgadget.config.so"] = gadgetConfig
                    payload["lib/$abi/libgadget.config"] = gadgetConfig
                    payload["lib/$abi/libgadget.script.so"] = fridaScript
                    payload["lib/$abi/libgadget.script"] = fridaScript
                    Log.i(TAG, "✓ Added Frida Gadget and script for $abi: ${gadgetBinary.size} bytes")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load Frida Gadget binary ($assetName): ${e.message}", e)
                }
            }
        }

        // 3. Marker asset (assets/poc_instrumentation.marker)
        // This signals PocLoaderApplication (if present) to also load the Gadget library
        val marker = "poc_instrumentation_v1".toByteArray()
        payload["assets/poc_instrumentation.marker"] = marker
        Log.i(TAG, "✓ Added marker asset: ${marker.size} bytes")

        // 4. Network Security Configuration: add inspection-CA (user) trust by MODIFYING the
        // target's existing binary NSC in place, preserving the exact aapt2-produced chunk layout
        // the platform's resource loader accepts. res/xml/* is loaded as compiled binary XML;
        // both a plain-text file and an AXML synthesized from scratch are rejected at launch with
        // "Corrupt XML binary file" (nativeOpenXmlAsset), so we only augment an existing config.
        // A target without one is left untouched (adding a referenced resource would also require
        // editing resources.arsc + the manifest — out of scope here).
        // Only replace the target's NSC if it already has one (so the manifest + resources.arsc
        // already reference res/xml/network_security_config.xml at 0x7f010000). The replacement
        // MUST be aapt2-compiled AXML: Android's runtime resource loader rejects both plain text
        // and ARSCLib-synthesized AXML as "Corrupt XML binary file" on newer platforms, so we ship
        // the aapt2 output (frida_nsc.bin) in app assets and inject it verbatim.
        // Replace the target's NSC (only if it already has one, so the manifest + resources.arsc
        // already reference it) with an aapt2-compiled config that trusts the user CA store. It must
        // be aapt2-compiled AXML shipped in assets (frida_nsc.bin); ARSCLib-synthesized AXML is
        // rejected by the runtime resource loader on newer platforms.
        // Replace the target's NSC (only when it already has one, so the manifest + resources.arsc
        // already reference it) with an aapt2-compiled config that trusts the user CA store. It must
        // be aapt2-compiled AXML (frida_nsc.bin) — Android's runtime resource loader rejects plain
        // text and ARSCLib-synthesized AXML.
        if (readExistingNetworkSecurityConfig(apkFile) != null) {
            try {
                payload["res/xml/network_security_config.xml"] =
                    context.assets.open("frida_nsc.bin").use { it.readBytes() }
                Log.i(TAG, "✓ Replaced Network Security Config (aapt2-compiled binary XML)")
            } catch (e: Exception) {
                Log.w(TAG, "frida_nsc.bin missing; leaving target trust config unchanged: ${e.message}")
            }
        } else {
            Log.i(TAG, "Target has no network_security_config.xml; leaving trust config unchanged")
        }

        Log.i(TAG, "Payload complete: ${payload.size} files, ${payload.values.sumOf { it.size }} total bytes")
        return payload
    }

    /** Read the target's compiled res/xml/network_security_config.xml, or null if it has none. */
    private fun readExistingNetworkSecurityConfig(apkFile: File): ByteArray? {
        return try {
            ZipFile(apkFile).use { zip ->
                val entry = zip.getEntry("res/xml/network_security_config.xml") ?: return null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read existing network_security_config.xml: ${e.message}")
            null
        }
    }


    /**
     * Verify that at least one required Frida Gadget binary is available.
     * Called before attempting to inject to fail fast.
     */
    fun verifyGadgetAvailable(): Boolean {
        val assets = listOf("frida-gadget-arm64.so", "frida-gadget-arm.so", "frida-gadget-x86_64.so", "frida-gadget-x86.so")
        for (asset in assets) {
            try {
                context.assets.open(asset).use { stream ->
                    if (stream.available() > 0) return true
                }
            } catch (e: Exception) {
                // Ignore and check next
            }
        }
        Log.e(TAG, "No Frida Gadget binaries available in assets.")
        return false
    }
}
