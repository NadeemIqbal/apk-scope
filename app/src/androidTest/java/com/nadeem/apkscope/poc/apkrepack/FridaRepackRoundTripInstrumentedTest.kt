package com.nadeem.apkscope.poc.apkrepack

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device verification of the Frida repack fix. Runs the REAL [ApkRepackPipeline] against a
 * self-owned fixture APK pushed to /data/local/tmp, and asserts it produces a patched APK.
 *
 * Before this fix the pipeline silently shipped an APK whose manifest named
 * com.nadeem.apkscope.FridaLoaderFactory as its appComponentFactory while that class lived in no
 * dex (frida_loader.dex was missing from app assets), so the patched app died at launch with
 * ClassNotFoundException in LoadedApk.createAppFactory. This test drives the fixed path; the
 * produced APK's launchability is verified separately by installing and launching it.
 */
@RunWith(AndroidJUnit4::class)
class FridaRepackRoundTripInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun repackFixture_producesPatchedApk() {
        runBlocking {
            val src = File("/data/local/tmp/fixture-debug.apk")
            assertTrue("push a fixture APK to ${src.path} first", src.exists() && src.canRead())

            // Mirror the real UI flow: the picked APK is copied into the app's own cacheDir
            // (the repacker writes its temp file next to the input, so the input must live
            // somewhere the app process can write).
            val input = File(context.cacheDir, "fixture-debug.apk")
            src.copyTo(input, overwrite = true)

            val result = ApkRepackPipeline(context).repack(input)
            assertTrue("repack must succeed but was $result", result is ApkRepackPipeline.PipelineResult.Success)

            val out = (result as ApkRepackPipeline.PipelineResult.Success).result.exportedFilePath
            assertNotNull("patched APK path must be set", out)
            assertTrue("patched APK must exist and be non-empty", File(out!!).exists() && File(out).length() > 0)

            // Stage the patched APK where adb can pull it for the install + launch check.
            val exported = File(context.getExternalFilesDir(null), "patched-fixture.apk")
            File(out).copyTo(exported, overwrite = true)
            Log.i("RepackRoundTrip", "PATCHED=$out EXPORTED=${exported.absolutePath}")
        }
    }
}
