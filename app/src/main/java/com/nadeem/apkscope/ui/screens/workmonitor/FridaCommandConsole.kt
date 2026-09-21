package com.nadeem.apkscope.ui.screens.workmonitor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nadeem.apkscope.poc.apkrepack.FridaChannelConfig
import com.nadeem.apkscope.poc.apkrepack.FridaScriptIssue
import com.nadeem.apkscope.poc.apkrepack.FridaScriptTools
import com.nadeem.apkscope.poc.apkrepack.FridaTrafficMonitor
import com.nadeem.apkscope.ui.components.AppTopBar
import com.nadeem.apkscope.ui.components.BaseCard
import com.nadeem.apkscope.ui.theme.MonoCodeStyle
import com.nadeem.apkscope.ui.theme.Spacing

private data class QuickFridaCommand(
    val label: String,
    val description: String,
    val source: String,
)

private data class CommandTranscriptEntry(
    val id: String,
    val source: String,
    val result: FridaTrafficMonitor.CommandResult? = null,
)

private val quickCommands = listOf(
    QuickFridaCommand(
        label = "Identity",
        description = "Confirm the active package, PID, architecture, and platform.",
        source = "({ packageName: packageName, pid: Process.id, arch: Process.arch, platform: Process.platform })",
    ),
    QuickFridaCommand(
        label = "Modules",
        description = "List native modules loaded by the current target process.",
        source = "Process.enumerateModules().map(function (m) { return { name: m.name, base: String(m.base), size: m.size, path: m.path }; })",
    ),
    QuickFridaCommand(
        label = "Threads",
        description = "List threads belonging to the current target process.",
        source = "Process.enumerateThreads().map(function (t) { return { id: t.id, state: t.state }; })",
    ),
    QuickFridaCommand(
        label = "Java classes",
        description = "Show the first 200 classes already loaded in the target VM.",
        source = "Java.available ? Java.enumerateLoadedClassesSync().slice(0, 200) : []",
    ),
)

private val defaultFridaSource = quickCommands.first().source

