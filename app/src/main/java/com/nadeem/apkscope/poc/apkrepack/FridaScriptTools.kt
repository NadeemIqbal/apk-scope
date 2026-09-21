package com.nadeem.apkscope.poc.apkrepack

/** A user-facing diagnostic attached to the command editor. */
data class FridaScriptIssue(
    val line: Int,
    val column: Int,
    val message: String,
    val suggestion: String,
    val isWarning: Boolean = false,
)

/**
 * Small, dependency-free editor helpers for the experimental Frida console.
 *
 * This is deliberately a conservative lexical check rather than a claim to be a full JavaScript
 * parser. It catches the mistakes that otherwise make a command fail silently in a target process:
 * unterminated strings/comments and unbalanced delimiters. The formatter preserves strings and
 * comments and only changes whitespace around structural punctuation.
 */
object FridaScriptTools {
    private enum class LexicalState { NORMAL, SINGLE_QUOTE, DOUBLE_QUOTE, TEMPLATE, LINE_COMMENT, BLOCK_COMMENT }

    private data class Delimiter(val value: Char, val line: Int, val column: Int)

    fun validate(source: String): List<FridaScriptIssue> {
        if (source.isBlank()) {
            return listOf(
                FridaScriptIssue(
                    line = 1,
                    column = 1,
                    message = "The command is empty.",
                    suggestion = "Choose a quick command or enter JavaScript before verifying.",
                )
            )
        }

        val issues = mutableListOf<FridaScriptIssue>()
        val delimiters = ArrayDeque<Delimiter>()
        var state = LexicalState.NORMAL
        var escaped = false
        var line = 1
        var column = 1
        var tokenLine = 1
        var tokenColumn = 1
        var index = 0

        fun advance(value: Char) {
            if (value == '\n') {
                line += 1
                column = 1
            } else {
                column += 1
            }
        }

        fun issue(message: String, suggestion: String, warning: Boolean = false) {
            issues += FridaScriptIssue(line, column, message, suggestion, warning)
        }

        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)

