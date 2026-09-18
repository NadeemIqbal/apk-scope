package com.nadeem.apkscope.core.staticanalysis

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Milestone 10 (Security Audit), Phase 10.3, MS10-NET01 correction: real `resources.arsc` (compiled
 * resource table) parsing, so a resource reference like `android:networkSecurityConfig="@0x7f180001"`
 * (the raw form [BinaryXmlParser] already extracts from the manifest — see its attribute-value
 * formatting for `dataType == 1`, `TYPE_REFERENCE`) can be resolved to the actual file path inside the
 * APK (`res/xml/network_security_config.xml`) rather than treating attribute *presence* alone as
 * completed analysis.
 *
 * Format reference: AOSP `frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h`
 * (`ResTable_header`, `ResTable_package`, `ResTable_typeSpec`, `ResTable_type`, `ResTable_entry`,
 * `Res_value`, `ResStringPool_header`) — this is the same well-documented binary format
 * `AndroidManifest.xml` itself uses for its string pool, just wrapping a different top-level chunk
 * type (`RES_TABLE_TYPE = 0x0002`, not `RES_XML_TYPE`).
 *
 * **Documented supported subset** (Section 3's own explicit allowance to report gaps rather than
 * guess): exactly one package matching the resource id's package byte; a *simple* (non-bag) entry
 * whose value is `TYPE_STRING` (a direct file path) in the table's global string pool; *dense* entry
 * arrays only (not the sparse-entry format some newer AAPT2 output uses); and, when a type id has
 * more than one `ResTable_type` chunk (multiple density/locale/etc. configuration variants), only the
 * fully-default (all-zero) configuration variant is resolved — anything else is reported as
 * [ResolveResult.Unsupported], never silently guessed at or silently treated as absent.
 */
object ResourceTableParser {

 sealed interface ResolveResult {
  /** The resolved zip-entry path, e.g. `res/xml/network_security_config.xml`. */
  data class Resolved(val path: String) : ResolveResult
  /** No entry exists at this resource id (e.g. `NO_ENTRY` in a sparse offset table) — a real, supported "absent" answer, not a parse limitation. */
  data object NotFound : ResolveResult
  /** This resource table (or this specific resource) uses a real, valid variant this parser does not attempt to interpret — named explicitly rather than silently misreported as absent or guessed at. */
  data class Unsupported(val reason: String) : ResolveResult
  /** The `resources.arsc` bytes themselves could not be read as a valid resource table at all. */
  data class ParseFailed(val reason: String) : ResolveResult
 }

 private const val RES_STRING_POOL_TYPE = 0x0001
 private const val RES_TABLE_TYPE = 0x0002
 private const val RES_TABLE_PACKAGE_TYPE = 0x0200
 private const val RES_TABLE_TYPE_SPEC_TYPE = 0x0202
 private const val RES_TABLE_TYPE_TYPE = 0x0201
 private const val UTF8_FLAG = 0x100
 private const val FLAG_SPARSE = 0x01
 private const val FLAG_COMPLEX = 0x0001
 private const val TYPE_STRING = 0x03
 private const val NO_ENTRY = -1 // 0xFFFFFFFF as a signed Int

 /**
  * @param resourceId the raw resource id, e.g. `0x7f180001` — package byte (bits 31-24), 1-based type
  * id (bits 23-16), 0-based entry id (bits 15-0). This is exactly what [BinaryXmlParser] formats as
  * `"@0x...".removePrefix("@0x").toInt(16)` for a `TYPE_REFERENCE` manifest attribute.
  */
 fun resolveFileResource(arscBytes: ByteArray, resourceId: Int): ResolveResult {
  if (arscBytes.size < 12) return ResolveResult.ParseFailed("resources.arsc is too small to contain a valid ResTable_header")
  val buffer = ByteBuffer.wrap(arscBytes).order(ByteOrder.LITTLE_ENDIAN)
  return try {
   val chunkType = buffer.getShort(0).toInt() and 0xFFFF
   if (chunkType != RES_TABLE_TYPE) return ResolveResult.ParseFailed("expected RES_TABLE_TYPE (0x0002) at offset 0, found 0x${chunkType.toString(16)}")
   val headerSize = buffer.getShort(2).toInt() and 0xFFFF

   val targetPackageId = (resourceId ushr 24) and 0xFF
   val targetTypeId1Based = (resourceId ushr 16) and 0xFF
   val targetEntryId = resourceId and 0xFFFF

   // The global string pool is always the first chunk after the header, per the format spec — file
   // resources' TYPE_STRING values index into this pool, not the package's own key-strings pool.
   var globalStrings: List<String>? = null

   var pos = headerSize
   while (pos + 8 <= arscBytes.size) {
    val cType = buffer.getShort(pos).toInt() and 0xFFFF
    val cSize = buffer.getInt(pos + 4)
    if (cSize <= 0 || pos + cSize > arscBytes.size) break
    when (cType) {
     RES_STRING_POOL_TYPE -> if (globalStrings == null) globalStrings = readStringPool(buffer, pos)
     RES_TABLE_PACKAGE_TYPE -> {
      val pkgId = buffer.getInt(pos + 8) and 0xFF
      if (pkgId == targetPackageId) {
       val strings = globalStrings ?: return ResolveResult.ParseFailed("package chunk encountered before the global string pool — unexpected chunk order")
       return resolveWithinPackage(buffer, pos, cSize, strings, targetTypeId1Based, targetEntryId)
      }
     }
    }
    pos += cSize
   }
   ResolveResult.NotFound
  } catch (e: Exception) {
   ResolveResult.ParseFailed("${e.javaClass.simpleName}: ${e.message}")
  }
 }

