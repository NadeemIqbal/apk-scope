package com.nadeem.apkscope.core.staticanalysis

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DexSourceReconstructorTest {

    @Test
    fun reconstructsAClassFromTheBuiltFixtureApk() {
        val apkFile = listOf(
            File("../../fixture/build/outputs/apk/debug/fixture-debug.apk"),
            File("fixture/build/outputs/apk/debug/fixture-debug.apk"),
            File("../fixture/build/outputs/apk/debug/fixture-debug.apk"),
        ).firstOrNull { it.exists() && it.canRead() } ?: return

        val disassembly = DexDisassembler.disassembleClass(
            apkFile = apkFile,
            className = "com.apksandbox.fixture.FixtureActivity",
        )
        assertTrue("Expected fixture class disassembly, got $disassembly", disassembly is DexDisassembler.DisassemblyResult.Success)

        val source = DexSourceReconstructor.reconstruct(
            (disassembly as DexDisassembler.DisassemblyResult.Success).smaliCode,
        )

        assertTrue(source.contains("class FixtureActivity"))
        assertTrue(source.contains("fun onCreate("))
        assertTrue(source.contains("Reconstructed source from DEX bytecode"))
    }

    @Test
    fun reconstructsClassHierarchyMethodsAndConstantReturnsFromSmali() {
        val smali = """
            .class public final Lcom/app1833/MainActivity;
            .super Lcom/facebook/react/ReactActivity;
            .source "MainActivity.kt"

            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Lcom/facebook/react/ReactActivity;-><init>()V
                return-void
            .end method

            .method protected onCreate(Landroid/os/Bundle;)V
                .registers 2
                invoke-super {p0, p1}, Lcom/facebook/react/ReactActivity;->onCreate(Landroid/os/Bundle;)V
                return-void
            .end method

            .method protected getMainComponentName()Ljava/lang/String;
                .registers 1
                const-string v0, "app_stagingDebug"
                return-object v0
            .end method
        """.trimIndent()

        val source = DexSourceReconstructor.reconstruct(smali)

        assertTrue(source.contains("package com.app1833"))
        assertTrue(source.contains("import android.os.Bundle"))
        assertTrue(source.contains("import com.facebook.react.ReactActivity"))
        assertTrue(source.contains("public class MainActivity : ReactActivity()"))
        assertTrue(source.contains("override fun onCreate(savedInstanceState: Bundle)"))
        assertTrue(source.contains("super.onCreate(savedInstanceState)"))
        assertTrue(source.contains("fun getMainComponentName(): String"))
        assertTrue(source.contains("return \"app_stagingDebug\""))
    }

    @Test
    fun keepsUnknownInstructionsVisibleInsteadOfReturningAnEmptySkeleton() {
        val smali = """
            .class public Lcom/example/Receiver;
            .super Landroid/content/BroadcastReceiver;

            .method public onReceive(Landroid/content/Context;Landroid/content/Intent;)V
                .registers 3
                invoke-virtual {p2}, Landroid/content/Intent;->getAction()Ljava/lang/String;
                move-result-object v0
                return-void
            .end method
        """.trimIndent()

        val source = DexSourceReconstructor.reconstruct(smali)
        assertTrue(source.contains("class Receiver : BroadcastReceiver()"))
        assertTrue(source.contains("override fun onReceive(context: Context, intent: Intent)"))
        assertTrue(source.contains("intent.getAction()"))
        assertTrue(source.contains("val v0 = intent.getAction()"))
    }

    @Test
    fun reportsWhenSmaliDoesNotContainAClassDeclaration() {
        val source = DexSourceReconstructor.reconstruct("# DEX disassembly failed")

        assertTrue(source.contains("no .class declaration"))
    }

    @Test
    fun readsPlainReactNativeJavaScriptBundle() {
        val apkFile = zipWithEntry(
            "assets/index.android.bundle",
            "__d(function(g,r,i,a,m,e,d){e.default='1833';});\n".toByteArray(),
        )

        try {
            val result = ReactNativeBundleInspector.inspect(apkFile)
            assertTrue(result is ReactNativeBundleInspector.InspectionResult.Success)
            result as ReactNativeBundleInspector.InspectionResult.Success
            assertTrue(result.engine == ReactNativeBundleInspector.Engine.JAVASCRIPT)
            assertTrue(result.displayText.contains("e.default='1833'"))
        } finally {
            apkFile.delete()
        }
    }

    @Test
    fun identifiesHermesBundleWithoutClaimingItIsOriginalJavaScript() {
        val bytes = ByteArray(96)
        bytes[0] = 0xC6.toByte()
        bytes[1] = 0x1F
        bytes[2] = 0xBC.toByte()
        bytes[3] = 0x03
        bytes[8] = 96
        val recoveredValue = "getMainComponentName 1833"
        recoveredValue.toByteArray().copyInto(bytes, destinationOffset = 16)
        val apkFile = zipWithEntry("assets/index.android.bundle", bytes)

        try {
            val result = ReactNativeBundleInspector.inspect(apkFile)
            assertTrue(result is ReactNativeBundleInspector.InspectionResult.Success)
            result as ReactNativeBundleInspector.InspectionResult.Success
            assertTrue(result.engine == ReactNativeBundleInspector.Engine.HERMES)
            assertTrue(result.displayText.contains("Hermes bytecode (HBC v96)"))
            assertTrue(result.displayText.contains("Original JavaScript source is not embedded"))
            assertTrue(result.displayText.contains("getMainComponentName 1833"))
        } finally {
            apkFile.delete()
        }
    }

    @Test
    fun readsPackedHermesV96StringTable() {
        val apkFile = zipWithEntry("assets/index.android.bundle", minimalHermesV96Bundle())

        try {
            val result = ReactNativeBundleInspector.inspect(apkFile)
            assertTrue(result is ReactNativeBundleInspector.InspectionResult.Success)
            result as ReactNativeBundleInspector.InspectionResult.Success
            assertTrue(result.displayText.contains("String table: 2 entries (1 identifiers)"))
            assertTrue(result.displayText.contains("MainActivity"))
            assertTrue(result.displayText.contains("1833"))
        } finally {
            apkFile.delete()
        }
    }

    private fun minimalHermesV96Bundle(): ByteArray {
        val storage = "MainActivity1833".toByteArray()
        val totalSize = 164 + storage.size
        val bytes = ByteArray(totalSize)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        header.putLong(0, 0x1F1903C103BC1FC6L)
        header.putInt(8, 96)
        header.putInt(32, totalSize)
        header.putInt(40, 1) // function count
        header.putInt(44, 2) // string-kind run count
        header.putInt(48, 1) // identifier count
        header.putInt(52, 2) // string count
        header.putInt(60, storage.size)

        // Header is aligned to 128 bytes; one v96 small function header follows it.
        header.putInt(128, 0)
        header.putInt(132, 0)
        header.putInt(136, 0)
        header.putInt(140, 0)

        // One identifier followed by one literal string.
        header.putInt(144, 0x80000001.toInt())
        header.putInt(148, 1)
        header.putInt(152, 0)

        // Packed small string entries: (isUTF16, byte offset, code-unit length).
        header.putInt(156, 12 shl 24)
        header.putInt(160, (12 shl 1) or (4 shl 24))
        storage.copyInto(bytes, destinationOffset = 164)
        return bytes
    }

    private fun zipWithEntry(name: String, bytes: ByteArray): File {
        val file = File.createTempFile("react-native-bundle", ".apk")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
        return file
    }
}
