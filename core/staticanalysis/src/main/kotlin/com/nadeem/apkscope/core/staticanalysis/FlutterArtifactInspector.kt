package com.nadeem.apkscope.core.staticanalysis

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Best-effort, fully local recovery of the Flutter-owned material that is actually present in an APK.
 *
 * Release Flutter applications normally store AOT-compiled Dart in `lib/<abi>/libapp.so`; that is
 * native machine code, not a container of the original `.dart` files. This inspector therefore keeps
 * direct text assets separate from strings recovered out of AOT/snapshot binaries and never calls the
 * latter source code. Every recovered value retains its APK entry, uncompressed byte offset, and
 * encoding. The limits below are part of the product contract: a large or hostile APK produces an
 * explicitly truncated report rather than an unbounded allocation.
 */
object FlutterArtifactInspector {
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 96L * 1024 * 1024
    private const val MAX_NOTICE_BYTES = 8L * 1024 * 1024
    private const val MAX_ENTRIES = 2_048
    private const val MAX_RECOVERED_VALUES = 5_000
    private const val MAX_VALUE_CHARS = 2_048
    private const val MAX_LOCATIONS_PER_VALUE = 4
    private const val MAX_TEXT_PREVIEWS = 64
    private const val MAX_TEXT_PREVIEW_CHARS = 24_000
    private const val MAX_REPORT_CHARS = 240_000
    private const val MIN_STRING_CHARS = 4

    enum class ValueKind(val heading: String) {
        DART_URI("Dart library and source URIs"),
        URL("URLs and network literals"),
        SOURCE_PATH("Dart/source paths"),
        ASSET_PATH("Asset and resource paths"),
        SYMBOL("Identifiers and symbol-like values"),
        MESSAGE("Human-readable messages"),
        OTHER("Other printable values"),
    }

    enum class Encoding(val label: String) {
        ASCII("ASCII"),
        UTF8("UTF-8"),
        UTF16_LE("UTF-16LE"),
    }

    data class Location(
        val entryName: String,
        val offset: Long,
        val encoding: Encoding,
    )

    data class RecoveredValue(
        val value: String,
        val kind: ValueKind,
        val locations: List<Location>,
    )

    data class ArtifactSummary(
        val entryName: String,
        val byteCount: Long,
        val role: String,
        val inspected: Boolean,
        val note: String? = null,
    )

    sealed interface InspectionResult {
        data class Success(
            val displayText: String,
            val artifacts: List<ArtifactSummary>,
            val recoveredValues: List<RecoveredValue>,
            val bytesInspected: Long,
            val limitsReached: Boolean,
        ) : InspectionResult

        data class NotFound(val reason: String) : InspectionResult
        data class Error(val message: String) : InspectionResult
    }

    private data class MutableRecoveredValue(
        val value: String,
        var kind: ValueKind,
        val locations: MutableList<Location> = mutableListOf(),
    )

    private class RecoveryAccumulator {
        val values = LinkedHashMap<String, MutableRecoveredValue>()
        val countsByKind = IntArray(ValueKind.entries.size)
        var limitReached: Boolean = false
    }

    private data class TextPreview(
        val entryName: String,
        val text: String,
        val truncated: Boolean,
    )

    fun inspect(apkFile: File): InspectionResult {
        if (!apkFile.exists() || !apkFile.canRead()) {
            return InspectionResult.Error("APK file does not exist or is unreadable")
        }

        return try {
            ZipFile(apkFile).use { zip -> inspectZip(zip) }
        } catch (e: Exception) {
            InspectionResult.Error(
                "Flutter artifact inspection failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}",
            )
        }
    }