            when (state) {
                LexicalState.NORMAL -> when {
                    current == '/' && next == '/' -> {
                        state = LexicalState.LINE_COMMENT
                        advance(current)
                        advance(next)
                        index += 2
                        continue
                    }
                    current == '/' && next == '*' -> {
                        state = LexicalState.BLOCK_COMMENT
                        tokenLine = line
                        tokenColumn = column
                        advance(current)
                        advance(next)
                        index += 2
                        continue
                    }
                    current == '\'' -> {
                        state = LexicalState.SINGLE_QUOTE
                        tokenLine = line
                        tokenColumn = column
                        escaped = false
                    }
                    current == '"' -> {
                        state = LexicalState.DOUBLE_QUOTE
                        tokenLine = line
                        tokenColumn = column
                        escaped = false
                    }
                    current == '`' -> {
                        state = LexicalState.TEMPLATE
                        tokenLine = line
                        tokenColumn = column
                        escaped = false
                    }
                    current == '(' || current == '[' || current == '{' -> {
                        delimiters.addLast(Delimiter(current, line, column))
                    }
                    current == ')' || current == ']' || current == '}' -> {
                        val expected = when (current) {
                            ')' -> '('
                            ']' -> '['
                            else -> '{'
                        }
                        val open = delimiters.removeLastOrNull()
                        if (open == null || open.value != expected) {
                            issue(
                                "Unexpected '$current'.",
                                "Add the matching opening '$expected' or remove this closing delimiter.",
                            )
                            if (open != null) delimiters.addLast(open)
                        }
                    }
                }

                LexicalState.SINGLE_QUOTE,
                LexicalState.DOUBLE_QUOTE,
                LexicalState.TEMPLATE -> {
                    if (escaped) {
                        escaped = false
                    } else if (current == '\\') {
                        escaped = true
                    } else if (
                        (state == LexicalState.SINGLE_QUOTE && current == '\'') ||
                        (state == LexicalState.DOUBLE_QUOTE && current == '"') ||
                        (state == LexicalState.TEMPLATE && current == '`')
                    ) {
                        state = LexicalState.NORMAL
                    } else if (current == '\n' && state != LexicalState.TEMPLATE) {
                        issues += FridaScriptIssue(
                            tokenLine,
                            tokenColumn,
                            "Unterminated string literal.",
                            "Close the string with ${if (state == LexicalState.SINGLE_QUOTE) "'" else "\""} or use a template literal for multi-line text.",
                        )
                        state = LexicalState.NORMAL
                    }
                }

                LexicalState.LINE_COMMENT -> if (current == '\n') state = LexicalState.NORMAL

                LexicalState.BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        advance(current)
                        advance(next)
                        state = LexicalState.NORMAL
                        index += 2
                        continue
                    }
                }
            }

            advance(current)
            index += 1
        }

        when (state) {
            LexicalState.SINGLE_QUOTE,
            LexicalState.DOUBLE_QUOTE,
            LexicalState.TEMPLATE -> issues += FridaScriptIssue(
                tokenLine,
                tokenColumn,
                "Unterminated string or template literal.",
                "Close the literal before running the command.",
            )
            LexicalState.BLOCK_COMMENT -> issues += FridaScriptIssue(
                tokenLine,
                tokenColumn,
                "Unterminated block comment.",
                "Add */ to close the comment.",
            )
            else -> Unit
        }

        delimiters.asReversed().forEach { delimiter ->
            issues += FridaScriptIssue(
                delimiter.line,
                delimiter.column,
                "Opening '${delimiter.value}' is not closed.",
                "Add the matching '${matchingDelimiter(delimiter.value)}' before running the command.",
            )
        }

        // These APIs belong to an external Frida controller, not this target-bound channel. Keep
        // them as warnings because the exact expression may still be useful for inspection, but
        // explain why it will not retarget the command to another package/process.
        val unsupportedControllerApi = Regex("\\b(device|session|spawn|attach)\\s*[.(]")
        unsupportedControllerApi.find(source)?.let { match ->
            val before = source.substring(0, match.range.first)
            val warningLine = before.count { it == '\n' } + 1
            val warningColumn = match.range.first - (before.lastIndexOf('\n') + 1) + 1
            issues += FridaScriptIssue(
                warningLine,
                warningColumn,
                "This looks like a controller-level attach/spawn operation.",
                "Use target-process APIs such as Process.enumerateModules() or Java.use(); this channel is already attached to the active target.",
                isWarning = true,
            )
        }

        return issues
    }

    fun beautify(source: String): String {
        if (source.isBlank()) return source

        val output = StringBuilder()
        var state = LexicalState.NORMAL
        var escaped = false
        var indent = 0
        var parenDepth = 0
        var lineHasContent = false
        var index = 0

        fun indentIfNeeded() {
            if (!lineHasContent) {
                repeat(indent) { output.append("    ") }
                lineHasContent = true
            }
        }

        fun trimLineEnd() {
            while (output.isNotEmpty() && output.last() == ' ') output.deleteCharAt(output.lastIndex)
        }

        fun newline() {
            trimLineEnd()
            if (lineHasContent) output.append('\n')
            lineHasContent = false
        }

        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            when (state) {
                LexicalState.NORMAL -> when {
                    current == '/' && next == '/' -> {
                        indentIfNeeded()
                        output.append("//")
                        state = LexicalState.LINE_COMMENT
                        index += 2
                        continue
                    }
                    current == '/' && next == '*' -> {
                        indentIfNeeded()
                        output.append("/*")
                        state = LexicalState.BLOCK_COMMENT
                        index += 2
                        continue
                    }
                    current == '\'' || current == '"' || current == '`' -> {
                        indentIfNeeded()
                        output.append(current)
                        state = when (current) {
                            '\'' -> LexicalState.SINGLE_QUOTE
                            '"' -> LexicalState.DOUBLE_QUOTE
                            else -> LexicalState.TEMPLATE
                        }
                        escaped = false
                    }
                    current.isWhitespace() -> {
                        if (current == '\n') newline()
                        else if (lineHasContent && output.lastOrNull() != ' ') output.append(' ')
                    }
                    current == '{' -> {
                        indentIfNeeded()
                        if (output.lastOrNull() != ' ') output.append(' ')
                        output.append('{')
                        indent += 1
                        newline()
                    }
                    current == '}' -> {
                        if (lineHasContent) newline()
                        indent = (indent - 1).coerceAtLeast(0)
                        indentIfNeeded()
                        output.append('}')
                    }
                    current == ';' -> {
                        indentIfNeeded()
                        output.append(';')
                        if (parenDepth == 0) newline() else output.append(' ')
                    }
                    current == '(' -> {
                        indentIfNeeded()
                        output.append(current)
                        parenDepth += 1
                    }
                    current == ')' -> {
                        trimLineEnd()
                        output.append(current)
                        parenDepth = (parenDepth - 1).coerceAtLeast(0)
                    }
                    else -> {
                        indentIfNeeded()
                        output.append(current)
                    }
                }

                LexicalState.SINGLE_QUOTE,
                LexicalState.DOUBLE_QUOTE,
                LexicalState.TEMPLATE -> {
                    output.append(current)
                    if (escaped) escaped = false
                    else if (current == '\\') escaped = true
                    else if (
                        (state == LexicalState.SINGLE_QUOTE && current == '\'') ||
                        (state == LexicalState.DOUBLE_QUOTE && current == '"') ||
                        (state == LexicalState.TEMPLATE && current == '`')
                    ) state = LexicalState.NORMAL
                }

                LexicalState.LINE_COMMENT -> {
                    if (current == '\n') {
                        state = LexicalState.NORMAL
                        newline()
                    } else output.append(current)
                }

                LexicalState.BLOCK_COMMENT -> {
                    output.append(current)
                    if (current == '*' && next == '/') {
                        output.append('/')
                        state = LexicalState.NORMAL
                        index += 2
                        continue
                    }
                }
            }
            index += 1
        }

        trimLineEnd()
        while (output.endsWith("\n")) output.deleteCharAt(output.lastIndex)
        return output.toString().trim()
    }

    /** Format a successful Frida value for display without changing the raw response. */
    fun formatResult(source: String): String {
        if (source.isBlank()) return source
        return prettyJson(source) ?: if (looksLikeJavaScript(source)) beautify(source) else source
    }

    /** Return indented JSON for object/array responses, or null when the value is not JSON. */
    fun prettyJson(source: String): String? {
        val trimmed = source.trim()
        if (trimmed.length < 2) return null
        val root = trimmed.first()
        val closingRoot = trimmed.last()
        if (root != '{' && root != '[') return null
        if (closingRoot != if (root == '{') '}' else ']') return null

        val output = StringBuilder()
        val delimiters = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        var indent = 0

        fun appendIndent() {
            repeat(indent) { output.append("    ") }
        }

        fun trimTrailingWhitespace() {
            while (output.lastOrNull()?.isWhitespace() == true) output.deleteCharAt(output.lastIndex)
        }

        trimmed.forEach { current ->
            when {
                inString -> {
                    output.append(current)
                    if (escaped) escaped = false
                    else if (current == '\\') escaped = true
                    else if (current == '"') inString = false
                }
                current == '"' -> {
                    inString = true
                    output.append(current)
                }
                current.isWhitespace() -> Unit
                current == '{' || current == '[' -> {
                    delimiters.addLast(current)
                    output.append(current)
                    indent += 1
                    output.append('\n')
                    appendIndent()
                }
                current == '}' || current == ']' -> {
                    val expected = if (current == '}') '{' else '['
                    if (delimiters.removeLastOrNull() != expected) return null
                    val wasEmpty = output.toString().trimEnd().lastOrNull() == expected
                    indent -= 1
                    trimTrailingWhitespace()
                    if (!wasEmpty) {
                        output.append('\n')
                        appendIndent()
                    }
                    output.append(current)
                }
                current == ',' -> {
                    trimTrailingWhitespace()
                    output.append(",\n")
                    appendIndent()
                }
                current == ':' -> {
                    trimTrailingWhitespace()
                    output.append(": ")
                }
                else -> output.append(current)
            }
        }

        if (inString || delimiters.isNotEmpty()) return null
        return output.toString().trim()
    }

    private fun looksLikeJavaScript(source: String): Boolean {
        val trimmed = source.trim()
        val startsLikeCode = trimmed.startsWith("function ") ||
            trimmed.startsWith("var ") ||
            trimmed.startsWith("let ") ||
            trimmed.startsWith("const ") ||
            trimmed.startsWith("if ") ||
            trimmed.startsWith("if(") ||
            trimmed.startsWith("(()") ||
            trimmed.startsWith("({")
        return startsLikeCode && (trimmed.contains('{') || trimmed.contains(';') || trimmed.contains("=>"))
    }

    private fun matchingDelimiter(open: Char): Char = when (open) {
        '(' -> ')'
        '[' -> ']'
        else -> '}'
    }
}
