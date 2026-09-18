package com.nadeem.apkscope.poc.apkrepack

import android.content.Context
import android.util.Log
import java.io.File

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
    fun buildPayload(presentAbis: List<String>, targetPackageName: String): Map<String, ByteArray> {
        Log.i(TAG, "Building Frida Gadget payload for ABIs: $presentAbis, package: $targetPackageName...")

        val payload = mutableMapOf<String, ByteArray>()
        val abisToInject = if (presentAbis.isEmpty()) {
            listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        } else {
            presentAbis
        }

        // 1. Frida live traffic capture script.
        val fridaScript = try {
            context.assets.open("frida-live-capture.js").use { stream ->
                stream.readBytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frida script not found in assets: ${e.message}")
            """
            // Frida live capture script not found
            console.log("Frida Gadget loaded (live capture unavailable)");
            """.trimIndent().toByteArray()
        }
        payload["assets/frida-live-capture.js"] = fridaScript
        payload["assets/poc_gadget_hook.js"] = fridaScript
        Log.i(TAG, "✓ Added Frida live capture script: ${fridaScript.size} bytes")

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

        // 4. Network Security Configuration (Fallback trust for inspection CA)
        // This helps if Frida fails to load or bypass pinning.
        val netSecConfig = """
            <?xml version="1.0" encoding="utf-8"?>
            <network-security-config>
                <base-config cleartextTrafficPermitted="true">
                    <trust-anchors>
                        <certificates src="system" />
                        <certificates src="user" />
                    </trust-anchors>
                </base-config>
                <debug-overrides>
                    <trust-anchors>
                        <certificates src="user" />
                    </trust-anchors>
                </debug-overrides>
            </network-security-config>
        """.trimIndent().toByteArray()
        payload["res/xml/network_security_config.xml"] = netSecConfig
        Log.i(TAG, "✓ Added Network Security Config fallback")

        Log.i(TAG, "Payload complete: ${payload.size} files, ${payload.values.sumOf { it.size }} total bytes")
        return payload
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
