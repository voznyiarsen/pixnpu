package com.pixnpu.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.pixnpu.ui.VideoClip
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "VideoDecoder"

/** Cap the number of frames sampled from a video (engine caps content items at 10). */
private const val MAX_VIDEO_FRAMES = 8

/** Longest edge for a sampled frame, to keep the prompt compact. */
private const val MAX_FRAME_DIMENSION = 640

/** Cap the source video size (1 GB) before decoding. */
private const val MAX_SOURCE_VIDEO_BYTES = 1L * 1024 * 1024 * 1024

/**
 * Reads a video's display name and duration (for the attachment chip). Cheap —
 * no decoding happens here; frames and audio are extracted at send time.
 */
suspend fun readVideoClip(context: Context, uri: Uri): VideoClip? =
    withContext(Dispatchers.IO) {
        try {
            val name = context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "video.mp4"
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                VideoClip(uri = uri, name = name, durationMs = durationMs)
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read video metadata: $uri", e)
            null
        }
    }

/**
 * Samples evenly-spaced frames from a video and writes them as JPEG files in the
 * cache dir. Used with [pcm16ToWav] audio (see decodeAudioFileToPcm) to feed a
 * video to the model as ImageFile + AudioBytes content.
 */
suspend fun extractVideoFrames(context: Context, uri: Uri, outputDir: File): List<String> =
    withContext(Dispatchers.IO) {
        try {
            val sourceSize = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull() ?: 0L
            if (sourceSize > MAX_SOURCE_VIDEO_BYTES) {
                Log.w(TAG, "Video too large ($sourceSize bytes) — skipping frames")
                return@withContext emptyList()
            }

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationUs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val stepUs = if (durationUs > 0L) {
                    durationUs / (MAX_VIDEO_FRAMES + 1)
                } else {
                    500_000L
                }

                val files = mutableListOf<String>()
                for (i in 1..MAX_VIDEO_FRAMES) {
                    val timeUs = i * stepUs
                    if (durationUs > 0L && timeUs > durationUs) break
                    val bitmap = runCatching {
                        retriever.getScaledFrameAtTime(
                            timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            MAX_FRAME_DIMENSION,
                            MAX_FRAME_DIMENSION,
                        )
                    }.getOrNull() ?: continue
                    val file = File(outputDir, "video_${System.nanoTime()}_$i.jpg")
                    file.outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    bitmap.recycle()
                    files.add(file.absolutePath)
                }
                Log.d(TAG, "Extracted ${files.size} frames (duration ${durationUs / 1000} ms)")
                files
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame extraction failed: $uri", e)
            emptyList()
        }
    }