    private fun inspectZip(zip: ZipFile): InspectionResult {
        val candidates = zip.entries().asSequence()
            .filterNot(ZipEntry::isDirectory)
            .filter { isFlutterArtifact(it.name) }
            .toList()
        if (candidates.isEmpty()) {
            return InspectionResult.NotFound(
                "No libapp.so, Flutter asset directory, or Dart VM snapshot entries were found in the APK",
            )
        }

        val ordered = candidates.sortedWith(
            compareBy<ZipEntry>({ inspectionPriority(it.name) }, { it.name }),
        )
        val recovery = RecoveryAccumulator()
        val summaries = mutableListOf<ArtifactSummary>()
        val previews = mutableListOf<TextPreview>()
        var totalBytes = 0L
        var limitsReached = candidates.size > MAX_ENTRIES

        ordered.take(MAX_ENTRIES).forEach { entry ->
            val role = artifactRole(entry.name)
            val declaredSize = entry.size.coerceAtLeast(0L)
            val remaining = MAX_TOTAL_BYTES - totalBytes
            val entryLimit = minOf(MAX_ENTRY_BYTES, remaining)
            if (entryLimit <= 0L) {
                limitsReached = true
                summaries += ArtifactSummary(entry.name, declaredSize, role, false, "total read budget reached")
                return@forEach
            }
            if (declaredSize > entryLimit) {
                limitsReached = true
                summaries += ArtifactSummary(
                    entry.name,
                    declaredSize,
                    role,
                    false,
                    if (declaredSize > MAX_ENTRY_BYTES) "entry exceeds 64 MiB limit" else "total read budget would be exceeded",
                )
                return@forEach
            }

            val shouldInspect = shouldInspectContents(entry.name)
            if (!shouldInspect) {
                summaries += ArtifactSummary(entry.name, declaredSize, role, false, "inventory only; binary media/font asset")
                return@forEach
            }

            val bytes = try {
                zip.getInputStream(entry).use { readBounded(it, entryLimit) }
            } catch (e: SizeLimitExceeded) {
                limitsReached = true
                summaries += ArtifactSummary(entry.name, declaredSize, role, false, e.message)
                return@forEach
            }
            totalBytes += bytes.size

            val noticeInflateBudget = MAX_TOTAL_BYTES - totalBytes
            val effectiveBytes = if (
                noticeInflateBudget > 0L &&
                (entry.name.endsWith("/NOTICES.Z") || entry.name == "assets/flutter_assets/NOTICES.Z")
            ) {
                inflateNotices(bytes, minOf(MAX_NOTICE_BYTES, noticeInflateBudget)) ?: bytes
            } else {
                bytes
            }
            if (effectiveBytes !== bytes) {
                // Inflated data is bounded separately and counts toward the same total work budget.
                totalBytes += effectiveBytes.size
            }

            val text = decodeLikelyText(effectiveBytes)
            if (text != null && previews.size < MAX_TEXT_PREVIEWS) {
                previews += TextPreview(
                    entryName = entry.name,
                    text = text.take(MAX_TEXT_PREVIEW_CHARS),
                    truncated = text.length > MAX_TEXT_PREVIEW_CHARS,
                )
            }
            recoverStrings(entry.name, effectiveBytes, recovery)

            val note = when {
                entry.name.endsWith("/libapp.so") -> inspectElfHeader(bytes)
                effectiveBytes !== bytes -> "zlib notices decompressed (${effectiveBytes.size} bytes)"
                text != null -> "direct UTF-8 text"
                else -> null
            }
            summaries += ArtifactSummary(entry.name, declaredSize.takeIf { it > 0 } ?: bytes.size.toLong(), role, true, note)
            if (recovery.limitReached) limitsReached = true
        }

        val recovered = recovery.values.values.map {
            RecoveredValue(it.value, it.kind, it.locations.toList())
        }.sortedWith(
            compareBy<RecoveredValue>({ it.kind.ordinal }, { it.value.lowercase() }),
        )
        val report = renderReport(summaries, previews, recovered, totalBytes, limitsReached)
        return InspectionResult.Success(report, summaries, recovered, totalBytes, limitsReached)
    }

