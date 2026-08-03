package com.pixnpu.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pixnpu.engine.GenerationParams
import com.pixnpu.engine.PromptTemplate
import com.pixnpu.ui.theme.TerminalAccent
import com.pixnpu.ui.theme.TerminalPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParameterSheet(
    params: GenerationParams,
    systemPrompt: String,
    template: PromptTemplate,
    isGenerating: Boolean,
    onChangeParams: (GenerationParams) -> Unit,
    onChangeSystemPrompt: (String) -> Unit,
    onChangeTemplate: (PromptTemplate) -> Unit,
    onDismiss: () -> Unit,
) {
    var temperature by remember(params.temperature) { mutableStateOf(params.temperature) }
    var topK by remember(params.topK) { mutableStateOf(params.topK) }
    var topP by remember(params.topP) { mutableStateOf(params.topP) }
    var maxTokens by remember(params.maxTokens) { mutableStateOf(params.maxTokens) }
    var contextTokens by remember(params.contextTokens) { mutableStateOf(params.contextTokens) }
    var system by remember(systemPrompt) { mutableStateOf(systemPrompt) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "generation::params",
                style = MaterialTheme.typography.labelMedium,
                color = TerminalAccent,
            )

            ParamSlider(
                label = "Temperature",
                value = temperature,
                range = 0.0f..2.0f,
                steps = 39,
                display = String.format(java.util.Locale.ROOT, "%.2f", temperature),
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
                display = String.format(java.util.Locale.ROOT, "%.2f", topP),
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
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { expanded = true }) {
                        Text(
                            text = "template: ${template.label}",
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        PromptTemplate.entries.forEach { entry ->
                            DropdownMenuItem(
                                text = { Text(entry.label, fontFamily = FontFamily.Monospace) },
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
                label = { Text("system prompt") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                minLines = 2,
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = if (isGenerating) "⚠ params apply at the start of the next prompt" else "params apply on next prompt",
                style = MaterialTheme.typography.labelMedium,
                color = if (isGenerating) TerminalAccent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
            Text(text = display, style = MaterialTheme.typography.bodySmall.copy(color = TerminalPrimary))
        }
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = range,
            steps = steps,
        )
    }
}