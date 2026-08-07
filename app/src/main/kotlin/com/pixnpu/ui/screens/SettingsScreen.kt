package com.pixnpu.ui.screens

import android.os.Process
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixnpu.BuildConfig
import com.pixnpu.engine.GenerationParams
import com.pixnpu.engine.Modality
import com.pixnpu.engine.PromptTemplate
import com.pixnpu.engine.SamplingPreset
import com.pixnpu.server.OpenAiApiServer
import com.pixnpu.util.AppLog
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Settings screen (pager tab): API server bind host/port, multimodal input,
 * sampling presets, generation params and misc toggles. All values bind
 * directly to MainViewModel StateFlows.
 */
@Composable
fun SettingsScreen(
    params: GenerationParams,
    systemPrompt: String,
    template: PromptTemplate,
    modality: Modality,
    isGenerating: Boolean,
    apiServerEnabled: Boolean,
    apiRouterEnabled: Boolean,
    apiHost: String,
    apiPort: Int,
    apiServerUrl: String,
    apiToken: String,
    keepScreenOn: Boolean,
    markdownEnabled: Boolean,
    presets: List<SamplingPreset>,
    onChangeParams: (GenerationParams) -> Unit,
    onChangeSystemPrompt: (String) -> Unit,
    onChangeTemplate: (PromptTemplate) -> Unit,
    onModalityChange: (Modality) -> Unit,
    onToggleApiServer: () -> Unit,
    onToggleApiRouter: (Boolean) -> Unit,
    onApiHostChange: (String) -> Unit,
    onApiPortChange: (Int) -> Unit,
    onApiTokenChange: (String) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onMarkdownEnabledChange: (Boolean) -> Unit,
    onCreatePreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onApplyPreset: (String) -> Unit,
    onResetSettings: () -> Unit,
) {
    var selectedPreset by remember { mutableStateOf<String?>(null) }
    var presetName by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(presets) {
        if (selectedPreset != null && presets.none { it.name == selectedPreset }) {
            selectedPreset = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleLarge,
        )

        SectionHeader("API Server")
        ToggleRow(
            title = "API Server",
            subtitle = if (apiServerEnabled) {
                "Running · $apiServerUrl"
            } else {
                "Off — loopback only by default"
            },
            checked = apiServerEnabled,
            onCheckedChange = { onToggleApiServer() },
        )
        ToggleRow(
            title = "Router Mode",
            subtitle = if (apiRouterEnabled) {
                "llama.cpp router — requests name any installed model and it is " +
                    "loaded on demand; the server can start without a loaded model"
            } else {
                "Serve the loaded model only (llama.cpp single-model mode)"
            },
            checked = apiRouterEnabled,
            onCheckedChange = onToggleApiRouter,
        )
        BindAddressRow(
            host = apiHost,
            port = apiPort,
            onHostChange = onApiHostChange,
            onPortChange = onApiPortChange,
        )
        OutlinedTextField(
            value = apiToken,
            onValueChange = { onApiTokenChange(it) },
            label = { Text("API Token (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = if (apiToken.isBlank()) {
                "No auth — clients can call without a key. Set a token to require " +
                    "Authorization: Bearer <token>."
            } else {
                "Requests must send Authorization: Bearer <token>. Changing it stops the server."
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!OpenAiApiServer.isLoopback(apiHost)) {
            Text(
                text = "Bound to $apiHost — reachable from your network. There is no auth, " +
                    "so only expose it on trusted networks.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = "Endpoints: /v1/chat/completions · /v1/models · /completion · /props · " +
                "/slots · /models[/load|/unload] · /tokenize · /detokenize",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

            SectionHeader("Multimodal Input")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Input Modality", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = modalityDescription(modality),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ModalityDropdown(
                    selectedModality = modality,
                    onModalityChange = onModalityChange,
                )
            }

            SectionHeader("Sampling Presets")
            var presetMenuExpanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Preset", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Saved temperature / Top-K / Top-P combinations",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Box {
                        TextButton(onClick = { presetMenuExpanded = true }) {
                            Text(selectedPreset ?: "Select…")
                        }
                        DropdownMenu(
                            expanded = presetMenuExpanded,
                            onDismissRequest = { presetMenuExpanded = false },
                        ) {
                            presets.forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(preset.name) },
                                    onClick = {
                                        selectedPreset = preset.name
                                        presetMenuExpanded = false
                                        onApplyPreset(preset.name)
                                    },
                                )
                            }
                            if (presets.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No presets yet") },
                                    onClick = {},
                                    enabled = false,
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text("Preset Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        if (presetName.isNotBlank()) {
                            onCreatePreset(presetName)
                            presetName = ""
                        }
                    },
                ) {
                    Text("Save", color = MaterialTheme.colorScheme.primary)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (selectedPreset != null) {
                        "Applying a preset updates Temperature, Top-K and Top-P"
                    } else {
                        "Select a preset to apply it, or save the current values"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        selectedPreset?.let {
                            onDeletePreset(it)
                            selectedPreset = null
                        }
                    },
                    enabled = selectedPreset != null,
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }

            SectionHeader("Generation Params")
            GenerationParamsSection(
                params = params,
                systemPrompt = systemPrompt,
                template = template,
                isGenerating = isGenerating,
                onChangeParams = onChangeParams,
                onChangeSystemPrompt = onChangeSystemPrompt,
                onChangeTemplate = onChangeTemplate,
            )

            SectionHeader("Other")
            ToggleRow(
                title = "Render Markdown",
                subtitle = "Format model replies with bold, headings, lists, quotes and code blocks",
                checked = markdownEnabled,
                onCheckedChange = onMarkdownEnabledChange,
            )
            ToggleRow(
                title = "Keep Screen On",
                subtitle = "Prevents the display from sleeping while the app is open",
                checked = keepScreenOn,
                onCheckedChange = onKeepScreenOnChange,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "App Version",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Reset Settings", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Restores defaults and deletes all custom presets",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = { confirmReset = true }) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset Settings?") },
            text = { Text("All settings and custom presets will be restored to defaults.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        onResetSettings()
                    },
                ) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun BindAddressRow(
    host: String,
    port: Int,
    onHostChange: (String) -> Unit,
    onPortChange: (Int) -> Unit,
) {
    var portText by remember(port) { mutableStateOf(port.toString()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = host,
            onValueChange = { onHostChange(it) },
            label = { Text("Bind Host") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = portText,
            onValueChange = { raw ->
                val digits = raw.filter(Char::isDigit).take(5)
                portText = digits
                val parsed = digits.toIntOrNull()
                if (parsed != null && digits.length == 4 && parsed in 1024..65535) {
                    onPortChange(parsed)
                }
            },
            label = { Text("Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .widthIn(min = 96.dp, max = 120.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModalityDropdown(
    selectedModality: Modality,
    onModalityChange: (Modality) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedButton(
            onClick = { expanded = true },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier
                .height(40.dp)
                .widthIn(min = 96.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                text = selectedModality.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(16.dp),
            )
        }
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Modality.entries.forEach { modality ->
                DropdownMenuItem(
                    onClick = {
                        onModalityChange(modality)
                        expanded = false
                    },
                    text = { Text(modality.label) },
                )
            }
        }
    }
}

private fun modalityDescription(modality: Modality): String = when {
    modality == Modality.Video ->
        "Frames on the GPU backend · audio on the CPU backend"
    modality.supportsVision && modality.supportsAudio ->
        "Images on the GPU backend · audio on the CPU backend"
    modality.supportsVision -> "Images via the GPU backend"
    modality.supportsAudio -> "Audio via the CPU backend"
    else -> "Text only — no extra backends"
}

@Composable
private fun GenerationParamsSection(
    params: GenerationParams,
    systemPrompt: String,
    template: PromptTemplate,
    isGenerating: Boolean,
    onChangeParams: (GenerationParams) -> Unit,
    onChangeSystemPrompt: (String) -> Unit,
    onChangeTemplate: (PromptTemplate) -> Unit,
) {
    var temperature by remember(params.temperature) { mutableStateOf(params.temperature) }
    var topK by remember(params.topK) { mutableStateOf(params.topK) }
    var topP by remember(params.topP) { mutableStateOf(params.topP) }
    var maxTokens by remember(params.maxTokens) { mutableStateOf(params.maxTokens) }
    var contextTokens by remember(params.contextTokens) { mutableStateOf(params.contextTokens) }
    var system by remember(systemPrompt) { mutableStateOf(systemPrompt) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ParamSlider(
            label = "Temperature",
            value = temperature,
            range = 0.0f..2.0f,
            steps = 39,
            display = String.format(Locale.ROOT, "%.2f", temperature),
            onValue = {
                temperature = it
                onChangeParams(params.copy(temperature = it))
            },
        )
        ParamSlider(
            label = "Top-K",
            value = topK.toFloat(),
            range = 1f..200f,
            steps = 198,
            display = topK.toString(),
            onValue = {
                topK = it.toInt()
                onChangeParams(params.copy(topK = topK))
            },
        )
        ParamSlider(
            label = "Top-P",
            value = topP,
            range = 0.0f..1.0f,
            steps = 99,
            display = String.format(Locale.ROOT, "%.2f", topP),
            onValue = {
                topP = it
                onChangeParams(params.copy(topP = it))
            },
        )
        ParamSlider(
            label = "Max Tokens",
            value = maxTokens.toFloat(),
            range = 128f..8192f,
            steps = 62,
            display = maxTokens.toString(),
            onValue = {
                maxTokens = it.toInt()
                onChangeParams(params.copy(maxTokens = maxTokens))
            },
        )
        ParamSlider(
            label = "Context Window",
            value = contextTokens.toFloat(),
            range = 1024f..32768f,
            steps = 30,
            display = contextTokens.toString(),
            onValue = {
                contextTokens = it.toInt()
                onChangeParams(params.copy(contextTokens = contextTokens))
            },
        )

        var expanded by remember { mutableStateOf(false) }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Box {
                TextButton(onClick = { expanded = true }) {
                    Text(text = "Template: ${template.label}")
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    PromptTemplate.entries.forEach { entry ->
                        DropdownMenuItem(
                            text = { Text(entry.label) },
                            onClick = {
                                onChangeTemplate(entry)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = system,
            onValueChange = {
                system = it
                onChangeSystemPrompt(it)
            },
            label = { Text("System Prompt") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            minLines = 2,
            maxLines = 4,
            textStyle = MaterialTheme.typography.bodyMedium,
        )

        Text(
            text = if (isGenerating) "⚠ Params Apply at the Start of the Next Prompt" else "Params Apply on Next Prompt",
            style = MaterialTheme.typography.labelMedium,
            color = if (isGenerating) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        AppLogSection()
    }
}

private enum class LogFilter(val label: String, val minLevel: Int) {
    All("All", 2),
    WarningsPlus("Warnings+", 5),
    Errors("Errors", 6),
}

@Composable
private fun AppLogSection() {
    LaunchedEffect(Unit) { AppLog.start() }
    val entries by AppLog.entries.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(LogFilter.All) }
    var atBottom by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    // Filtering 2000 entries on every log line is wasteful; derivedStateOf
    // only recomputes when the entries or the filter actually change.
    val visible by remember {
        derivedStateOf { entries.filter { it.level >= filter.minLevel } }
    }

    LaunchedEffect(listState, visible.size) {
        if (atBottom && visible.isNotEmpty()) {
            listState.scrollToItem(visible.lastIndex)
        }
    }
    LaunchedEffect(listState, visible.size) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.collect { lastVisible ->
            atBottom = lastVisible >= visible.lastIndex - 1
        }
    }

    SectionHeader("App Log")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LogFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(option.label) },
                )
            }
        }
        TextButton(onClick = { AppLog.clear() }, enabled = entries.isNotEmpty()) {
            Text("Clear")
        }
    }
    Text(
        text = "${visible.size} entries · process ${Process.myPid()}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        ) {
            items(visible, key = { it.id }) { entry ->
                LogEntryRow(entry)
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: AppLog.Entry) {
    val color = when (entry.priority) {
        'E', 'F' -> MaterialTheme.colorScheme.error
        'W' -> MaterialTheme.colorScheme.tertiary
        'I' -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(entry.timeMs)
    val line = "$time ${entry.priority} ${entry.tag}: ${entry.message}"
    val clipboard = LocalClipboardManager.current
    Text(
        text = line,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
            color = color,
        ),
        modifier = Modifier
            .fillMaxWidth()
            // Tap copies the entry's full logcat line to the clipboard.
            .clickable { clipboard.setText(AnnotatedString(line)) },
    )
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onValue: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
            Text(text = display, style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary))
        }
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = range,
            steps = steps,
        )
    }
}
