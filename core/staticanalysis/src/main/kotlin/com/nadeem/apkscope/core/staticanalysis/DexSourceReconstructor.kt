package com.nadeem.apkscope.core.staticanalysis

/**
 * Produces a readable, source-like view from the smali emitted by [DexDisassembler].
 *
 * This is intentionally not called a decompiler: DEX does not retain the original Kotlin/Java
 * source, local variable names, nullability, or control-flow structure. The renderer preserves the
 * class hierarchy and method/field surface, translates a small set of unambiguous instructions,
 * and keeps the remaining instructions as comments so the UI never presents an empty or invented
 * implementation for an arbitrary component.
 */
object DexSourceReconstructor {

    fun reconstruct(smaliCode: String): String {
        val parsed = SmaliParser.parse(smaliCode)
            ?: return """/* Unable to reconstruct source: no .class declaration was found in the DEX output. */"""

        return Renderer.render(parsed)
    }

    private data class SmaliClass(
        val descriptor: String,
        val accessFlags: Set<String>,
        val superDescriptor: String?,
        val interfaces: List<String>,
        val sourceFile: String?,
        val fields: List<SmaliField>,
        val methods: List<SmaliMethod>,
    )

    private data class SmaliField(
        val name: String,
        val descriptor: String,
        val accessFlags: Set<String>,
    )

    private data class SmaliMethod(
        val name: String,
        val parameterDescriptors: List<String>,
        val returnDescriptor: String,
        val accessFlags: Set<String>,
        val instructions: List<String>,
    )

    private object SmaliParser {
        private val methodPattern = Regex("""^\.method\s+(.*?)\s+([^\s(]+)\(([^)]*)\)(\S+)\s*$""")
        private val fieldPattern = Regex("""^\.field\s+(?:.*\s)?([^\s:]+):(\S+)\s*$""")

        fun parse(smaliCode: String): SmaliClass? {
            var descriptor: String? = null
            var accessFlags = emptySet<String>()
            var superDescriptor: String? = null
            val interfaces = mutableListOf<String>()
            var sourceFile: String? = null
            val fields = mutableListOf<SmaliField>()
            val methods = mutableListOf<SmaliMethod>()
            var currentMethod: SmaliMethodBuilder? = null

            smaliCode.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                when {
                    line.startsWith(".class ") -> {
                        val tokens = line.split(Regex("\\s+"))
                        descriptor = tokens.lastOrNull()?.takeIf { it.startsWith("L") && it.endsWith(";") }
                        accessFlags = tokens.drop(1).dropLast(1).toSet()
                    }
                    line.startsWith(".super ") -> superDescriptor = line.substringAfterLast(' ').trim()
                    line.startsWith(".implements ") -> interfaces += line.substringAfterLast(' ').trim()
                    line.startsWith(".source ") -> sourceFile = line.substringAfter(".source ").trim().trim('"')
                    line.startsWith(".field ") -> {
                        fieldPattern.matchEntire(line)?.let { match ->
                            val fieldPrefix = line.substringAfter(".field ").substringBefore(":")
                            val name = match.groupValues[1]
                            fields += SmaliField(
                                name = name,
                                descriptor = match.groupValues[2],
                                accessFlags = fieldPrefix.substringBeforeLast(' ').split(Regex("\\s+"))
                                    .filter { it.isNotBlank() && it != name }
                                    .toSet(),
                            )
                        }
                    }
                    line.startsWith(".method ") -> {
                        currentMethod = methodPattern.matchEntire(line)?.let { match ->
                            SmaliMethodBuilder(
                                name = match.groupValues[2],
                                parameterDescriptors = parseDescriptors(match.groupValues[3]),
                                returnDescriptor = match.groupValues[4],
                                accessFlags = match.groupValues[1].split(Regex("\\s+")).filter { it.isNotBlank() }.toSet(),
                            )
                        }
                    }
                    line == ".end method" -> {
                        currentMethod?.let { methods += it.build() }
                        currentMethod = null
                    }
                    else -> if (line.isNotBlank()) currentMethod?.instructions?.add(line)
                }
            }

            val classDescriptor = descriptor ?: return null
            return SmaliClass(
                descriptor = classDescriptor,
                accessFlags = accessFlags,
                superDescriptor = superDescriptor,
                interfaces = interfaces,
                sourceFile = sourceFile,
                fields = fields,
                methods = methods,
            )
        }

