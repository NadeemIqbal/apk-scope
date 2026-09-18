package com.apksandbox.pinnedfixture

import android.app.Application
import android.util.Log
import java.io.BufferedReader

/**
 * POC Loader Application for the pinned fixture.
 *
 * On first run (or when the marker asset is present in the APK), this Application subclass:
 * 1. Checks for the presence of `assets/poc_instrumentation.marker` in the APK.
 * 2. If present, extracts the Frida script from assets to `filesDir` and loads the Gadget native library.
 * 3. If absent, does nothing — the unmodified fixture never loads any instrumentation.
 *
 * This design allows us to build the fixture once and either:
 * - Run it unmodified (no Gadget, no marker): certificate pinning is enforced, inspection fails.
 * - Run it modified (with marker/Gadget injected by the POC): certificate pinning is bypassed by Gadget hooks.
 *
 * This is the core of the POC's bounded instrumentation approach: the fixture source code is aware
 * of the Gadget loading mechanism and conditionally activates it only when the marker is present.
 * No bytecode patching or manifest modification needed — the fixture itself is built to support this.
 */
class PocLoaderApplication : Application() {

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)

        val markerAssetName = "poc_instrumentation.marker"
        val scriptAssetName = "poc_gadget_hook.js"
        val scriptFileName = "poc_gadget_hook.js"

        try {
            // Check if the marker asset exists (only present in the modified APK)
            val markerExists = try {
                val stream = base.assets.open(markerAssetName)
                stream.close()
                true
            } catch (e: Exception) {
                false
            }

            if (markerExists) {
                Log.i("PocLoaderApplication", "POC instrumentation marker detected; loading Gadget...")

                // Extract the Frida script from assets to filesDir (first run only)
                val scriptFile = base.getFileStreamPath(scriptFileName)
                if (!scriptFile.exists()) {
                    try {
                        val scriptContent = base.assets.open(scriptAssetName).bufferedReader().use { it.readText() }
                        base.openFileOutput(scriptFileName, android.content.Context.MODE_PRIVATE).use { out ->
                            out.write(scriptContent.toByteArray())
                        }
                        Log.i("PocLoaderApplication", "Extracted Frida script to $scriptFile")
                    } catch (e: Exception) {
                        Log.e("PocLoaderApplication", "Failed to extract Frida script: ${e.message}", e)
                    }
                }

                // Load the Gadget native library
                // The library should be present in lib/<abi>/libgadget.so after modification
                try {
                    System.loadLibrary("gadget")
                    Log.i("PocLoaderApplication", "Frida Gadget loaded successfully")
                } catch (e: Exception) {
                    Log.e("PocLoaderApplication", "Failed to load Gadget library: ${e.message}", e)
                }
            } else {
                Log.i("PocLoaderApplication", "POC instrumentation marker not found; running unmodified")
            }
        } catch (e: Exception) {
            Log.e("PocLoaderApplication", "Error during POC loader initialization: ${e.message}", e)
        }
    }
}
