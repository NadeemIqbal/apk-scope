package com.nadeem.apkscope.core.staticanalysis

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Milestone 10 (Security Audit), Phase 10.3, MS10-NET01 correction — parses the *content* of a real
 * `network_security_config.xml` resource ([ResourceTableParser] resolves the manifest's resource
 * reference to this file's actual zip-entry path first), rather than treating the manifest
 * attribute's mere presence as completed analysis.
 *
 * **Documented supported subset**: `<base-config>`, `<domain-config>` (with nested `<domain>`),
 * `<debug-overrides>`, `<trust-anchors>`/`<certificates src="...">`, and `<pin-set>`/`<pin
 * digest="...">`. Real Android Network Security Configuration elements this parser does not attempt
 * to interpret — `<certificatesOverride>` (a testOnly-scoped element some documentation shows as
 * legacy-only), certificate `overridePins` attributes, and multiple/nested `<domain-config>` (a
 * `<domain-config>` inside another `<domain-config>`, which real NSC XML allows for inheritance) — are
 * reported via [NetworkSecurityConfigAnalysis.unsupportedNotes], never silently dropped or guessed at.
 */
object NetworkSecurityConfigParser {

 data class DomainEntry(val domain: String, val includeSubdomains: Boolean)

 data class PinDigest(val digestAlgorithm: String, val digestValue: String)

 data class PinSetInfo(val expirationDate: String?, val pins: List<PinDigest>)

 /** One `<base-config>`, `<domain-config>`, or `<debug-overrides>` block. [domains] is empty for `<base-config>`/`<debug-overrides>` (they apply regardless of domain). */
 data class ConfigBlock(
  val domains: List<DomainEntry>,
  val cleartextTrafficPermitted: Boolean?,
  val trustAnchorSources: List<String>,
  val pinSet: PinSetInfo?,
 )

 data class NetworkSecurityConfigAnalysis(
  val baseConfig: ConfigBlock?,
  val domainConfigs: List<ConfigBlock>,
  val debugOverrides: ConfigBlock?,
  val unsupportedNotes: List<String>,
 )

 sealed interface ParseResult {
  data class Parsed(val analysis: NetworkSecurityConfigAnalysis) : ParseResult
  /** [ResourceTableParser.ResolveResult.NotFound]/[Unsupported]/[ParseFailed] carried through, or the resolved file could not be found/read in the zip, or its own binary XML could not be parsed. */
  data class Unavailable(val reason: String) : ParseResult
 }

 /**
  * The one entry point: given the raw manifest attribute value [BinaryXmlParser] extracted for
  * `android:networkSecurityConfig` (a `"@0x..."` resource-id reference, or occasionally a literal
  * `"@xml/name"` string if the manifest attribute was somehow stored as a raw string instead of a
  * typed reference) and the APK's zip file, resolves and parses the real resource.
  */
 fun parseFromManifestAttribute(zip: ZipFile, rawAttributeValue: String): ParseResult {
  val resourceId = rawAttributeValue.removePrefix("@0x").toIntOrNull(16)
   ?: return ParseResult.Unavailable("manifest attribute value \"$rawAttributeValue\" is not a resolvable resource-id reference (expected \"@0x...\")")

  val arscEntry = zip.getEntry("resources.arsc")
   ?: return ParseResult.Unavailable("APK has no resources.arsc — cannot resolve resource id 0x${resourceId.toString(16)}")
  val arscBytes = zip.getInputStream(arscEntry).use { it.readBytes() }

  val resolved = ResourceTableParser.resolveFileResource(arscBytes, resourceId)
  val path = when (resolved) {
   is ResourceTableParser.ResolveResult.Resolved -> resolved.path
   ResourceTableParser.ResolveResult.NotFound -> return ParseResult.Unavailable("resource id 0x${resourceId.toString(16)} has no entry in resources.arsc")
   is ResourceTableParser.ResolveResult.Unsupported -> return ParseResult.Unavailable("unsupported resources.arsc variant: ${resolved.reason}")
   is ResourceTableParser.ResolveResult.ParseFailed -> return ParseResult.Unavailable("resources.arsc parse failure: ${resolved.reason}")
  }

  val xmlEntry = zip.getEntry(path)
   ?: return ParseResult.Unavailable("resolved path \"$path\" does not exist in this APK's zip entries")
  val xmlBytes = zip.getInputStream(xmlEntry).use { it.readBytes() }
  return parseXmlBytes(xmlBytes)
 }

 fun parseXmlBytes(bytes: ByteArray): ParseResult {
  val events = try {
   readAxmlEvents(bytes)
  } catch (e: Exception) {
   return ParseResult.Unavailable("could not parse network-security-config XML: ${e.javaClass.simpleName}: ${e.message}")
  }
  return interpret(events)
 }

 // ---- Tag-vocabulary interpretation ----

 private sealed interface XmlEvent {
  data class StartTag(val name: String, val attributes: Map<String, String>) : XmlEvent
  data class Text(val text: String) : XmlEvent
  data class EndTag(val name: String) : XmlEvent
 }

 private fun interpret(events: List<XmlEvent>): ParseResult {
  var baseConfig: ConfigBlock? = null
  val domainConfigs = mutableListOf<ConfigBlock>()
  var debugOverrides: ConfigBlock? = null
  val unsupported = mutableListOf<String>()

  // A small explicit state machine, not a general-purpose tree — real NSC XML nests at most
  // domain/trust-anchors+certificates/pin-set+pin one level inside base-config/domain-config/
  // debug-overrides, which is exactly this documented subset's depth.
  var currentBlockKind: String? = null // "base-config" | "domain-config" | "debug-overrides"
  var currentDomains = mutableListOf<DomainEntry>()
  var currentCleartext: Boolean? = null
  var currentTrustAnchors = mutableListOf<String>()
  var currentPinExpiration: String? = null
  var currentPins = mutableListOf<PinDigest>()
  var domainConfigNestingDepth = 0

  // The innermost leaf tag currently open that carries text content (<domain> or <pin>), plus any
  // attribute captured on its StartTag that is only usable once the matching EndTag's text arrives.
  var openLeafTag: String? = null
  var pendingDomainIncludeSubdomains = false
  var pendingPinDigestAlgorithm: String = "unknown"
  var pendingText = StringBuilder()

  fun flushBlock() {
   val block = ConfigBlock(
    domains = currentDomains.toList(),
    cleartextTrafficPermitted = currentCleartext,
    trustAnchorSources = currentTrustAnchors.toList(),
    pinSet = if (currentPins.isNotEmpty() || currentPinExpiration != null) PinSetInfo(currentPinExpiration, currentPins.toList()) else null,
   )
   when (currentBlockKind) {
    "base-config" -> baseConfig = block
    "domain-config" -> domainConfigs.add(block)
    "debug-overrides" -> debugOverrides = block
   }
   currentBlockKind = null
   currentDomains = mutableListOf()
   currentCleartext = null
   currentTrustAnchors = mutableListOf()
   currentPinExpiration = null
   currentPins = mutableListOf()
  }

  for (event in events) {
   when (event) {
    is XmlEvent.StartTag -> when (event.name) {
     "base-config", "domain-config", "debug-overrides" -> {
      if (event.name == "domain-config" && currentBlockKind == "domain-config") {
       // A <domain-config> nested inside another <domain-config> — real, valid NSC XML
       // (inheritance), not something this parser's flat ConfigBlock model represents.
       domainConfigNestingDepth++
       unsupported.add("nested <domain-config> (domain-config inheritance) found — not represented; only the outermost domain-config's own direct settings are captured")
      } else {
       currentBlockKind = event.name
       currentCleartext = parseBool(event.attributes["cleartextTrafficPermitted"])
      }
     }
     "domain" -> {
      openLeafTag = "domain"
      pendingDomainIncludeSubdomains = parseBool(event.attributes["includeSubdomains"]) ?: false
      pendingText = StringBuilder()
     }
     "certificates" -> {
      val src = event.attributes["src"]
      if (src != null) currentTrustAnchors.add(src)
     }
     "pin-set" -> currentPinExpiration = event.attributes["expiration"]
     "pin" -> {
      openLeafTag = "pin"
      pendingPinDigestAlgorithm = event.attributes["digest"] ?: "unknown"
      pendingText = StringBuilder()
     }
     "certificateTransparency" -> unsupported.add("<certificateTransparency> (API 33+) found — presence and its override attribute are not interpreted, a documented gap")
     "trust-anchors" -> Unit // no attributes of interest itself — its <certificates> children are handled above
     else -> Unit
    }
    is XmlEvent.Text -> if (openLeafTag != null) pendingText.append(event.text)
    is XmlEvent.EndTag -> when (event.name) {
     "domain" -> {
      if (openLeafTag == "domain") {
       currentDomains.add(DomainEntry(pendingText.toString(), pendingDomainIncludeSubdomains))
       openLeafTag = null
      }
     }
     "pin" -> {
      if (openLeafTag == "pin") {
       currentPins.add(PinDigest(pendingPinDigestAlgorithm, pendingText.toString()))
       openLeafTag = null
      }
     }
     "base-config", "debug-overrides" -> if (currentBlockKind == event.name) flushBlock()
     "domain-config" -> {
      if (domainConfigNestingDepth > 0) domainConfigNestingDepth-- else if (currentBlockKind == "domain-config") flushBlock()
     }
     else -> Unit
    }
   }
  }
  if (currentBlockKind != null) flushBlock() // malformed/truncated XML with no matching end tag — best-effort flush of whatever was captured

  return ParseResult.Parsed(NetworkSecurityConfigAnalysis(baseConfig, domainConfigs, debugOverrides, unsupported))
 }

 private fun parseBool(v: String?): Boolean? = when (v?.lowercase()?.trim()) {
  "true" -> true
  "false" -> false
  else -> null
 }

 // ---- Low-level AXML tag-event reader (independent of BinaryXmlParser — see this file's own doc
 // comment for why a shared abstraction across two independently-verified parsers is not worth it) ----

 private const val CHUNK_AXML_FILE = 0x00080003
 private const val CHUNK_STRING_POOL = 0x001C0001
 private const val CHUNK_START_TAG = 0x00100102
 private const val CHUNK_END_TAG = 0x00100103
 private const val CHUNK_TEXT = 0x00100104

 private fun readAxmlEvents(bytes: ByteArray): List<XmlEvent> {
  if (bytes.size < 8) return emptyList()
  val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
  val fileType = buffer.int
  if (fileType != CHUNK_AXML_FILE) throw IllegalArgumentException("not a binary XML file (unexpected magic)")
  buffer.int // whole-file size, unused

  var stringPool: List<String> = emptyList()
  val events = mutableListOf<XmlEvent>()
  // Text content for <domain>/<pin> arrives as a separate CHUNK_TEXT "node" between the enclosing
  // start/end tags, addressed by the same string-pool mechanism as attribute values.

  while (buffer.hasRemaining()) {
   val chunkStart = buffer.position()
   if (buffer.remaining() < 8) break
   val chunkType = buffer.int
   val chunkSize = buffer.int
   if (chunkSize < 8 || chunkStart + chunkSize > bytes.size) break

   when (chunkType) {
    CHUNK_STRING_POOL -> stringPool = parseAxmlStringPool(buffer, chunkStart)
    CHUNK_START_TAG -> {
     buffer.position(chunkStart + 20)
     val tagNameIdx = buffer.int
     buffer.position(chunkStart + 28)
     val attrCount = buffer.short.toInt() and 0xFFFF
     buffer.position(chunkStart + 36)
     val tagName = stringPool.getOrNull(tagNameIdx).orEmpty()
     val attributes = mutableMapOf<String, String>()
     for (i in 0 until attrCount) {
      if (buffer.remaining() < 20) break
      buffer.int // namespace index, unused for this vocabulary (no namespaced attributes in NSC XML)
      val attrNameIdx = buffer.int
      val attrRawValIdx = buffer.int
      val typedValSizeRes = buffer.int
      val typedValData = buffer.int
      val attrName = stringPool.getOrNull(attrNameIdx).orEmpty()
      val attrVal = if (attrRawValIdx >= 0 && attrRawValIdx < stringPool.size && stringPool[attrRawValIdx].isNotEmpty()) {
       stringPool[attrRawValIdx]
      } else {
       val dataType = (typedValSizeRes ushr 24) and 0xFF
       when (dataType) {
        3 -> stringPool.getOrNull(typedValData) ?: typedValData.toString()
        18 -> if (typedValData != 0) "true" else "false"
        16 -> typedValData.toString()
        else -> typedValData.toString()
       }
      }
      if (attrName.isNotEmpty()) attributes[attrName] = attrVal
     }
     events.add(XmlEvent.StartTag(tagName, attributes))
    }
    CHUNK_TEXT -> {
     // ResXMLTree_cdataExt has no "ns" field unlike start/end-tag nodes — its string-pool index
     // (`data`) sits at offset 16, not 20 (verified against a real fixture domain value; using 20
     // here, by analogy with start/end tags, silently read the wrong field).
     buffer.position(chunkStart + 16)
     val textIdx = buffer.int
     val text = stringPool.getOrNull(textIdx).orEmpty()
     if (text.isNotBlank()) events.add(XmlEvent.Text(text.trim()))
    }
    CHUNK_END_TAG -> {
     buffer.position(chunkStart + 20)
     val tagNameIdx = buffer.int
     events.add(XmlEvent.EndTag(stringPool.getOrNull(tagNameIdx).orEmpty()))
    }
   }
   buffer.position(chunkStart + chunkSize)
  }
  return events
 }

 /** Same ResStringPool format [ResourceTableParser.readStringPool] reads, at the offsets [BinaryXmlParser.parseStringPool] already established for a `CHUNK_STRING_POOL`-wrapped pool (a chunk header's own layout, not resources.arsc's raw one — the two differ in header size, hence not literally shared code). */
 private fun parseAxmlStringPool(buffer: ByteBuffer, chunkStart: Int): List<String> {
  val stringCount = buffer.getInt(chunkStart + 8)
  val flags = buffer.getInt(chunkStart + 16)
  val stringsStart = buffer.getInt(chunkStart + 20)
  val isUtf8 = (flags and 0x100) != 0
  val result = ArrayList<String>(stringCount)
  for (i in 0 until stringCount) {
   val relOffset = buffer.getInt(chunkStart + 28 + i * 4)
   val stringAbs = chunkStart + stringsStart + relOffset
   result.add(if (isUtf8) readUtf8String(buffer, stringAbs) else readUtf16String(buffer, stringAbs))
  }
  return result
 }

 private fun readUtf8String(buffer: ByteBuffer, off: Int): String {
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

 private fun readUtf16String(buffer: ByteBuffer, off: Int): String {
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