        private class SmaliMethodBuilder(
            private val name: String,
            private val parameterDescriptors: List<String>,
            private val returnDescriptor: String,
            private val accessFlags: Set<String>,
        ) {
            val instructions = mutableListOf<String>()

            fun build() = SmaliMethod(name, parameterDescriptors, returnDescriptor, accessFlags, instructions.toList())
        }
    }

    private object Renderer {
        fun render(clazz: SmaliClass): String {
            val className = typeName(clazz.descriptor)
            val packageName = packageName(clazz.descriptor)
            val imports = collectImports(clazz)
            val classKind = when {
                "interface" in clazz.accessFlags -> "interface"
                "enum" in clazz.accessFlags -> "enum class"
                else -> "class"
            }
            val classModifiers = listOf("public", "protected", "private", "abstract")
                .filter { it in clazz.accessFlags }
                .joinToString(" ")
                .let { if (it.isBlank()) "" else "$it " }
            val parentTypes = buildList {
                clazz.superDescriptor
                    ?.takeUnless { it == "Ljava/lang/Object;" }
                    ?.let { add("${typeName(it)}()") }
                clazz.interfaces.mapTo(this) { typeName(it) }
            }
            val inheritance = if (parentTypes.isEmpty()) "" else " : ${parentTypes.joinToString(", ") }"

            return buildString {
                appendLine("/*")
                appendLine(" * Reconstructed source from DEX bytecode.")
                appendLine(" * Original source, local names, nullability, and exact control flow are not available.")
                clazz.sourceFile?.let { appendLine(" * DEX source hint: $it") }
                appendLine(" * Untranslated instructions are retained as smali comments for inspection.")
                appendLine(" */")
                if (packageName.isNotBlank()) {
                    appendLine("package $packageName")
                }
                if (imports.isNotEmpty()) {
                    appendLine()
                    imports.forEach { appendLine("import $it") }
                }
                appendLine()
                appendLine("${classModifiers}${classKind} ${safeIdentifier(className)}$inheritance {")

                clazz.fields.forEach { field ->
                    appendLine("    // field ${field.name}: ${typeName(field.descriptor)} (${field.accessFlags.joinToString(" ")})")
                }
                if (clazz.fields.isNotEmpty() && clazz.methods.isNotEmpty()) appendLine()

                val renderedMethods = clazz.methods.mapNotNull { renderMethod(it, clazz) }
                renderedMethods.forEachIndexed { index, method ->
                    if (index > 0) appendLine()
                    method.forEach(::appendLine)
                }
                if (clazz.fields.isEmpty() && renderedMethods.isEmpty()) {
                    appendLine("    // No fields or methods were emitted for this class.")
                }
                appendLine("}")
            }.trimEnd()
        }

        private fun renderMethod(method: SmaliMethod, clazz: SmaliClass): List<String>? {
            if (method.name == "<clinit>") {
                return listOf(
                    "    // Static initializer",
                    *renderInstructions(method, indent = "    ").toTypedArray(),
                )
            }
            if (method.name == "<init>") {
                val meaningful = renderInstructions(method, indent = "        ")
                    .filterNot { it.contains("smali: invoke-direct") && it.contains("-><init>") }
                if (meaningful.isEmpty()) return null
                return listOf("    init {") + meaningful + "    }"
            }
            if ("abstract" in method.accessFlags || "native" in method.accessFlags) {
                return listOf("    // ${method.accessFlags.joinToString(" ")} ${method.name}${methodSignature(method)}")
            }

            val params = method.parameterDescriptors.mapIndexed { index, descriptor ->
                "${parameterName(method.name, index)}: ${typeName(descriptor)}"
            }
            val returnType = typeName(method.returnDescriptor)
            val visibility = listOf("public", "protected", "private")
                .firstOrNull { it in method.accessFlags }
                ?.let { "$it " }
                ?: ""
            val static = if ("static" in method.accessFlags) "companion object " else ""
            val override = if (isLikelyOverride(method, clazz)) "override " else ""
            val signature = if (returnType == "Unit") {
                "fun ${safeIdentifier(method.name)}(${params.joinToString(", ")})"
            } else {
                "fun ${safeIdentifier(method.name)}(${params.joinToString(", ")}): $returnType"
            }
            val body = renderInstructions(method, indent = "        ")
            return buildList {
                if (static.isNotBlank()) add("    $static{")
                add("    ${visibility}${override}$signature {")
                addAll(body.ifEmpty { listOf("        // No executable instructions were emitted.") })
                add("    }")
                if (static.isNotBlank()) add("    }")
            }
        }

        private fun renderInstructions(method: SmaliMethod, indent: String): List<String> {
            val registerValues = mutableMapOf<String, String>()
            val parameterNames = method.parameterDescriptors.mapIndexed { index, _ ->
                "p${if ("static" in method.accessFlags) index else index + 1}" to parameterName(method.name, index)
            }.toMap()
            val output = mutableListOf<String>()
            var pendingExpression: String? = null

            method.instructions.forEach { instruction ->
                when {
                    instruction.startsWith(".registers ") || instruction.startsWith(".line ") || instruction.startsWith(".local ") || instruction.startsWith(".end local") || instruction.startsWith(".restart local") -> Unit
                    instruction.startsWith(":") -> output += "$indent// label $instruction"
                    instruction.startsWith("const-string") -> {
                        val match = Regex("""^const-string(?:/jumbo)?\s+(\S+),\s+(.+)$""").find(instruction)
                        if (match != null) {
                            val value = match.groupValues[2]
                            registerValues[match.groupValues[1]] = value
                            output += "${indent}val ${match.groupValues[1]} = $value"
                        } else {
                            output += "$indent// smali: $instruction"
                        }
                    }
                    instruction.matches(Regex("^const(?:/4|/16|/high16)?\\s+\\S+,\\s+.*$")) || instruction.startsWith("const-wide") -> {
                        val match = Regex("""^const(?:/4|/16|/high16|-wide)?\s+(\S+),\s+(.+)$""").find(instruction)
                        if (match != null) {
                            val value = when (match.groupValues[2]) {
                                "0x0" -> "0"
                                "0x1" -> "1"
                                "0x0L" -> "0L"
                                "0x1L" -> "1L"
                                else -> match.groupValues[2]
                            }
                            registerValues[match.groupValues[1]] = value
                            output += "${indent}val ${match.groupValues[1]} = $value"
                        } else {
                            output += "$indent// smali: $instruction"
                        }
                    }
                    instruction.startsWith("new-instance ") -> {
                        val match = Regex("""^new-instance\s+(\S+),\s+(L[^;]+;)$""").find(instruction)
                        if (match != null) {
                            registerValues[match.groupValues[1]] = "${typeName(match.groupValues[2])}()"
                        } else {
                            output += "$indent// smali: $instruction"
                        }
                    }
                    instruction.startsWith("invoke-") -> {
                        val expression = renderInvoke(instruction, registerValues, parameterNames)
                        if (expression != null) {
                            pendingExpression = expression
                            if (!instruction.contains("-><init>(")) output += "$indent// $expression"
                        } else {
                            output += "$indent// smali: $instruction"
                        }
                    }
                    instruction.startsWith("move-result") -> {
                        val register = instruction.substringAfter(' ').trim()
                        val expression = pendingExpression
                        if (expression != null) {
                            registerValues[register] = expression
                            output += "${indent}val $register = $expression"
                        } else {
                            output += "$indent// smali: $instruction"
                        }
                        pendingExpression = null
                    }
                    instruction.startsWith("return-void") -> Unit
                    instruction.startsWith("return-object ") || instruction.startsWith("return ") || instruction.startsWith("return-wide ") -> {
                        val register = instruction.substringAfter(' ').trim()
                        val value = registerValues[register]
                        output += if (value != null) "${indent}return $value" else "${indent}// smali: $instruction"
                    }
                    instruction.startsWith("if-") || instruction.startsWith("goto") || instruction.startsWith("packed-switch") || instruction.startsWith("sparse-switch") -> {
                        output += "$indent// control flow: $instruction"
                    }
                    instruction.startsWith("throw ") -> output += "$indent// throws ${instruction.substringAfter(' ').trim()}"
                    else -> output += "$indent// smali: $instruction"
                }
            }
            return output
        }

        private fun renderInvoke(
            instruction: String,
            registerValues: Map<String, String>,
            parameterNames: Map<String, String>,
        ): String? {
            val match = Regex("""^invoke-([a-z-]+)\s+\{([^}]*)\},\s+(L[^;]+;)->([^\s(]+)(\([^)]*\)\S+)$""").find(instruction)
                ?: return null
            val kind = match.groupValues[1]
            val registers = match.groupValues[2].split(',').map { it.trim() }.filter { it.isNotBlank() }
            val owner = typeName(match.groupValues[3])
            val methodName = match.groupValues[4]
            val receiver = registers.firstOrNull()?.let { registerValue(it, registerValues, parameterNames) }
            val args = registers.drop(if (kind == "static") 0 else 1)
                .map { registerValue(it, registerValues, parameterNames) }
            val call = if (kind == "super") {
                "super.$methodName(${args.joinToString(", ")})"
            } else if (kind == "static") {
                "$owner.$methodName(${args.joinToString(", ")})"
            } else if (receiver == "this" || receiver == null) {
                "this.$methodName(${args.joinToString(", ")})"
            } else if (methodName == "<init>") {
                "$owner(${args.joinToString(", ")})"
            } else {
                "$receiver.$methodName(${args.joinToString(", ")})"
            }
            return if (methodName == "<init>" && kind == "direct") "/* $call */" else call
        }

        private fun registerValue(
            register: String,
            registerValues: Map<String, String>,
            parameterNames: Map<String, String>,
        ): String = when (register) {
            "p0", "this" -> "this"
            else -> registerValues[register] ?: parameterNames[register] ?: register
        }

        private fun collectImports(clazz: SmaliClass): List<String> {
            val descriptors = buildList {
                clazz.superDescriptor?.let(::add)
                addAll(clazz.interfaces)
                clazz.fields.mapTo(this) { it.descriptor }
                clazz.methods.forEach { method ->
                    addAll(method.parameterDescriptors)
                    add(method.returnDescriptor)
                }
            }
            return descriptors.flatMap(::objectTypes)
                .filter { it !in setOf("java.lang.Object", "java.lang.String", "java.lang.Boolean", "java.lang.Byte", "java.lang.Short", "java.lang.Character", "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double") }
                .filter { it.substringBeforeLast('.', "") != packageName(clazz.descriptor) }
                .distinct()
                .sorted()
        }

        private fun objectTypes(descriptor: String): List<String> {
            var value = descriptor
            while (value.startsWith("[")) value = value.drop(1)
            if (!value.startsWith("L") || !value.endsWith(";")) return emptyList()
            return listOf(value.removePrefix("L").removeSuffix(";").replace('/', '.').replace('$', '.'))
        }

        private fun isLikelyOverride(method: SmaliMethod, clazz: SmaliClass): Boolean {
            if ("static" in method.accessFlags || "private" in method.accessFlags || clazz.superDescriptor == null) return false
            return method.name in setOf(
                "onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy", "onNewIntent",
                "onBind", "onStartCommand", "onReceive", "query", "insert", "delete", "update", "getType",
            )
        }

        private fun methodSignature(method: SmaliMethod): String =
            " ${method.name}(${method.parameterDescriptors.joinToString() }): ${typeName(method.returnDescriptor)}"

        private fun parameterName(methodName: String, index: Int): String = when (methodName to index) {
            "onCreate" to 0 -> "savedInstanceState"
            "onNewIntent" to 0 -> "intent"
            "onBind" to 0 -> "intent"
            "onStartCommand" to 0 -> "intent"
            "onStartCommand" to 1 -> "flags"
            "onStartCommand" to 2 -> "startId"
            "onReceive" to 0 -> "context"
            "onReceive" to 1 -> "intent"
            else -> "arg$index"
        }

        private fun typeName(descriptor: String): String {
            if (descriptor.isBlank()) return "Any"
            if (descriptor.startsWith("[")) return "Array<${typeName(descriptor.drop(1))}>"
            return when (descriptor) {
                "V" -> "Unit"
                "Z" -> "Boolean"
                "B" -> "Byte"
                "S" -> "Short"
                "C" -> "Char"
                "I" -> "Int"
                "J" -> "Long"
                "F" -> "Float"
                "D" -> "Double"
                else -> descriptor.removePrefix("L").removeSuffix(";")
                    .substringAfterLast('/')
                    .replace('$', '.')
            }
        }

        private fun packageName(descriptor: String): String = descriptor.removePrefix("L").removeSuffix(";")
            .substringBeforeLast('/', "")
            .replace('/', '.')

        private fun safeIdentifier(value: String): String =
            if (value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) value else "`$value`"
    }

    private fun parseDescriptors(value: String): List<String> {
        val descriptors = mutableListOf<String>()
        var index = 0
        while (index < value.length) {
            val start = index
            while (index < value.length && value[index] == '[') index++
            if (index >= value.length) break
            if (value[index] == 'L') {
                val end = value.indexOf(';', index)
                if (end < 0) break
                index = end + 1
            } else {
                index++
            }
            descriptors += value.substring(start, index)
        }
        return descriptors
    }
}
