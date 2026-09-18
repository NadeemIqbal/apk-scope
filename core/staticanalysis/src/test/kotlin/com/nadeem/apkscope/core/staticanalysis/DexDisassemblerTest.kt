package com.nadeem.apkscope.core.staticanalysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class DexDisassemblerTest {

    private fun findFixtureApk(): File? {
        val candidates = listOf(
            File("../../fixture/build/outputs/apk/debug/fixture-debug.apk"),
            File("fixture/build/outputs/apk/debug/fixture-debug.apk"),
            File("../fixture/build/outputs/apk/debug/fixture-debug.apk"),
        )
        return candidates.firstOrNull { it.exists() && it.canRead() }
    }

    @Test
    fun binaryXmlParserExtractsManifestComponentsAndIntentFilters() {
        val apkFile = findFixtureApk() ?: return // Skip if fixture not compiled in this env
        ZipFile(apkFile).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
            assertNotNull("AndroidManifest.xml should exist in fixture APK", entry)
            zip.getInputStream(entry).use { stream ->
                val components = BinaryXmlParser.parseManifest(stream)
                assertTrue("Should parse at least 3 components from fixture", components.size >= 3)

                val activity = components.firstOrNull { it.name.endsWith("FixtureActivity") }
                assertNotNull("FixtureActivity should be parsed", activity)
                assertEquals(ComponentDescriptor.ComponentType.ACTIVITY, activity!!.type)
                assertTrue("FixtureActivity should be exported", activity.exported)
                assertTrue("FixtureActivity should have intent filters", activity.intentFilters.isNotEmpty())

                val filter = activity.intentFilters.first()
                assertTrue(
                    "Should have MAIN action",
                    filter.actions.contains("android.intent.action.MAIN")
                )
                assertTrue(
                    "Should have LAUNCHER category",
                    filter.categories.contains("android.intent.category.LAUNCHER")
                )
            }
        }
    }

    @Test
    fun binaryXmlParserDecompilesFullXmlAndConfig() {
        val apkFile = findFixtureApk() ?: return
        ZipFile(apkFile).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
            assertNotNull("AndroidManifest.xml should exist in fixture APK", entry)
            zip.getInputStream(entry).use { stream ->
                val result = BinaryXmlParser.parseManifestFull(stream)
                assertNotNull(result.config)
                assertTrue("Package name should be non-empty", !result.config.packageName.isNullOrBlank())
                assertTrue("Should decompile non-empty raw XML", result.rawXml.isNotBlank())
                assertTrue("Raw XML should contain manifest tag", result.rawXml.contains("<manifest"))
                assertTrue("Raw XML should contain application tag", result.rawXml.contains("<application"))
                assertTrue("Raw XML should close manifest tag", result.rawXml.contains("</manifest>"))
                assertTrue("Should have components parsed", result.components.isNotEmpty())
            }
        }
    }

    @Test
    fun dexDisassemblerExtractsSmaliFromRealApk() {
        val apkFile = findFixtureApk() ?: return
        val result = DexDisassembler.disassembleClass(apkFile, "com.apksandbox.fixture.FixtureActivity")

        assertTrue("Expected Success, got $result", result is DexDisassembler.DisassemblyResult.Success)
        val success = result as DexDisassembler.DisassemblyResult.Success
        assertTrue("Smali code should declare class", success.smaliCode.contains(".class"))
        assertTrue("Smali code should name FixtureActivity", success.smaliCode.contains("Lcom/apksandbox/fixture/FixtureActivity;"))
        assertTrue(success.dexSource.endsWith(".dex"))
    }

    @Test
    fun dexDisassemblerReturnsNotFoundForMissingClass() {
        val apkFile = findFixtureApk() ?: return
        val result = DexDisassembler.disassembleClass(apkFile, "com.apksandbox.fixture.NonExistentClass")
        assertTrue("Expected NotFound, got $result", result is DexDisassembler.DisassemblyResult.NotFound)
    }
}
