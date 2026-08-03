package com.pixnpu.ui.components

import android.os.Debug
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pixnpu.engine.EngineStatus
import com.pixnpu.engine.GenerationParams
import com.pixnpu.engine.InferenceMetrics
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun RuntimeStatusBar(
    metrics: InferenceMetrics,
    params: GenerationParams,
    modifier: Modifier = Modifier,
) {
    var heap by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            heap = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576L
            delay(2000)
        }
    }

    val statusColor = when (metrics.status) {
        EngineStatus.Generating -> MaterialTheme.colorScheme.primary
        EngineStatus.Loading -> MaterialTheme.colorScheme.tertiary
        EngineStatus.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusChip(label = "BACKEND", value = metrics.backend, valueColor = backendColor(metrics.backend))
        StatusChip(label = "STATE", value = metrics.status.name, valueColor = statusColor)
        StatusChip(
            label = "TTFT",
            value = metrics.ttftMs?.let { String.format(Locale.ROOT, "%.2fs", it / 1000.0) } ?: "—",
        )
        StatusChip(label = "tok/s", value = String.format(Locale.ROOT, "%.1f", metrics.tokensPerSecond))
        StatusChip(
            label = "ctx",
            value = "${metrics.contextTokens}/${metrics.maxContextTokens}",
        )
        StatusChip(label = "heap", value = "$heap MB")
        StatusChip(label = "temp", value = String.format(Locale.ROOT, "%.2f", params.temperature))
        StatusChip(label = "top-k", value = params.topK.toString())
        StatusChip(label = "top-p", value = String.format(Locale.ROOT, "%.2f", params.topP))
        StatusChip(label = "max-tok", value = params.maxTokens.toString())
    }
}

@Composable
private fun backendColor(label: String): Color = when {
    label.startsWith("NPU") -> MaterialTheme.colorScheme.primary
    label.startsWith("GPU") -> MaterialTheme.colorScheme.tertiary
    label.startsWith("CPU") -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun StatusChip(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    val resolvedColor =
        if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else valueColor
    Column(modifier = modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(color = resolvedColor),
        )
    }
}