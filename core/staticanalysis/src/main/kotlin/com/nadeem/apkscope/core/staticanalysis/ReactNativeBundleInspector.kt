package com.nadeem.apkscope.core.staticanalysis

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.LinkedHashSet
import java.util.zip.ZipFile

/**
 * Reads the JavaScript payload shipped by a React Native APK.
 *
 * A React Native Activity is only the native host. The application behavior normally lives in
 * assets/index.android.bundle. Release builds commonly store that bundle as Hermes bytecode, so
 * the original JavaScript is not recoverable unless the APK also contains a source map or a plain
 * JavaScript bundle. Hermes output below is deliberately described as recovered bytecode data,
 * never as the original source.
 */
object ReactNativeBundleInspector {
    private const val MAX_BUNDLE_BYTES = 16L * 1024 * 1024
    private const val MAX_DISPLAY_CHARS = 160_000
    private const val MAX_RECOVERED_STRINGS = 250
    private const val MAX_RECOVERED_STRING_CHARS = 512
    private const val HERMES_HEADER_SIZE = 128

    enum class Engine(val displayName: String) {
        JAVASCRIPT("JavaScript bundle"),
        HERMES("Hermes bytecode"),
    }

    sealed interface InspectionResult {
        data class Success(
            val entryName: String,
            val byteCount: Long,
            val engine: Engine,
            val displayText: String,
        ) : InspectionResult

        data class NotFound(val reason: String) : InspectionResult
        data class Error(val message: String) : InspectionResult
    }

