package com.nadeem.apkscope.core.staticanalysis

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lightweight, robust Android Binary XML (AXML) parser and decompiler.
 * Extracts:
 * - Application components (`<activity>`, `<service>`, `<receiver>`, `<provider>`), exported states, permissions, and intent-filters.
 * - Key-value metadata declarations (`<meta-data>`) such as API keys, secrets, SDK tokens.
 * - Network security configuration, cleartext traffic allowance, and sandbox security flags.
 * - Hardware and feature requirements (`<uses-feature>`).
 * - Full formatted, indented, human-readable XML string (`AndroidManifest.xml`).
 */
object BinaryXmlParser {

    private const val CHUNK_AXML_FILE = 0x00080003
    private const val CHUNK_STRING_POOL = 0x001C0001
    private const val CHUNK_RESOURCE_IDS = 0x00080180
    private const val CHUNK_START_NAMESPACE = 0x00100100
    private const val CHUNK_END_NAMESPACE = 0x00100101
    private const val CHUNK_START_TAG = 0x00100102
    private const val CHUNK_END_TAG = 0x00100103
    private const val CHUNK_TEXT = 0x00100104

    data class ParsedComponentInfo(
        val name: String,
        val type: ComponentDescriptor.ComponentType,
        val exported: Boolean,
        val permission: String?,
        val intentFilters: List<IntentFilterDescriptor>,
    )

    data class ManifestMetaData(
        val name: String,
        val value: String?,
        val resourceId: String? = null,
        val parentTag: String? = null,
    )

    data class ManifestFeature(
        val name: String,
        val required: Boolean = true,
        val glEsVersion: String? = null,
    )

    data class ManifestConfig(
        val packageName: String? = null,
        val versionCode: Long? = null,
        val versionName: String? = null,
        val minSdkVersion: Int? = null,
        val targetSdkVersion: Int? = null,
        val compileSdkVersion: Int? = null,
        val sharedUserId: String? = null,
        val networkSecurityConfig: String? = null,
        val usesCleartextTraffic: Boolean? = null,
        val debuggable: Boolean? = null,
        val allowBackup: Boolean? = null,
        val testOnly: Boolean? = null,
        val extractNativeLibs: Boolean? = null,
        val requestLegacyExternalStorage: Boolean? = null,
        val supportsRtl: Boolean? = null,
        val hardwareAccelerated: Boolean? = null,
        val appTheme: String? = null,
        val appLabel: String? = null,
        val appIcon: String? = null,
        val fullBackupContent: String? = null,
        val dataExtractionRules: String? = null,
        val metaData: List<ManifestMetaData> = emptyList(),
        val features: List<ManifestFeature> = emptyList(),
        val permissions: List<String> = emptyList(),
    )

    data class ManifestParseResult(
        val components: List<ParsedComponentInfo>,
        val config: ManifestConfig,
        val rawXml: String,
    )

    private data class TagFrame(
        val name: String,
        var hasChildren: Boolean = false,
    )

    fun parseManifest(inputStream: InputStream): List<ParsedComponentInfo> {
        return parseManifestFull(inputStream).components
    }

