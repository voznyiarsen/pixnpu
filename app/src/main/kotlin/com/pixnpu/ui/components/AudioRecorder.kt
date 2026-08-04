package com.pixnpu.ui.components

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pixnpu.ui.AudioClip
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "AudioRecorder"

/** Sample rate required by LiteRT-LM audio models (16-bit PCM mono). */
const val AUDIO_SAMPLE_RATE = 16000

/** Hard cap on clip length; matches gallery's MAX_AUDIO_CLIP_DURATION_SEC. */
const val MAX_AUDIO_CLIP_DURATION_MS = 30_000L

/** Converts raw PCM16 mono bytes (at [AUDIO_SAMPLE_RATE]) to milliseconds. */
fun pcmBytesToDurationMs(bytes: ByteArray): Long =
    (bytes.size / 2L * 1000L / AUDIO_SAMPLE_RATE).coerceAtLeast(0L)

/**
 * Records a voice clip to raw PCM16 mono bytes at 16 kHz using [AudioRecord],
 * the format expected by [com.google.ai.edge.litertlm.Content.AudioBytes].
 * Mirrors the gallery's recorder (16 kHz mono PCM16, 30 s cap).
 */
class AudioRecorder(private val scope: CoroutineScope) {

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs

    private var audioRecord: AudioRecord? = null
    private var stream = ByteArrayOutputStream()
    private var job: Job? = null

    val isRecording: Boolean get() = job?.isActive == true

    @SuppressLint("MissingPermission") // Permission checked by the caller before start().
    fun start(onMaxDurationReached: () -> Unit = {}) {
        if (isRecording) return
        val minBufferSize =
            AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBufferSize <= 0) {
            Log.e(TAG, "Failed to get min buffer size ($minBufferSize)")
            return
        }
        audioRecord?.release()
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize,
        )
        audioRecord = recorder
        stream = ByteArrayOutputStream()
        _elapsedMs.value = 0L
        job = scope.launch(Dispatchers.IO) {
            try {
                recorder.startRecording()
                val buffer = ByteArray(minBufferSize)
                val startMs = System.currentTimeMillis()
                var lastPublished = 0L
                while (isActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val bytesRead = recorder.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        stream.write(buffer, 0, bytesRead)
                    }
                    val elapsed = System.currentTimeMillis() - startMs
                    if (elapsed - lastPublished >= 100) {
                        _elapsedMs.value = elapsed
                        lastPublished = elapsed
                    }
                    if (elapsed >= MAX_AUDIO_CLIP_DURATION_MS) {
                        stop()
                        onMaxDurationReached()
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording failed", e)
            }
        }
    }

    /** Stops recording and returns the recorded bytes (empty if nothing recorded). */
    fun stop(): ByteArray {
        job?.cancel()
        job = null
        val recorder = audioRecord
        if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { recorder.stop() }
        }
        recorder?.release()
        audioRecord = null
        val bytes = stream.toByteArray()
        stream.reset()
        Log.d(TAG, "Stopped recording, ${bytes.size} bytes")
        return bytes
    }

    fun release() {
        job?.cancel()
        job = null
        audioRecord?.let {
            if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { it.stop() }
            }
            it.release()
        }
        audioRecord = null
    }
}

/**
 * Compact recording panel shown in the composer while recording: close button,
 * pulsing dot + elapsed seconds, and a send button that stops recording and
 * returns the clip. Auto-stops at [MAX_AUDIO_CLIP_DURATION_MS].
 */
@Composable
fun AudioRecorderPanel(
    recorder: AudioRecorder,
    onSend: (AudioClip) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val elapsedMs by recorder.elapsedMs.collectAsState()
    val elapsedSeconds = String.format(Locale.ROOT, "%.1f", elapsedMs / 1000f)
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose { recorder.release() }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onClose,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Cancel recording",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (recorder.isRecording) {
                    "Recording $elapsedSeconds s"
                } else {
                    "Tap the mic button to start"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    scope.launch {
                        if (recorder.isRecording) {
                            val bytes = recorder.stop()
                            if (bytes.isNotEmpty()) {
                                onSend(AudioClip(bytes = bytes, durationMs = pcmBytesToDurationMs(bytes)))
                            }
                        } else {
                            recorder.start()
                        }
                    }
                },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    imageVector = if (recorder.isRecording) Icons.Rounded.ArrowUpward else Icons.Rounded.Mic,
                    contentDescription = if (recorder.isRecording) "Send audio clip" else "Start recording",
                    tint = Color.White,
                )
            }
        }
    }
}