 private fun resolveWithinPackage(
  buffer: ByteBuffer,
  pkgOff: Int,
  pkgSize: Int,
  globalStrings: List<String>,
  targetTypeId1Based: Int,
  targetEntryId: Int,
 ): ResolveResult {
  // ResTable_package: header(8) + id(4) + name[128 char16_t](256) = offset 268, then
  // typeStrings(268)/lastPublicType(272)/keyStrings(276)/lastPublicKey(280), each a uint32.
  val typeStringsOffsetField = buffer.getInt(pkgOff + 12 + 256)
  val keyStringsOffsetField = buffer.getInt(pkgOff + 12 + 256 + 8)
  val typeStringsAbs = pkgOff + typeStringsOffsetField
  val keyStringsAbs = pkgOff + keyStringsOffsetField
  val typeStringsSize = buffer.getInt(typeStringsAbs + 4)
  val keyStrings = readStringPool(buffer, keyStringsAbs)
  val keyStringsSize = buffer.getInt(keyStringsAbs + 4)

  // Type/typeSpec chunks follow the two string pools; there may be several ResTable_type chunks for
  // the same type id (one per configuration variant) — collect them all, then apply this parser's
  // documented "default config only" support rule.
  val matchingTypeChunks = mutableListOf<Int>() // offsets of ResTable_type chunks for the target type id
  var pos = keyStringsAbs + keyStringsSize
  val pkgEnd = pkgOff + pkgSize
  while (pos + 8 <= pkgEnd) {
   val cType = buffer.getShort(pos).toInt() and 0xFFFF
   val cSize = buffer.getInt(pos + 4)
   if (cSize <= 0 || pos + cSize > pkgEnd) break
   if (cType == RES_TABLE_TYPE_TYPE) {
    val typeId = buffer.get(pos + 8).toInt() and 0xFF
    if (typeId == targetTypeId1Based) matchingTypeChunks.add(pos)
   }
   pos += cSize
  }
  if (matchingTypeChunks.isEmpty()) return ResolveResult.NotFound

  val chosen = if (matchingTypeChunks.size == 1) {
   matchingTypeChunks[0]
  } else {
   // Multiple configuration variants exist for this type id (density/locale/API-level qualified
   // resource folders, e.g. res/xml-v24/...). This parser only resolves the fully-default
   // (all-zero) config — anything else is a real, named gap, not a silent guess.
   val defaultConfigChunk = matchingTypeChunks.firstOrNull { isDefaultConfig(buffer, it) }
    ?: return ResolveResult.Unsupported(
     "resource type has ${matchingTypeChunks.size} configuration-qualified variants (e.g. density/locale/API-level) and none is the fully-default config — this parser resolves only the default-config variant",
    )
   defaultConfigChunk
  }

  return readEntryFromTypeChunk(buffer, chosen, targetEntryId, globalStrings)
 }

 private fun isDefaultConfig(buffer: ByteBuffer, typeChunkOff: Int): Boolean {
  val configSize = buffer.getInt(typeChunkOff + 20)
  // A fully-default ResTable_config is all zero bytes after its own size field (mcc/mnc/locale/
  // orientation/density/etc. all unset) — checking every byte is simpler and just as correct as
  // naming each field individually, and doesn't need to track ResTable_config's field layout, which
  // has grown across API levels.
  for (i in 4 until configSize) {
   if (buffer.get(typeChunkOff + 20 + i).toInt() != 0) return false
  }
  return true
 }