    fun inspect(apkFile: File): InspectionResult {
        if (!apkFile.exists() || !apkFile.canRead()) {
            return InspectionResult.Error("APK file does not exist or is unreadable")
        }

        return try {
            ZipFile(apkFile).use { zip ->
                val entry = findBundleEntry(zip)
                    ?: return InspectionResult.NotFound(
                        "No React Native JavaScript bundle was found under assets/"
                    )
                if (entry.size > MAX_BUNDLE_BYTES) {
                    return InspectionResult.Error(
                        "React Native bundle is larger than the ${MAX_BUNDLE_BYTES / (1024 * 1024)} MiB inspection limit"
                    )
                }

                val bytes = zip.getInputStream(entry).use { readBounded(it, MAX_BUNDLE_BYTES) }
                if (isHermesBytecode(bytes)) {
                    val version = readUInt32LittleEndian(bytes, 8)
                    InspectionResult.Success(
                        entryName = entry.name,
                        byteCount = bytes.size.toLong(),
                        engine = Engine.HERMES,
                        displayText = renderHermesReport(entry.name, bytes, version),
                    )
                } else {
                    val source = String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
                    InspectionResult.Success(
                        entryName = entry.name,
                        byteCount = bytes.size.toLong(),
                        engine = Engine.JAVASCRIPT,
                        displayText = renderJavaScriptReport(entry.name, source),
                    )
                }
            }
        } catch (e: Exception) {
            InspectionResult.Error("React Native bundle inspection failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun findBundleEntry(zip: ZipFile) = sequenceOf(
        "assets/index.android.bundle",
        "assets/index.android.bundle.hbc",
    ).mapNotNull(zip::getEntry).firstOrNull { !it.isDirectory }
        ?: zip.entries().asSequence().firstOrNull { entry ->
            !entry.isDirectory && entry.name.startsWith("assets/") &&
                (entry.name.endsWith(".bundle") || entry.name.endsWith(".bundle.hbc"))
        }

    private fun renderJavaScriptReport(entryName: String, source: String): String {
        val clipped = source.take(MAX_DISPLAY_CHARS)
        return buildString {
            appendLine("/*")
            appendLine(" * React Native JavaScript bundle recovered from the APK.")
            appendLine(" * Entry: $entryName")
            appendLine(" * This is bundled JavaScript, not the original project file layout.")
            if (source.length > clipped.length) {
                appendLine(" * Display truncated at $MAX_DISPLAY_CHARS characters.")
            }
            appendLine(" */")
            appendLine()
            append(clipped)
        }
    }

    private fun renderHermesReport(entryName: String, bytes: ByteArray, version: Long): String {
        val parsed = parseHermesStringTable(bytes)
        val recoveredStrings = parsed?.strings
            ?.asSequence()
            ?.filter { isReadableString(it.value) }
            ?.distinctBy { it.value }
            ?.sortedWith(compareByDescending<RecoveredString> { codeLikeScore(it.value) }.thenBy { it.index })
            ?.take(MAX_RECOVERED_STRINGS)
            ?.toList()
            ?: recoverPrintableStrings(bytes).mapIndexed { index, value ->
                RecoveredString(index = index, kind = 0, value = value)
            }

        val report = buildString {
            appendLine("/*")
            appendLine(" * React Native Hermes bundle recovered from the APK.")
            appendLine(" * Entry: $entryName")
            appendLine(" * Engine: Hermes bytecode (HBC v${if (version > 0) version else "unknown"})")
            appendLine(" * Size: ${bytes.size} bytes")
            parsed?.let {
                appendLine(" * Functions: ${it.functionCount}")
                appendLine(" * String table: ${it.stringCount} entries (${it.identifierCount} identifiers)")
            }
            appendLine(" *")
            appendLine(" * Original JavaScript source is not embedded in this release bundle.")
            appendLine(" * The recovered values below are bytecode string data, not an exact source reconstruction.")
            appendLine(" * A matching source map or external Hermes decompiler is required for source-level output.")
            appendLine(" */")
            appendLine()
            appendLine(
                if (parsed == null) {
                    "// HBC string table could not be parsed; only a conservative raw-byte fallback is shown:"
                } else {
                    "// Readable Hermes string-table entries (not original source):"
                },
            )
            if (recoveredStrings.isEmpty()) {
                appendLine("// No printable string data was recovered.")
            } else {
                recoveredStrings.forEach { entry ->
                    appendLine("// [${entry.kindName()} #${entry.index}]")
                    appendLine(entry.value.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\n// "))
                }
            }
        }
        return if (report.length <= MAX_DISPLAY_CHARS) {
            report
        } else {
            report.take(MAX_DISPLAY_CHARS) + "\n// Display truncated at $MAX_DISPLAY_CHARS characters."
        }
    }

    private data class RecoveredString(
        val index: Int,
        val kind: Int,
        val value: String,
    ) {
        fun kindName(): String = if (kind == 1) "identifier" else "literal"
    }

    private data class ParsedHermesStrings(
        val functionCount: Long,
        val identifierCount: Long,
        val stringCount: Int,
        val strings: List<RecoveredString>,
    )

    /**
     * Hermes HBC stores strings in a packed table rather than as ordinary NUL-terminated bytes.
     * This reader covers the v87-v96 layout used by current React Native release bundles.
     */
    private fun parseHermesStringTable(bytes: ByteArray): ParsedHermesStrings? {
        val version = readUInt32LittleEndian(bytes, 8).toInt()
        if (version !in 87..96 || bytes.size < HERMES_HEADER_SIZE) return null

        val functionCount = readUInt32LittleEndian(bytes, 40)
        val stringKindCount = readUInt32LittleEndian(bytes, 44).toInt()
        val identifierCount = readUInt32LittleEndian(bytes, 48)
        val stringCount = readUInt32LittleEndian(bytes, 52).toInt()
        val overflowStringCount = readUInt32LittleEndian(bytes, 56).toInt()
        val stringStorageSize = readUInt32LittleEndian(bytes, 60)

        if (stringKindCount < 0 || stringCount < 0 || overflowStringCount < 0 ||
            stringCount > 200_000 || overflowStringCount > 100_000 || functionCount > 2_000_000
        ) {
            return null
        }

        var cursor = align(HERMES_HEADER_SIZE.toLong(), 32)
        cursor = advance(cursor, functionCount * 16L, bytes.size) ?: return null

        val kinds = ArrayList<Int>(stringCount)
        cursor = align(cursor, 4)
        repeat(stringKindCount) {
            val packed = readUInt32At(bytes, cursor) ?: return null
            cursor += 4
            val count = (packed and 0x7FFF_FFFFL).toInt()
            val kind = (packed ushr 31).toInt()
            if (count > stringCount - kinds.size) return null
            repeat(count) { kinds += kind }
        }
        if (kinds.size != stringCount) return null

        cursor = align(cursor, 4)
        cursor = advance(cursor, identifierCount * 4L, bytes.size) ?: return null
        val smallTableOffset = cursor
        cursor = advance(cursor, stringCount * 4L, bytes.size) ?: return null
        val overflowTableOffset = align(cursor, 4)
        cursor = advance(overflowTableOffset, overflowStringCount * 8L, bytes.size) ?: return null
        val storageOffset = align(cursor, 4)
        val storageEnd = advance(storageOffset, stringStorageSize, bytes.size) ?: return null

        val strings = ArrayList<RecoveredString>(stringCount)
        for (index in 0 until stringCount) {
            val packed = readUInt32At(bytes, smallTableOffset + index * 4L) ?: return null
            val isUtf16 = (packed and 1L) != 0L
            var offset = ((packed ushr 1) and 0x7F_FFFFL).toInt()
            var length = ((packed ushr 24) and 0xFFL).toInt()

            if (length == 0xFF) {
                if (offset >= overflowStringCount) return null
                val overflowOffset = overflowTableOffset + offset * 8L
                offset = (readUInt32At(bytes, overflowOffset) ?: return null).toInt()
                length = (readUInt32At(bytes, overflowOffset + 4) ?: return null).toInt()
            }

            val byteLength = if (isUtf16) length.toLong() * 2L else length.toLong()
            // The packed offset is already a byte offset into the combined character storage;
            // only the UTF-16 length is expressed in code units and needs doubling.
            val valueStart = storageOffset + offset.toLong()
            val valueEnd = valueStart + byteLength
            if (offset < 0 || length < 0 || valueStart < storageOffset || valueEnd > storageEnd) return null

            val start = valueStart.toInt()
            val size = byteLength.toInt()
            val value = if (isUtf16) {
                String(bytes, start, size, StandardCharsets.UTF_16LE)
            } else {
                String(bytes, start, size, StandardCharsets.ISO_8859_1)
            }
            strings += RecoveredString(index, kinds[index], value)
        }

        return ParsedHermesStrings(
            functionCount = functionCount,
            identifierCount = identifierCount,
            stringCount = stringCount,
            strings = strings,
        )
    }

    private fun isReadableString(value: String): Boolean {
        if (value.length < 4) return false
        if (!value.all { it == '\n' || it == '\r' || it == '\t' || it in ' '..'~' }) return false
        return value.count(Char::isLetterOrDigit) >= 2
    }

    private fun codeLikeScore(value: String): Int = buildList {
        if (value.contains("function ")) add(100)
        if (value.contains("=>")) add(90)
        if (value.contains("const ") || value.contains("let ") || value.contains("var ")) add(70)
        if (value.contains("return ")) add(60)
        if (value.contains("React") || value.contains("react-native")) add(50)
        if (value.length >= 120) add(20)
        add(0)
    }.maxOrNull() ?: 0

    private fun align(value: Long, alignment: Long): Long =
        (value + alignment - 1) / alignment * alignment

    private fun advance(start: Long, amount: Long, size: Int): Long? {
        val end = start + amount
        return if (amount < 0 || end < start || end > size.toLong()) null else end
    }

    private fun readUInt32At(bytes: ByteArray, offset: Long): Long? =
        if (offset < 0 || offset + 4 > bytes.size) null else readUInt32LittleEndian(bytes, offset.toInt())

    private fun recoverPrintableStrings(bytes: ByteArray): List<String> {
        val values = LinkedHashSet<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.length >= 4) {
                val value = current.toString()
                    .take(MAX_RECOVERED_STRING_CHARS)
                    .trim()
                if (value.length >= 4 && value.any(Char::isLetterOrDigit)) values += value
            }
            current.setLength(0)
        }

        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            if (value in 32..126) {
                if (current.length < MAX_RECOVERED_STRING_CHARS * 2) current.append(value.toChar())
            } else {
                flush()
                if (values.size >= MAX_RECOVERED_STRINGS) break
            }
        }
        flush()
        return values.toList()
    }

    private fun isHermesBytecode(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0xC6.toByte() && bytes[1] == 0x1F.toByte() &&
            bytes[2] == 0xBC.toByte() && bytes[3] == 0x03.toByte()

    private fun readUInt32LittleEndian(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) return 0
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun readBounded(input: java.io.InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IllegalArgumentException("bundle exceeds inspection limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