    private fun isFlutterArtifact(name: String): Boolean =
        name.startsWith("assets/flutter_assets/") ||
            name.endsWith("/libapp.so") ||
            name.endsWith("/libflutter.so") ||
            name in LEGACY_SNAPSHOT_ENTRIES

    private fun inspectionPriority(name: String): Int = when {
        name.endsWith("/libapp.so") -> 0
        name in LEGACY_SNAPSHOT_ENTRIES || name.contains("snapshot") -> 1
        isDirectTextEntry(name) -> 2
        name.endsWith("AssetManifest.bin") || name.endsWith("NOTICES.Z") -> 3
        else -> 4
    }

    private fun artifactRole(name: String): String = when {
        name.endsWith("/libapp.so") -> "Dart AOT application ELF (${abiFromEntry(name)})"
        name.endsWith("/libflutter.so") -> "Flutter engine ELF (${abiFromEntry(name)})"
        name.contains("snapshot", ignoreCase = true) -> "Dart VM snapshot"
        name.endsWith("AssetManifest.json") || name.endsWith("AssetManifest.bin") || name.endsWith("AssetManifest.bin.json") -> "Flutter asset manifest"
        name.endsWith("FontManifest.json") -> "Flutter font manifest"
        name.endsWith("NOTICES.Z") -> "Compressed dependency notices"
        isDirectTextEntry(name) -> "Direct text asset"
        else -> "Flutter asset"
    }

    private fun shouldInspectContents(name: String): Boolean = when {
        name.endsWith("/libflutter.so") -> false // Framework engine code, not the target's Dart program.
        name.endsWith("/libapp.so") -> true
        name in LEGACY_SNAPSHOT_ENTRIES || name.contains("snapshot", ignoreCase = true) -> true
        name.startsWith("assets/flutter_assets/") && !isKnownBinaryAsset(name) -> true
        name.endsWith("AssetManifest.bin") || name.endsWith("NOTICES.Z") -> true
        else -> false
    }

    private fun isDirectTextEntry(name: String): Boolean {
        val lower = name.lowercase()
        return TEXT_EXTENSIONS.any(lower::endsWith) ||
            lower.endsWith("/assetmanifest.json") ||
            lower.endsWith("/assetmanifest.bin.json") ||
            lower.endsWith("/fontmanifest.json") ||
            lower.endsWith("/nativeassets.json") ||
            lower.endsWith("/license")
    }

    /** Unknown/extensionless package assets are inspected; only well-known opaque media is skipped. */
    private fun isKnownBinaryAsset(name: String): Boolean {
        val lower = name.lowercase()
        return BINARY_ASSET_EXTENSIONS.any(lower::endsWith)
    }

    private fun recoverStrings(
        entryName: String,
        bytes: ByteArray,
        destination: RecoveryAccumulator,
    ) {
        scanAscii(entryName, bytes, destination)
        scanUtf8(entryName, bytes, destination)
        scanUtf16Le(entryName, bytes, destination, 0)
        scanUtf16Le(entryName, bytes, destination, 1)
    }