@Composable
fun FridaCommandConsoleScreen(
    targetPackage: String,
    sessionId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by FridaTrafficMonitor.shared.status.collectAsState()
    var source by remember { mutableStateOf(defaultFridaSource) }
    var verifiedSource by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val transcript = remember(targetPackage, sessionId) { mutableStateListOf<CommandTranscriptEntry>() }
    val listState = rememberLazyListState()
    // An empty composer is an intentional post-send state, not a script error. Do not show an
    // "empty command" diagnostic while the user is deciding what to send next.
    val validationIssues = remember(source) { FridaScriptTools.validate(source) }
    // Keep the post-send composer visually clean, but do not let the empty state count as a
    // successful verification when the user explicitly presses Verify.
    val issues = if (source.isBlank()) emptyList() else validationIssues
    val errors = issues.filterNot(FridaScriptIssue::isWarning)
    val warnings = issues.filter(FridaScriptIssue::isWarning)
    val validationErrors = validationIssues.filterNot(FridaScriptIssue::isWarning)
    val validationWarnings = validationIssues.filter(FridaScriptIssue::isWarning)
    val isVerified = source.isNotBlank() && verifiedSource == source && validationErrors.isEmpty()

    LaunchedEffect(targetPackage, sessionId) {
        FridaTrafficMonitor.shared.commandResults.collect { result ->
            if (result.targetPackage != targetPackage || result.sessionId != sessionId) return@collect
            val existingIndex = transcript.indexOfFirst { it.id == result.id }
            if (existingIndex >= 0) {
                transcript[existingIndex] = transcript[existingIndex].copy(result = result)
            } else if (result.source != null) {
                transcript.add(
                    CommandTranscriptEntry(
                        id = result.id,
                        source = result.source,
                        result = result,
                    ),
                )
            }
        }
    }

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.lastIndex)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = "Frida Console",
                eyebrow = "TARGET",
                onBack = onBack,
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(Spacing.base),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    OutlinedTextField(
                        value = source,
                        onValueChange = { value ->
                            if (value.length <= FridaChannelConfig.MAX_COMMAND_CHARS) {
                                source = value
                                verifiedSource = null
                                statusMessage = null
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp, max = 190.dp),
                        label = { Text("JavaScript command") },
                        placeholder = { Text("Choose a quick command or type JavaScript") },
                        textStyle = MonoCodeStyle,
                        supportingText = {
                            Text("${source.length} / ${FridaChannelConfig.MAX_COMMAND_CHARS} characters")
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        OutlinedButton(
                            onClick = {
                                source = FridaScriptTools.beautify(source)
                                verifiedSource = null
                                statusMessage = "Formatted locally. Verify is optional; send when ready."
                            },
                        ) { Text("Beautify") }
                        OutlinedButton(
                            onClick = {
                                verifiedSource = if (source.isNotBlank() && validationErrors.isEmpty()) source else null
                                statusMessage = when {
                                    source.isBlank() -> "Enter a command before verifying."
                                    validationErrors.isEmpty() -> {
                                        if (validationWarnings.isEmpty()) "Code is ready to run." else "Code is valid with ${validationWarnings.size} warning(s)."
                                    }
                                    else -> {
                                        "Found ${validationErrors.size} issue(s). You can still send and inspect the target result."
                                    }
                                }
                            },
                        ) { Text(if (isVerified) "Verified" else "Verify") }
                        Button(
                            enabled = status.commandReady,
                            onClick = {
                                val submittedSource = source
                                val dispatch = FridaTrafficMonitor.shared.sendScript(submittedSource)
                                statusMessage = if (dispatch.accepted && dispatch.id != null) {
                                    transcript.add(CommandTranscriptEntry(dispatch.id, submittedSource))
                                    source = ""
                                    verifiedSource = null
                                    "Sent command ${dispatch.id} to $targetPackage"
                                } else {
                                    dispatch.error ?: "Command was not sent"
                                }
                            },
                        ) {
                            Text(if (status.commandReady) "Send" else "Connecting…")
                        }
                    }
                    statusMessage?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.base),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            BaseCard {
                Text("Target: $targetPackage", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        status.commandReady -> "Connected and authenticated. Commands run only inside this target process."
                        status.isListening -> "Waiting for the authenticated target connection."
                        else -> "Target connection is not available."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.commandReady) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "There is no package, PID, attach, or spawn selector. This console cannot switch to another app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("Quick commands", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                quickCommands.forEach { quickCommand ->
                    AssistChip(
                        onClick = {
                            source = quickCommand.source
                            verifiedSource = null
                            statusMessage = quickCommand.description
                        },
                        label = { Text(quickCommand.label) },
                    )
                }
            }

            if (issues.isNotEmpty()) {
                BaseCard(containerColor = if (errors.isNotEmpty()) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer) {
                    issues.forEach { issue -> ScriptIssueRow(issue) }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (transcript.isEmpty()) {
                    item {
                        BaseCard {
                            Text("No commands yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Choose a quick command or enter JavaScript below. Each submitted script and its target result will appear here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(transcript, key = { it.id }) { entry ->
                    CommandTranscriptCard(
                        entry = entry,
                        onReuse = {
                            source = entry.source
                            verifiedSource = null
                            statusMessage = "Command loaded into the composer. Verify is optional; send when ready."
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandTranscriptCard(
    entry: CommandTranscriptEntry,
    onReuse: () -> Unit,
) {
    BaseCard {
        Text("Command", style = MaterialTheme.typography.labelLarge)
        Text(
            entry.source,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = Spacing.xs),
            style = MonoCodeStyle,
            maxLines = 40,
            overflow = TextOverflow.Ellipsis,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm))
        Text("Target result", style = MaterialTheme.typography.labelLarge)
        val result = entry.result
        if (result == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Waiting for the target…", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val rawText = if (result.ok) result.result ?: "undefined" else result.error ?: "Unknown target error"
            val displayText = if (result.ok) FridaScriptTools.formatResult(rawText) else rawText
            Text(
                displayText,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = Spacing.xs),
                style = MonoCodeStyle,
                color = if (result.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = onReuse) { Text("Reuse command") }
    }
}

@Composable
private fun ScriptIssueRow(issue: FridaScriptIssue) {
    val color = if (issue.isWarning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "${if (issue.isWarning) "Warning" else "Error"} · line ${issue.line}, column ${issue.column}: ${issue.message}",
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
        Text(
            "Suggestion: ${issue.suggestion}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