    fun parseManifestFull(inputStream: InputStream): ManifestParseResult {
        val bytes = inputStream.readBytes()
        if (bytes.size < 8) {
            return ManifestParseResult(emptyList(), ManifestConfig(), "")
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val fileType = buffer.int
        if (fileType != CHUNK_AXML_FILE) {
            return ManifestParseResult(emptyList(), ManifestConfig(), "")
        }
        val fileSize = buffer.int

        var stringPool: List<String> = emptyList()
        val uriToPrefix = mutableMapOf<String, String>()
        val prefixToUri = mutableMapOf<String, String>()
        val rootNamespaces = mutableMapOf<String, String>()

        val components = mutableListOf<ParsedComponentInfo>()
        val metaDataList = mutableListOf<ManifestMetaData>()
        val featuresList = mutableListOf<ManifestFeature>()
        val permissionsList = mutableListOf<String>()

        var pkgName: String? = null
        var verCode: Long? = null
        var verName: String? = null
        var minSdk: Int? = null
        var targetSdk: Int? = null
        var compileSdk: Int? = null
        var sharedUser: String? = null
        var netSecConfig: String? = null
        var cleartextTraffic: Boolean? = null
        var isDebuggable: Boolean? = null
        var isAllowBackup: Boolean? = null
        var isTestOnly: Boolean? = null
        var isExtractNativeLibs: Boolean? = null
        var isLegacyStorage: Boolean? = null
        var isRtl: Boolean? = null
        var isHwAccelerated: Boolean? = null
        var themeRef: String? = null
        var labelRef: String? = null
        var iconRef: String? = null
        var backupContent: String? = null
        var extractionRules: String? = null

        // Temporary state during XML traversal
        var currentComponentType: ComponentDescriptor.ComponentType? = null
        var currentComponentName: String? = null
        var currentComponentExported: Boolean? = null
        var currentComponentPermission: String? = null
        val currentComponentFilters = mutableListOf<IntentFilterDescriptor>()

        var inIntentFilter = false
        val currentActions = mutableListOf<String>()
        val currentCategories = mutableListOf<String>()
        val currentDataSchemes = mutableListOf<String>()

        // XML String reconstruction
        val xmlBuilder = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        val tagStack = ArrayDeque<TagFrame>()
        var isFirstTag = true

        fun parseBool(v: String?): Boolean? = when (v?.lowercase()?.trim()) {
            "true", "1", "-1", "4294967295", "0xffffffff" -> true
            "false", "0" -> false
            else -> null
        }

        while (buffer.hasRemaining()) {
            val chunkStart = buffer.position()
            if (buffer.remaining() < 8) break
            val chunkType = buffer.int
            val chunkSize = buffer.int
            if (chunkSize < 8 || chunkStart + chunkSize > bytes.size) break

            when (chunkType) {
                CHUNK_STRING_POOL -> {
                    stringPool = parseStringPool(buffer, chunkStart, chunkSize)
                }

                CHUNK_START_NAMESPACE -> {
                    buffer.position(chunkStart + 16)
                    val prefixIdx = buffer.int
                    val uriIdx = buffer.int
                    val prefix = stringPool.getOrNull(prefixIdx).orEmpty()
                    val uri = stringPool.getOrNull(uriIdx).orEmpty()
                    if (prefix.isNotEmpty() && uri.isNotEmpty()) {
                        uriToPrefix[uri] = prefix
                        prefixToUri[prefix] = uri
                        rootNamespaces[prefix] = uri
                    }
                }

                CHUNK_START_TAG -> {
                    buffer.position(chunkStart + 20)
                    val tagNameIdx = buffer.int
                    buffer.position(chunkStart + 28)
                    val attrCount = buffer.short.toInt() and 0xFFFF
                    buffer.position(chunkStart + 36) // Start of attributes

                    val tagName = stringPool.getOrNull(tagNameIdx).orEmpty()
                    val attributes = mutableMapOf<String, String>()
                    val prefixedAttributes = mutableListOf<Pair<String, String>>()

                    for (i in 0 until attrCount) {
                        if (buffer.remaining() < 20) break
                        val attrNsIdx = buffer.int
                        val attrNameIdx = buffer.int
                        val attrRawValIdx = buffer.int
                        val typedValSizeRes = buffer.int
                        val typedValData = buffer.int

                        val attrName = stringPool.getOrNull(attrNameIdx).orEmpty()
                        val attrNsUri = stringPool.getOrNull(attrNsIdx).orEmpty()

                        val attrVal = if (attrRawValIdx >= 0 && attrRawValIdx < stringPool.size && stringPool[attrRawValIdx].isNotEmpty()) {
                            stringPool[attrRawValIdx]
                        } else {
                            val dataType = (typedValSizeRes ushr 24) and 0xFF
                            when (dataType) {
                                3 -> stringPool.getOrNull(typedValData) ?: typedValData.toString()
                                18 -> if (typedValData != 0) "true" else "false"
                                1 -> "@0x${Integer.toHexString(typedValData)}"
                                17 -> "0x${Integer.toHexString(typedValData)}"
                                16 -> typedValData.toString()
                                28, 29 -> String.format("#%08X", typedValData)
                                4 -> java.lang.Float.intBitsToFloat(typedValData).toString()
                                else -> if (typedValData == 0) "false" else typedValData.toString()
                            }
                        }

                        attributes[attrName] = attrVal

                        // Resolve prefix
                        val prefix = when {
                            attrNsUri.isNotEmpty() -> uriToPrefix[attrNsUri] ?: if (attrNsUri.contains("res/android")) "android" else ""
                            attrName in listOf(
                                "name", "permission", "exported", "minSdkVersion", "targetSdkVersion",
                                "versionCode", "versionName", "debuggable", "allowBackup", "networkSecurityConfig",
                                "usesCleartextTraffic", "testOnly", "extractNativeLibs", "requestLegacyExternalStorage",
                                "supportsRtl", "hardwareAccelerated", "theme", "label", "icon", "value", "resource",
                                "required", "glEsVersion", "scheme", "host", "path", "mimeType"
                            ) -> "android"
                            else -> ""
                        }
                        val prefixedName = if (prefix.isNotEmpty()) "$prefix:$attrName" else attrName
                        prefixedAttributes.add(prefixedName to attrVal)
                    }

                    // Format XML tag
                    if (tagStack.isNotEmpty()) {
                        tagStack.last().hasChildren = true
                    }
                    val indent = "    ".repeat(tagStack.size)
                    xmlBuilder.append(indent).append("<").append(tagName)

                    if (isFirstTag) {
                        isFirstTag = false
                        // Ensure standard android namespace is declared
                        if (!rootNamespaces.containsKey("android")) {
                            xmlBuilder.append("\n        xmlns:android=\"http://schemas.android.com/apk/res/android\"")
                        }
                        for ((p, u) in rootNamespaces) {
                            xmlBuilder.append("\n        xmlns:$p=\"$u\"")
                        }
                    }

                    for ((attrKey, attrValue) in prefixedAttributes) {
                        val escaped = attrValue
                            .replace("&", "&amp;")
                            .replace("<", "&lt;")
                            .replace(">", "&gt;")
                            .replace("\"", "&quot;")
                        xmlBuilder.append("\n").append(indent).append("    ").append(attrKey).append("=\"").append(escaped).append("\"")
                    }
                    xmlBuilder.append(">\n")

                    tagStack.addLast(TagFrame(name = tagName))

                    // Extract structured config based on tag
                    when (tagName) {
                        "manifest" -> {
                            pkgName = attributes["package"] ?: attributes["android:package"]
                            verCode = (attributes["versionCode"] ?: attributes["android:versionCode"])?.toLongOrNull()
                            verName = attributes["versionName"] ?: attributes["android:versionName"]
                            sharedUser = attributes["sharedUserId"] ?: attributes["android:sharedUserId"]
                            compileSdk = (attributes["compileSdkVersion"] ?: attributes["android:compileSdkVersion"])?.toIntOrNull()
                        }
                        "uses-sdk" -> {
                            minSdk = (attributes["minSdkVersion"] ?: attributes["android:minSdkVersion"])?.toIntOrNull()
                            targetSdk = (attributes["targetSdkVersion"] ?: attributes["android:targetSdkVersion"])?.toIntOrNull()
                        }
                        "uses-permission" -> {
                            val perm = attributes["name"] ?: attributes["android:name"]
                            if (!perm.isNullOrBlank()) permissionsList.add(perm)
                        }
                        "uses-feature" -> {
                            val feat = attributes["name"] ?: attributes["android:name"] ?: ""
                            val req = parseBool(attributes["required"] ?: attributes["android:required"]) ?: true
                            val gl = attributes["glEsVersion"] ?: attributes["android:glEsVersion"]
                            if (feat.isNotBlank() || gl != null) {
                                featuresList.add(ManifestFeature(name = feat, required = req, glEsVersion = gl))
                            }
                        }
                        "application" -> {
                            netSecConfig = attributes["networkSecurityConfig"] ?: attributes["android:networkSecurityConfig"]
                            cleartextTraffic = parseBool(attributes["usesCleartextTraffic"] ?: attributes["android:usesCleartextTraffic"])
                            isDebuggable = parseBool(attributes["debuggable"] ?: attributes["android:debuggable"])
                            isAllowBackup = parseBool(attributes["allowBackup"] ?: attributes["android:allowBackup"])
                            isTestOnly = parseBool(attributes["testOnly"] ?: attributes["android:testOnly"])
                            isExtractNativeLibs = parseBool(attributes["extractNativeLibs"] ?: attributes["android:extractNativeLibs"])
                            isLegacyStorage = parseBool(attributes["requestLegacyExternalStorage"] ?: attributes["android:requestLegacyExternalStorage"])
                            isRtl = parseBool(attributes["supportsRtl"] ?: attributes["android:supportsRtl"])
                            isHwAccelerated = parseBool(attributes["hardwareAccelerated"] ?: attributes["android:hardwareAccelerated"])
                            themeRef = attributes["theme"] ?: attributes["android:theme"]
                            labelRef = attributes["label"] ?: attributes["android:label"]
                            iconRef = attributes["icon"] ?: attributes["android:icon"]
                            backupContent = attributes["fullBackupContent"] ?: attributes["android:fullBackupContent"]
                            extractionRules = attributes["dataExtractionRules"] ?: attributes["android:dataExtractionRules"]
                        }
                        "meta-data" -> {
                            val mName = attributes["name"] ?: attributes["android:name"] ?: ""
                            val mVal = attributes["value"] ?: attributes["android:value"]
                            val mRes = attributes["resource"] ?: attributes["android:resource"]
                            val parent = if (tagStack.size >= 2) tagStack.toList()[tagStack.size - 2].name else "application"
                            if (mName.isNotBlank()) {
                                metaDataList.add(ManifestMetaData(name = mName, value = mVal, resourceId = mRes, parentTag = parent))
                            }
                        }
                        "activity", "activity-alias" -> {
                            currentComponentType = ComponentDescriptor.ComponentType.ACTIVITY
                            currentComponentName = attributes["name"]
                            currentComponentExported = parseBool(attributes["exported"])
                            currentComponentPermission = attributes["permission"]
                            currentComponentFilters.clear()
                        }
                        "service" -> {
                            currentComponentType = ComponentDescriptor.ComponentType.SERVICE
                            currentComponentName = attributes["name"]
                            currentComponentExported = parseBool(attributes["exported"])
                            currentComponentPermission = attributes["permission"]
                            currentComponentFilters.clear()
                        }
                        "receiver" -> {
                            currentComponentType = ComponentDescriptor.ComponentType.RECEIVER
                            currentComponentName = attributes["name"]
                            currentComponentExported = parseBool(attributes["exported"])
                            currentComponentPermission = attributes["permission"]
                            currentComponentFilters.clear()
                        }
                        "provider" -> {
                            currentComponentType = ComponentDescriptor.ComponentType.PROVIDER
                            currentComponentName = attributes["name"]
                            currentComponentExported = parseBool(attributes["exported"])
                            currentComponentPermission = attributes["permission"]
                                ?: attributes["readPermission"]
                                ?: attributes["writePermission"]
                            currentComponentFilters.clear()
                        }
                        "intent-filter" -> {
                            inIntentFilter = true
                            currentActions.clear()
                            currentCategories.clear()
                            currentDataSchemes.clear()
                        }
                        "action" -> {
                            if (inIntentFilter) attributes["name"]?.let { currentActions.add(it) }
                        }
                        "category" -> {
                            if (inIntentFilter) attributes["name"]?.let { currentCategories.add(it) }
                        }
                        "data" -> {
                            if (inIntentFilter) attributes["scheme"]?.let { currentDataSchemes.add(it) }
                        }
                    }
                }

                CHUNK_END_TAG -> {
                    buffer.position(chunkStart + 20)
                    val tagNameIdx = buffer.int
                    val tagName = stringPool.getOrNull(tagNameIdx).orEmpty()

                    if (tagStack.isNotEmpty()) {
                        tagStack.removeLast()
                    }
                    val indent = "    ".repeat(tagStack.size)
                    xmlBuilder.append(indent).append("</").append(tagName).append(">\n")

                    when (tagName) {
                        "intent-filter" -> {
                            inIntentFilter = false
                            if (currentComponentType != null && currentComponentName != null) {
                                currentComponentFilters.add(
                                    IntentFilterDescriptor(
                                        componentName = currentComponentName,
                                        actions = currentActions.toList(),
                                        categories = currentCategories.toList(),
                                        dataSchemes = currentDataSchemes.toList(),
                                    )
                                )
                            }
                        }
                        "activity", "activity-alias", "service", "receiver", "provider" -> {
                            val type = currentComponentType
                            val name = currentComponentName
                            if (type != null && !name.isNullOrBlank()) {
                                val exported = currentComponentExported
                                    ?: currentComponentFilters.isNotEmpty()

                                components.add(
                                    ParsedComponentInfo(
                                        name = name,
                                        type = type,
                                        exported = exported,
                                        permission = currentComponentPermission,
                                        intentFilters = currentComponentFilters.toList(),
                                    )
                                )
                            }
                            currentComponentType = null
                            currentComponentName = null
                            currentComponentExported = null
                            currentComponentPermission = null
                            currentComponentFilters.clear()
                        }
                    }
                }
            }

            // Advance buffer to end of this chunk
            buffer.position(chunkStart + chunkSize)
        }

        val config = ManifestConfig(
            packageName = pkgName,
            versionCode = verCode,
            versionName = verName,
            minSdkVersion = minSdk,
            targetSdkVersion = targetSdk,
            compileSdkVersion = compileSdk,
            sharedUserId = sharedUser,
            networkSecurityConfig = netSecConfig,
            usesCleartextTraffic = cleartextTraffic,
            debuggable = isDebuggable,
            allowBackup = isAllowBackup,
            testOnly = isTestOnly,
            extractNativeLibs = isExtractNativeLibs,
            requestLegacyExternalStorage = isLegacyStorage,
            supportsRtl = isRtl,
            hardwareAccelerated = isHwAccelerated,
            appTheme = themeRef,
            appLabel = labelRef,
            appIcon = iconRef,
            fullBackupContent = backupContent,
            dataExtractionRules = extractionRules,
            metaData = metaDataList,
            features = featuresList,
            permissions = permissionsList,
        )

        return ManifestParseResult(
            components = components,
            config = config,
            rawXml = xmlBuilder.toString(),
        )
    }

    private fun parseStringPool(buffer: ByteBuffer, start: Int, size: Int): List<String> {
        val stringCount = buffer.int
        val styleCount = buffer.int
        val flags = buffer.int
        val stringsStartOffset = buffer.int
        val stylesStartOffset = buffer.int

        val isUtf8 = (flags and (1 shl 8)) != 0

        val stringOffsets = IntArray(stringCount)
        for (i in 0 until stringCount) {
            stringOffsets[i] = buffer.int
        }

        val strings = ArrayList<String>(stringCount)
        val baseDataOffset = start + stringsStartOffset

        for (i in 0 until stringCount) {
            val offset = baseDataOffset + stringOffsets[i]
            if (offset >= buffer.capacity()) {
                strings.add("")
                continue
            }
            buffer.position(offset)

            if (isUtf8) {
                var charLen = buffer.get().toInt() and 0xFF
                if ((charLen and 0x80) != 0) {
                    charLen = ((charLen and 0x7F) shl 8) or (buffer.get().toInt() and 0xFF)
                }
                var byteLen = buffer.get().toInt() and 0xFF
                if ((byteLen and 0x80) != 0) {
                    byteLen = ((byteLen and 0x7F) shl 8) or (buffer.get().toInt() and 0xFF)
                }
                if (buffer.remaining() < byteLen) {
                    strings.add("")
                    continue
                }
                val strBytes = ByteArray(byteLen)
                buffer.get(strBytes)
                strings.add(String(strBytes, Charsets.UTF_8))
            } else {
                var charLen = buffer.short.toInt() and 0xFFFF
                if ((charLen and 0x8000) != 0) {
                    charLen = ((charLen and 0x7FFF) shl 16) or (buffer.short.toInt() and 0xFFFF)
                }
                val byteLen = charLen * 2
                if (buffer.remaining() < byteLen) {
                    strings.add("")
                    continue
                }
                val strBytes = ByteArray(byteLen)
                buffer.get(strBytes)
                strings.add(String(strBytes, Charsets.UTF_16LE))
            }
        }

        return strings
    }
}