    private fun scanAscii(
        entryName: String,
        bytes: ByteArray,
        destination: RecoveryAccumulator,
    ) {
        var start = -1
        val current = StringBuilder()
        fun flush() {
            if (start >= 0) addRecovered(entryName, start.toLong(), Encoding.ASCII, current.toString(), destination)
            current.setLength(0)
            start = -1
        }
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            if (value in 32..126 || value == 9 || value == 10 || value == 13) {
                if (start < 0) start = index
                if (current.length < MAX_VALUE_CHARS * 2) current.append(value.toChar())
            } else {
                flush()
            }
        }
        flush()
    }

    private fun scanUtf8(
        entryName: String,
        bytes: ByteArray,
        destination: RecoveryAccumulator,
    ) {
        var start = -1
        var hasHighByte = false
        val current = ByteArrayOutputStream()
        fun flush() {
            if (start >= 0 && hasHighByte && current.size() >= MIN_STRING_CHARS) {
                val decoded = runCatching {
                    StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(current.toByteArray()))
                        .toString()
                }.getOrNull()
                if (decoded != null && decoded.all(::isReadableUnicode)) {
                    addRecovered(entryName, start.toLong(), Encoding.UTF8, decoded, destination)
                }
            }
            current.reset()
            start = -1
            hasHighByte = false
        }
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            if (value == 0 || value in 1..8 || value in 14..31 || value == 127) {
                flush()
            } else {
                if (start < 0) start = index
                if (current.size() < MAX_VALUE_CHARS * 4) current.write(value)
                if (value >= 128) hasHighByte = true
            }
        }
        flush()
    }

    private fun scanUtf16Le(
        entryName: String,
        bytes: ByteArray,
        destination: RecoveryAccumulator,
        alignment: Int,
    ) {
        var start = -1
        val current = StringBuilder()
        fun flush() {
            if (start >= 0) addRecovered(entryName, start.toLong(), Encoding.UTF16_LE, current.toString(), destination)
            current.setLength(0)
            start = -1
        }
        var index = alignment
        while (index + 1 < bytes.size) {
            val codeUnit = (bytes[index].toInt() and 0xFF) or ((bytes[index + 1].toInt() and 0xFF) shl 8)
            val char = codeUnit.toChar()
            if (isReadableUnicode(char) && !Character.isSurrogate(char)) {
                if (start < 0) start = index
                if (current.length < MAX_VALUE_CHARS * 2) current.append(char)
            } else {
                flush()
            }
            index += 2
        }
        flush()
    }

    private fun addRecovered(
        entryName: String,
        offset: Long,
        encoding: Encoding,
        raw: String,
        destination: RecoveryAccumulator,
    ) {
        val value = raw.trim().replace("\u0000", "").take(MAX_VALUE_CHARS)
        if (!isUseful(value)) return
        val kind = classify(value)
        val existing = destination.values[value]
        if (existing != null) {
            if (kind.ordinal < existing.kind.ordinal) {
                destination.countsByKind[existing.kind.ordinal]--
                existing.kind = kind
                destination.countsByKind[kind.ordinal]++
            }
            val location = Location(entryName, offset, encoding)
            if (existing.locations.size < MAX_LOCATIONS_PER_VALUE && location !in existing.locations) {
                existing.locations += location
            }
            return
        }
        if (destination.values.size >= MAX_RECOVERED_VALUES) {
            destination.limitReached = true
            // Continue scanning after the cap so a late URL/source URI can displace an early generic
            // compiler string. The map remains bounded; only strictly higher-signal categories evict.
            val worstOrdinal = destination.countsByKind.indices.reversed()
                .firstOrNull { destination.countsByKind[it] > 0 }
                ?: return
            if (worstOrdinal <= kind.ordinal) return
            val eviction = destination.values.entries.last { it.value.kind.ordinal == worstOrdinal }
            destination.values.remove(eviction.key)
            destination.countsByKind[worstOrdinal]--
        }
        destination.values[value] = MutableRecoveredValue(
            value = value,
            kind = kind,
            locations = mutableListOf(Location(entryName, offset, encoding)),
        )
        destination.countsByKind[kind.ordinal]++
    }

    private fun isUseful(value: String): Boolean {
        if (value.length < MIN_STRING_CHARS || value.none(Char::isLetterOrDigit)) return false
        if (value.all { it == value.first() }) return false
        val controls = value.count { Character.isISOControl(it) && it !in "\n\r\t" }
        if (controls != 0) return false
        val meaningful = value.count {
            it.isLetterOrDigit() || it.isWhitespace() || it in COMMON_PUNCTUATION
        }
        return meaningful.toDouble() / value.length >= 0.75
    }

    private fun classify(value: String): ValueKind {
        val lower = value.lowercase()
        return when {
            lower.startsWith("package:") || lower.startsWith("dart:") -> ValueKind.DART_URI
            URL_PATTERN.containsMatchIn(value) -> ValueKind.URL
            lower.endsWith(".dart") || lower.contains(".dart:") || lower.contains("/lib/") && lower.contains("dart") -> ValueKind.SOURCE_PATH
            lower.contains("flutter_assets/") || lower.startsWith("assets/") || ASSET_EXTENSION_PATTERN.containsMatchIn(lower) -> ValueKind.ASSET_PATH
            value.none(Char::isWhitespace) && SYMBOL_PATTERN.matches(value) -> ValueKind.SYMBOL
            value.any(Char::isWhitespace) -> ValueKind.MESSAGE
            else -> ValueKind.OTHER
        }
    }

    private fun decodeLikelyText(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return ""
        val decoded = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .removePrefix("\uFEFF")
        }.getOrNull() ?: return null
        val sample = decoded.take(8_192)
        if (sample.isEmpty()) return decoded
        val readable = sample.count(::isReadableUnicode)
        return decoded.takeIf { readable.toDouble() / sample.length >= 0.92 }
    }

    private fun isReadableUnicode(char: Char): Boolean =
        char == '\n' || char == '\r' || char == '\t' ||
            (!Character.isISOControl(char) && char != '\uFFFD')

    private fun inflateNotices(bytes: ByteArray, limit: Long): ByteArray? = runCatching {
        InflaterInputStream(ByteArrayInputStream(bytes)).use { readBounded(it, limit) }
    }.getOrNull()

    private fun inspectElfHeader(bytes: ByteArray): String {
        if (bytes.size < 20 || bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) return "libapp.so entry is not a recognizable ELF header"
        val elfClass = when (bytes[4].toInt() and 0xFF) {
            1 -> "ELF32"
            2 -> "ELF64"
            else -> "ELF?"
        }
        val order = if ((bytes[5].toInt() and 0xFF) == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val machine = ByteBuffer.wrap(bytes, 18, 2).order(order).short.toInt() and 0xFFFF
        val architecture = when (machine) {
            3 -> "x86"
            40 -> "ARM"
            62 -> "x86_64"
            183 -> "AArch64"
            243 -> "RISC-V"
            else -> "machine=$machine"
        }
        return "$elfClass $architecture Dart AOT binary"
    }

    private fun renderReport(
        artifacts: List<ArtifactSummary>,
        previews: List<TextPreview>,
        recovered: List<RecoveredValue>,
        bytesInspected: Long,
        limitsReached: Boolean,
    ): String {
        val report = BoundedReport(MAX_REPORT_CHARS)
        report.line("/*")
        report.line(" * Flutter artifacts recovered locally from the APK")
        report.line(" *")
        report.line(" * Direct asset text below is original packaged content. Values recovered from")
        report.line(" * libapp.so/snapshots are data found in compiled Dart AOT or VM artifacts; they")
        report.line(" * are not the original Dart files, widget tree, local names, or exact control flow.")
        report.line(" * Files: ${artifacts.size}; bytes inspected: $bytesInspected; recovered values: ${recovered.size}")
        report.line(" * Limits: 64 MiB/entry, 96 MiB total, 5,000 values, 240,000 display characters.")
        if (limitsReached) report.line(" * TRUNCATED: one or more inspection/display limits were reached.")
        report.line(" */")
        report.line()
        report.line("// Artifact inventory")
        artifacts.forEach { artifact ->
            val status = if (artifact.inspected) "inspected" else "not scanned"
            val note = artifact.note?.let { " · $it" }.orEmpty()
            report.line("// ${artifact.entryName} · ${artifact.byteCount} bytes · ${artifact.role} · $status$note")
        }

        if (previews.isNotEmpty()) {
            report.line()
            report.line("// Direct packaged text assets")
            previews.forEach { preview ->
                report.line()
                report.line("// ===== ${preview.entryName}${if (preview.truncated) " (preview truncated)" else ""} =====")
                report.block(preview.text)
            }
        }

        ValueKind.entries.forEach { kind ->
            val group = recovered.filter { it.kind == kind }
            if (group.isEmpty()) return@forEach
            report.line()
            report.line("// ${kind.heading} (${group.size})")
            group.forEach { recoveredValue ->
                val locations = recoveredValue.locations.joinToString("; ") {
                    "${it.entryName}@0x${it.offset.toString(16)} ${it.encoding.label}"
                }
                report.line("// [$locations]")
                report.line(recoveredValue.value)
            }
        }
        if (report.truncated) {
            report.forceTrailer("\n// DISPLAY TRUNCATED at $MAX_REPORT_CHARS characters; extraction counts above remain authoritative.\n")
        }
        return report.toString()
    }

    private fun readBounded(input: java.io.InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024L).toInt())
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw SizeLimitExceeded("entry exceeded its $limit-byte read budget")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun abiFromEntry(name: String): String = name.removePrefix("lib/").substringBefore('/')

    private class SizeLimitExceeded(override val message: String) : Exception(message)

    private class BoundedReport(private val limit: Int) {
        private val builder = StringBuilder(minOf(limit, 32_768))
        var truncated: Boolean = false
            private set

        fun line(value: String = "") {
            append(value)
            append("\n")
        }

        fun block(value: String) = append(value).also { append("\n") }

        private fun append(value: String) {
            if (builder.length >= limit) {
                truncated = true
                return
            }
            val remaining = limit - builder.length
            if (value.length > remaining) truncated = true
            builder.append(value, 0, minOf(value.length, remaining))
        }

        fun forceTrailer(value: String) {
            if (value.length >= limit) {
                builder.setLength(0)
                builder.append(value.takeLast(limit))
                return
            }
            if (builder.length + value.length > limit) builder.setLength(limit - value.length)
            builder.append(value)
        }

        override fun toString(): String = builder.toString()
    }

    private val LEGACY_SNAPSHOT_ENTRIES = setOf(
        "assets/vm_snapshot_data",
        "assets/vm_snapshot_instr",
        "assets/isolate_snapshot_data",
        "assets/isolate_snapshot_instr",
        "assets/flutter_assets/vm_snapshot_data",
        "assets/flutter_assets/vm_snapshot_instr",
        "assets/flutter_assets/isolate_snapshot_data",
        "assets/flutter_assets/isolate_snapshot_instr",
    )
    private val TEXT_EXTENSIONS = setOf(
        ".json", ".arb", ".yaml", ".yml", ".txt", ".xml", ".html", ".htm", ".js",
        ".css", ".md", ".csv", ".ini", ".properties", ".env", ".dart", ".frag", ".vert", ".glsl",
    )
    private val BINARY_ASSET_EXTENSIONS = setOf(
        ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".ico", ".ttf", ".otf", ".woff", ".woff2",
        ".mp3", ".wav", ".ogg", ".aac", ".m4a", ".mp4", ".webm", ".mov", ".avi", ".pdf", ".zip",
        ".gz", ".br", ".wasm", ".so", ".dex", ".riv", ".flr", ".spv",
    )
    private const val COMMON_PUNCTUATION = "_-.:/@+<>()[]{}=,;!?%#&*'\"\\|$`~"
    private val URL_PATTERN = Regex("""(?i)\b(?:https?|wss?|ftp)://[^\s\u0000]+""")
    private val ASSET_EXTENSION_PATTERN = Regex("""(?i).+\.(?:png|jpe?g|webp|svg|gif|ttf|otf|json|arb|yaml|mp3|wav|mp4|riv|flr)$""")
    private val SYMBOL_PATTERN = Regex("""[A-Za-z_$<][A-Za-z0-9_$<>.:/@+\-]{3,}""")
}
