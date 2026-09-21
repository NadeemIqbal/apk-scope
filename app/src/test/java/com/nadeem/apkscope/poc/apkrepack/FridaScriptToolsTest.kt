package com.nadeem.apkscope.poc.apkrepack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FridaScriptToolsTest {
    @Test
    fun emptyScriptIsRejected() {
        val issues = FridaScriptTools.validate("  \n")

        assertTrue(issues.any { !it.isWarning && it.message == "The command is empty." })
    }

    @Test
    fun jsonResultsArePrettyPrintedForDisplay() {
        assertEquals(
            "{\n    \"name\": \"fixture\",\n    \"items\": [\n        1,\n        2\n    ]\n}",
            FridaScriptTools.formatResult("{\"name\":\"fixture\",\"items\":[1,2]}"),
        )
    }

    @Test
    fun plainResultsRemainUnchanged() {
        assertEquals("hello", FridaScriptTools.formatResult("hello"))
    }

    @Test
    fun validTargetScriptHasNoErrors() {
        val issues = FridaScriptTools.validate("Process.enumerateModules().map(function (m) { return m.name; });")

        assertTrue(issues.none { !it.isWarning })
    }

    @Test
    fun reportsUnclosedDelimiterWithARepair() {
        val issue = FridaScriptTools.validate("Process.enumerateModules().map(function (m) { return m.name;")
            .first { !it.isWarning }

        assertTrue(issue.message.contains("not closed"))
        assertTrue(issue.suggestion.contains("matching"))
    }

    @Test
    fun formatterPreservesStringsAndAddsReadableIndentation() {
        val formatted = FridaScriptTools.beautify("var value = '{not a block}'; if (value) { console.log(value); }")

        assertEquals(
            "var value = '{not a block}';\nif (value) {\n    console.log(value);\n}",
            formatted,
        )
    }

    @Test
    fun controllerOperationsAreExplainedAsWarnings() {
        val issues = FridaScriptTools.validate("device.attach(123)")

        assertTrue(issues.any { it.isWarning && it.suggestion.contains("target-process") })
    }
}
