package com.nadeem.apkscope.core.staticanalysis

import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import java.io.File
import java.io.StringWriter

/**
 * Native DEX disassembler for Android.
 * Powered by Google's smali-dexlib2 and smali-baksmali.
 * Loads APK dex containers (classes.dex, classes2.dex, ...) and disassembles
 * individual classes on-demand into formatted Dalvik / Smali bytecode.
 */
object DexDisassembler {

    sealed interface DisassemblyResult {
        data class Success(val smaliCode: String, val dexSource: String) : DisassemblyResult
        data class NotFound(val reason: String) : DisassemblyResult
        data class Error(val message: String) : DisassemblyResult
    }

    /**
     * Disassembles a single class from the given APK file into formatted Smali bytecode.
     * @param apkFile the APK archive file
     * @param className fully qualified class name (e.g. "com.apksandbox.fixture.FixtureActivity")
     */
    fun disassembleClass(apkFile: File, className: String): DisassemblyResult {
        if (!apkFile.exists() || !apkFile.canRead()) {
            return DisassemblyResult.Error("APK file does not exist or is unreadable: ${apkFile.absolutePath}")
        }

        // Convert class name to Dalvik descriptor:
        // "com.apksandbox.fixture.FixtureActivity" -> "Lcom/apksandbox/fixture/FixtureActivity;"
        val targetDescriptor = if (className.startsWith("L") && className.endsWith(";")) {
            className
        } else {
            "L" + className.replace('.', '/') + ";"
        }

        return try {
            val opcodes = Opcodes.getDefault()
            val container = DexFileFactory.loadDexContainer(apkFile, opcodes)
            val entryNames = container.dexEntryNames

            for (entryName in entryNames) {
                val dexEntry = container.getEntry(entryName) ?: continue
                val dexFile = dexEntry.dexFile
                for (classDef in dexFile.classes) {
                    if (classDef.type == targetDescriptor) {
                        val options = BaksmaliOptions().apply {
                            apiLevel = 34
                        }
                        val stringWriter = StringWriter()
                        val baksmaliWriter = BaksmaliWriter(stringWriter)
                        val classDefinition = ClassDefinition(options, classDef)
                        classDefinition.writeTo(baksmaliWriter)
                        return DisassemblyResult.Success(
                            smaliCode = stringWriter.toString(),
                            dexSource = entryName
                        )
                    }
                }
            }

            DisassemblyResult.NotFound("Class $targetDescriptor not found in DEX entries (${entryNames.joinToString()})")
        } catch (e: Exception) {
            DisassemblyResult.Error("Disassembly failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