 private fun readEntryFromTypeChunk(buffer: ByteBuffer, typeChunkOff: Int, targetEntryId: Int, globalStrings: List<String>): ResourceTableParser.ResolveResult {
  // ResTable_type: header(8) + id(1)@8 + flags(1)@9 + reserved(2)@10-11 + entryCount(4)@12 + entriesStart(4)@16 + config@20.
  val flags = buffer.get(typeChunkOff + 9).toInt() and 0xFF
  val entryCount = buffer.getInt(typeChunkOff + 12)
  val entriesStart = buffer.getInt(typeChunkOff + 16)
  if (targetEntryId >= entryCount && (flags and FLAG_SPARSE) == 0) return ResolveResult.NotFound

  val entryOffset: Int
  if ((flags and FLAG_SPARSE) != 0) {
   // Sparse format: offsets array is (entryIdx: u16, offset/4: u16) pairs, only for entries that
   // exist, sorted by entryIdx — a real, valid format this parser does not walk, per its documented
   // supported subset.
   return ResolveResult.Unsupported("resource type uses the sparse entry-offset format, not supported")
  } else {
   val configSize = buffer.getInt(typeChunkOff + 20)
   val offsetsAbs = typeChunkOff + 20 + configSize
   val rawOffset = buffer.getInt(offsetsAbs + targetEntryId * 4)
   if (rawOffset == NO_ENTRY) return ResolveResult.NotFound
   entryOffset = typeChunkOff + entriesStart + rawOffset
  }

  val entrySize = buffer.getShort(entryOffset).toInt() and 0xFFFF
  val entryFlags = buffer.getShort(entryOffset + 2).toInt() and 0xFFFF
  if ((entryFlags and FLAG_COMPLEX) != 0) {
   return ResolveResult.Unsupported("resource entry is a complex/bag entry (e.g. a style or array), not a simple file/value entry")
  }
  val valueOff = entryOffset + entrySize
  val dataType = buffer.get(valueOff + 3).toInt() and 0xFF
  val data = buffer.getInt(valueOff + 4)
  if (dataType != TYPE_STRING) {
   return ResolveResult.Unsupported("resource entry's value is not a direct file-path string (dataType=0x${dataType.toString(16)}, e.g. a reference/alias to another resource) — not supported")
  }
  val path = globalStrings.getOrNull(data)
   ?: return ResolveResult.ParseFailed("value string-pool index $data out of range (pool size ${globalStrings.size})")
  return ResolveResult.Resolved(path)
 }

 /** Minimal `ResStringPool` reader — same format `BinaryXmlParser` parses for the manifest's own string pool, reimplemented here (not shared) since this operates on a `ByteBuffer`-relative-offset model rather than that parser's list-based one, and resources.arsc string pools are read far less often, not worth forcing a shared abstraction across two independently-verified parsers. */
 private fun readStringPool(buffer: ByteBuffer, poolOff: Int): List<String> {
  val stringCount = buffer.getInt(poolOff + 8)
  val flags = buffer.getInt(poolOff + 16)
  val stringsStart = buffer.getInt(poolOff + 20)
  val isUtf8 = (flags and UTF8_FLAG) != 0
  val result = ArrayList<String>(stringCount)
  for (i in 0 until stringCount) {
   val relOffset = buffer.getInt(poolOff + 28 + i * 4)
   val stringAbs = poolOff + stringsStart + relOffset
   result.add(
    if (isUtf8) readUtf8PoolString(buffer, stringAbs) else readUtf16PoolString(buffer, stringAbs)
   )
  }
  return result
 }

 private fun readUtf8PoolString(buffer: ByteBuffer, off: Int): String {
  // UTF-8 pool strings encode both a char-length and a byte-length, each as one byte normally or two
  // bytes if the high bit is set (a 15-bit length) — the char-length is skipped, only byte-length is
  // needed to know how many bytes to decode.
  var pos = off
  val first = buffer.get(pos).toInt() and 0xFF
  pos += if (first and 0x80 != 0) 2 else 1
  val lenFirstByte = buffer.get(pos).toInt() and 0xFF
  val byteLen: Int
  if (lenFirstByte and 0x80 != 0) {
   val lenSecondByte = buffer.get(pos + 1).toInt() and 0xFF
   byteLen = ((lenFirstByte and 0x7F) shl 8) or lenSecondByte
   pos += 2
  } else {
   byteLen = lenFirstByte
   pos += 1
  }
  val bytes = ByteArray(byteLen)
  val dup = buffer.duplicate()
  dup.position(pos)
  dup.get(bytes)
  return String(bytes, Charsets.UTF_8)
 }

 private fun readUtf16PoolString(buffer: ByteBuffer, off: Int): String {
  var pos = off
  val firstUnit = buffer.getShort(pos).toInt() and 0xFFFF
  val charLen: Int
  if (firstUnit and 0x8000 != 0) {
   val secondUnit = buffer.getShort(pos + 2).toInt() and 0xFFFF
   charLen = ((firstUnit and 0x7FFF) shl 16) or secondUnit
   pos += 4
  } else {
   charLen = firstUnit
   pos += 2
  }
  val chars = CharArray(charLen)
  for (i in 0 until charLen) chars[i] = buffer.getShort(pos + i * 2).toInt().toChar()
  return String(chars)
 }
}
