package com.pixnpu.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pixnpu.engine.EngineStatus
import com.pixnpu.engine.GenerationParams
import com.pixnpu.engine.InferenceMetrics
import java.util.Locale
import android.os.Debug
import kotlinx.coroutines.delay

@Composable
fun RuntimeStatusBar(
    metrics: InferenceMetrics,
    params: GenerationParams,
    modifier: Modifier = Modifier,
) {
    var heap by remember { mutableStateOf(0L) }
    var nativeHeap by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            heap = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576L
            nativeHeap = Debug.getNativeHeapAllocatedSize() / 1048576L
            delay(2000)
        }
    }

    val statusColor = when (metrics.status) {
        EngineStatus.Generating -> MaterialTheme.colorScheme.primary
        EngineStatus.Loading -> MaterialTheme.colorScheme.tertiary
        EngineStatus.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val stateActive = metrics.status == EngineStatus.Generating || metrics.status == EngineStatus.Loading
    val stateGradient = if (metrics.status == EngineStatus.Generating) {
        gradient(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
    } else if (metrics.status == EngineStatus.Loading) {
        gradient(MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary)
    } else {
        null
    }

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusChip(
            label = "BACKEND",
            value = metrics.backend,
            valueColor = backendColor(metrics.backend),
            activeColor = backendGradient(metrics.backend),
        )
        StatusChip(
            label = "STATE",
            value = metrics.status.name,
            valueColor = statusColor,
            activeColor = stateGradient,
            active = stateActive,
        )
        StatusChip(label = "TTFT", value = metrics.ttftMs?.let { String.format(Locale.ROOT, "%.2fs", it / 1000.0) } ?: "—")
        StatusChip(label = "tok/s", value = String.format(Locale.ROOT, "%.1f", metrics.tokensPerSecond))
        StatusChip(label = "ctx", value = "${metrics.contextTokens}/${metrics.maxContextTokens}")
        StatusChip(label = "heap", value = "$heap MB")
        StatusChip(label = "native", value = "$nativeHeap MB")
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
private fun backendGradient(label: String): Brush = when {
    label.startsWith("NPU") -> gradient(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    label.startsWith("GPU") -> gradient(MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.primary)
    label.startsWith("CPU") -> gradient(
        MaterialTheme.colorScheme.onSurfaceVariant,
        MaterialTheme.colorScheme.surfaceVariant,
    )
    else -> gradient(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun gradient(start: Color, end: Color): Brush =
    Brush.horizontalGradient(listOf(start, end))

@Composable
private fun StatusChip(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    activeColor: Brush? = null,
    active: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val resolvedColor =
        if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else valueColor

    val pulse = if (active && activeColor != null) {
        val transition = rememberInfiniteTransition(label = "status-pulse")
        val alpha by transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "status-pulse-alpha",
        )
        alpha
    } else {
        null
    }

    Column(modifier = modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (activeColor != null) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(brush = activeColor, shape = RoundedCornerShape(2.dp))
                        .alpha(pulse ?: 1f)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(color = resolvedColor),
            )
        }
    }
}
