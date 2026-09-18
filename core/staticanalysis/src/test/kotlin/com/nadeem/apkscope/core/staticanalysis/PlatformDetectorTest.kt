package com.nadeem.apkscope.core.staticanalysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class PlatformDetectorTest {

    private fun findFixtureApk(): File? {
        val candidates = listOf(
            File("../../fixture/build/outputs/apk/debug/fixture-debug.apk"),
            File("fixture/build/outputs/apk/debug/fixture-debug.apk"),
            File("../fixture/build/outputs/apk/debug/fixture-debug.apk"),
        )
        return candidates.firstOrNull { it.exists() && it.canRead() }
    }

    @Test
    fun fixtureApkIsDetectedAsNativeKotlin() {
        val apkFile = findFixtureApk() ?: return
        ZipFile(apkFile).use { zip ->
            val info = PlatformDetector.detect(zip, listOf("com.apksandbox.fixture.FixtureActivity"))
            assertEquals(AppPlatform.NATIVE_KOTLIN, info.platform)
            assertEquals("Native (Kotlin)", info.platform.displayName)
            assertEquals("Kotlin Android", info.details)
        }
    }
}
