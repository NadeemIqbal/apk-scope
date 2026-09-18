package com.nadeem.apkscope.core.staticanalysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FlutterArtifactInspectorTest {
    @Test
    fun recoversDirectAssetsAndAotStringsWithProvenanceWithoutClaimingSourceRecovery() {
        val elf = ByteArray(512)
        elf[0] = 0x7F
        elf[1] = 'E'.code.toByte()
        elf[2] = 'L'.code.toByte()
        elf[3] = 'F'.code.toByte()
        elf[4] = 2 // ELF64
        elf[5] = 1 // little endian
        elf[18] = 183.toByte() // AArch64
        "package:bank/features/login.dart\u0000https://api.example.test/v1/login\u0000Login failed"
            .toByteArray()
            .copyInto(elf, 64)
        val apk = flutterApk(
            "lib/arm64-v8a/libapp.so" to elf,
            "assets/flutter_assets/AssetManifest.json" to "{\"assets/logo.png\":[\"assets/logo.png\"]}".toByteArray(),
            "assets/flutter_assets/i18n/en.arb" to "{\"welcome\":\"Welcome back\"}".toByteArray(),
        )

        try {
            val result = FlutterArtifactInspector.inspect(apk)
            assertTrue(result is FlutterArtifactInspector.InspectionResult.Success)
            result as FlutterArtifactInspector.InspectionResult.Success

            assertTrue(result.displayText.contains("Direct packaged text assets"))
            assertTrue(result.displayText.contains("Welcome back"))
            assertTrue(result.displayText.contains("package:bank/features/login.dart"))
            assertTrue(result.displayText.contains("https://api.example.test/v1/login"))
            assertTrue(result.displayText.contains("lib/arm64-v8a/libapp.so@0x40 ASCII"))
            assertTrue(result.displayText.contains("ELF64 AArch64 Dart AOT binary"))
            assertTrue(result.displayText.contains("not the original Dart files"))
            assertFalse(result.limitsReached)
            assertTrue(result.recoveredValues.any { it.kind == FlutterArtifactInspector.ValueKind.DART_URI })
            assertTrue(result.recoveredValues.any { it.kind == FlutterArtifactInspector.ValueKind.URL })
        } finally {
            apk.delete()
        }
    }

    @Test
    fun recoversUtf16StringsAndDeduplicatesAcrossAbisWhileKeepingLocations() {
        val value = "CheckoutScreen.build"
        val utf16 = value.toByteArray(StandardCharsets.UTF_16LE) + byteArrayOf(0, 0)
        val apk = flutterApk(
            "lib/arm64-v8a/libapp.so" to utf16,
            "lib/x86_64/libapp.so" to utf16,
        )

        try {
            val result = FlutterArtifactInspector.inspect(apk) as FlutterArtifactInspector.InspectionResult.Success
            val recovered = result.recoveredValues.single { it.value == value }
            assertEquals(2, recovered.locations.size)
            assertTrue(recovered.locations.all { it.encoding == FlutterArtifactInspector.Encoding.UTF16_LE })
            assertTrue(recovered.locations.any { it.entryName.contains("arm64-v8a") })
            assertTrue(recovered.locations.any { it.entryName.contains("x86_64") })
        } finally {
            apk.delete()
        }
    }

    @Test
    fun inflatesFlutterNoticesWithinItsOwnBound() {
        val compressed = java.io.ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write("Dependency License\nCopyright Example".toByteArray()) }
        }.toByteArray()
        val apk = flutterApk("assets/flutter_assets/NOTICES.Z" to compressed)

        try {
            val result = FlutterArtifactInspector.inspect(apk) as FlutterArtifactInspector.InspectionResult.Success
            assertTrue(result.displayText.contains("Dependency License"))
            assertTrue(result.artifacts.single().note.orEmpty().contains("decompressed"))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun lateHighSignalValuesDisplaceGenericStringsAfterTheValueCap() {
        val aotData = buildString {
            repeat(5_010) { index -> append("genericSymbol$index\u0000") }
            append("https://late.example.test/recovered\u0000")
        }.toByteArray()
        val apk = flutterApk("lib/arm64-v8a/libapp.so" to aotData)

        try {
            val result = FlutterArtifactInspector.inspect(apk) as FlutterArtifactInspector.InspectionResult.Success
            assertEquals(5_000, result.recoveredValues.size)
            assertTrue(result.limitsReached)
            assertTrue(result.recoveredValues.any {
                it.kind == FlutterArtifactInspector.ValueKind.URL &&
                    it.value == "https://late.example.test/recovered"
            })
        } finally {
            apk.delete()
        }
    }

    @Test
    fun reportsNotFoundForANonFlutterApk() {
        val apk = flutterApk("classes.dex" to byteArrayOf(1, 2, 3, 4))
        try {
            val result = FlutterArtifactInspector.inspect(apk)
            assertTrue(result is FlutterArtifactInspector.InspectionResult.NotFound)
        } finally {
            apk.delete()
        }
    }

    private fun flutterApk(vararg entries: Pair<String, ByteArray>): File {
        val file = File.createTempFile("flutter-artifacts", ".apk")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
}
