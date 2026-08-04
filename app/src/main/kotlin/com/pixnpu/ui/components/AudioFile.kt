package com.pixnpu.ui.components

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.pixnpu.ui.AudioClip
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AudioFileDecoder"

/** Target format for model audio input: 16 kHz mono 16-bit PCM (Gemma 3n). */
private const val AUDIO_TARGET_SAMPLE_RATE = 16_000

/** Cap the decoded clip at 5 minutes (~9.6 MB of PCM16 @ 16 kHz). */
private const val MAX_DECODED_PCM_BYTES = 5 * 60 * AUDIO_TARGET_SAMPLE_RATE * 2

/** Cap the source file size (100 MB) before decoding. */
private const val MAX_SOURCE_FILE_BYTES = 100L * 1024 * 1024

sealed class AudioFileDecodeResult {
    data class Success(val clip: AudioClip) : AudioFileDecodeResult()
    data class Failure(val message: String) : AudioFileDecodeResult()
}

/**
 * Decodes an arbitrary audio file (mp3/m4a/ogg/wav picked from storage) into the
 * raw 16 kHz mono 16-bit PCM format that `Content.AudioBytes` expects, using
 * MediaExtractor + MediaCodec followed by manual downmix/resample.
 */
suspend fun decodeAudioFileToPcm(context: Context, uri: Uri): AudioFileDecodeResult =
    withContext(Dispatchers.IO) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        try {
            val sourceSize = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull() ?: 0L
            if (sourceSize > MAX_SOURCE_FILE_BYTES) {
                return@withContext AudioFileDecodeResult.Failure(
                    "Audio file too large (max ${MAX_SOURCE_FILE_BYTES / (1024 * 1024)} MB)",
                )
            }

            extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
            var audioTrack = -1
            for (i in 0 until extractor!!.trackCount) {
                val mime = extractor!!.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioTrack = i
                    break
                }
            }
            if (audioTrack < 0) {
                return@withContext AudioFileDecodeResult.Failure("No audio track found in file")
            }
            extractor!!.selectTrack(audioTrack)
            val format = extractor!!.getTrackFormat(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: return@withContext AudioFileDecodeResult.Failure("Unsupported audio format")

            decoder = MediaCodec.createDecoderByType(mime)
            decoder!!.configure(format, null, null, 0)
            decoder!!.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            val output = ByteArrayOutputStream()
            val timeoutUs = 10_000L

            while (!outputEnded) {
                if (!inputEnded) {
                    val inIndex = decoder!!.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inBuf = decoder!!.getInputBuffer(inIndex)!!
                        val sampleSize = extractor!!.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            decoder!!.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            decoder!!.queueInputBuffer(
                                inIndex, 0, sampleSize, extractor!!.sampleTime, 0,
                            )
                            extractor!!.advance()
                        }
                    }
                }
                when (val outIndex = decoder!!.dequeueOutputBuffer(bufferInfo, timeoutUs)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = decoder!!.outputFormat
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> {
                        if (outIndex >= 0) {
                            val outBuf = decoder!!.getOutputBuffer(outIndex)!!
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.get(chunk)
                            decoder!!.releaseOutputBuffer(outIndex, false)
                            output.write(chunk)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputEnded = true
                            }
                        }
                    }
                }
                if (output.size() > MAX_DECODED_PCM_BYTES) {
                    outputEnded = true
                }
            }

            var pcm = output.toByteArray()
            if (pcm.size % 2 != 0) pcm = pcm.copyOf(pcm.size - 1)
            if (pcm.isEmpty()) {
                return@withContext AudioFileDecodeResult.Failure("Decoded audio is empty")
            }
            if (channels != 1) pcm = downmixToMono(pcm, channels)
            if (sampleRate != AUDIO_TARGET_SAMPLE_RATE) {
                pcm = resample(pcm, sampleRate, AUDIO_TARGET_SAMPLE_RATE)
            }
            AudioFileDecodeResult.Success(
                AudioClip(
                    bytes = pcm,
                    durationMs = pcmBytesToDurationMs(pcm),
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Audio decode failed", e)
            AudioFileDecodeResult.Failure("Could not decode audio: ${e.message}")
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor?.release() }
        }
    }

/** Averages interleaved multi-channel 16-bit samples into mono. */
internal fun downmixToMono(pcm: ByteArray, channels: Int): ByteArray {
    if (channels <= 1) return pcm
    val out = ByteArray(pcm.size / channels)
    var o = 0
    var i = 0
    while (i + 2 * channels <= pcm.size) {
        var sum = 0
        for (c in 0 until channels) {
            sum += shortAt(pcm, i / 2 + c)
        }
        val avg = (sum / channels).toShort()
        out[o++] = (avg.toInt() and 0xFF).toByte()
        out[o++] = ((avg.toInt() shr 8) and 0xFF).toByte()
        i += 2 * channels
    }
    return out
}

/** Linearly resamples 16-bit mono PCM from [fromRate] to [toRate] Hz. */
internal fun resample(pcm: ByteArray, fromRate: Int, toRate: Int): ByteArray {
    if (fromRate == toRate) return pcm
    val inSamples = pcm.size / 2
    val outSamples = (inSamples.toLong() * toRate / fromRate).toInt()
    val out = ByteArray(outSamples * 2)
    val ratio = fromRate.toDouble() / toRate.toDouble()
    var o = 0
    for (n in 0 until outSamples) {
        val pos = n * ratio
        val i0 = pos.toInt()
        val i1 = (i0 + 1).coerceAtMost(inSamples - 1)
        val frac = pos - i0
        val interpolated = (
            shortAt(pcm, i0) + (shortAt(pcm, i1) - shortAt(pcm, i0)) * frac
            ).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        out[o++] = (interpolated and 0xFF).toByte()
        out[o++] = ((interpolated shr 8) and 0xFF).toByte()
    }
    return out
}

private fun shortAt(pcm: ByteArray, sampleIndex: Int): Short {
    val lo = pcm[sampleIndex * 2].toInt() and 0xFF
    val hi = pcm[sampleIndex * 2 + 1].toInt() shl 8
    return (lo or hi).toShort()
}
