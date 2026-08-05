package com.pixnpu.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pixnpu.BuildConfig
import com.pixnpu.engine.GenerationParams
import com.pixnpu.engine.Modality
import com.pixnpu.engine.PromptTemplate
import java.util.Locale

/**
 * Settings bottom sheet: API server, multimodal input, generation params and
 * misc toggles. All values bind directly to MainViewModel StateFlows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    params: GenerationParams,
    systemPrompt: String,
    template: PromptTemplate,
    modality: Modality,
    isGenerating: Boolean,
    apiServerEnabled: Boolean,
    apiPort: Int,
    apiServerUrl: String,
    keepScreenOn: Boolean,
    onChangeParams: (GenerationParams) -> Unit,
    onChangeSystemPrompt: (String) -> Unit,
    onChangeTemplate: (PromptTemplate) -> Unit,
    onModalityChange: (Modality) -> Unit,
    onToggleApiServer: () -> Unit,
    onApiPortChange: (Int) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                    "Off — binds 127.0.0.1 only, never on the network"
                },
                checked = apiServerEnabled,
                onCheckedChange = { onToggleApiServer() },
            )
            PortRow(
                port = apiPort,
                onPortChange = onApiPortChange,
            )
            Text(
                text = "Endpoints: GET /v1/models · POST /v1/chat/completions",
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
            Spacer(Modifier.height(8.dp))
        }
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
private fun PortRow(
    port: Int,
    onPortChange: (Int) -> Unit,
) {
    var portText by remember(port) { mutableStateOf(port.toString()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Port", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Changing the port stops the server",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    }
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
