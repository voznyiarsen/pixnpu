package com.pixnpu.util

import android.os.Process
import android.util.Log
import java.util.regex.Pattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * In-app ring buffer of logcat entries for our own process, surfaced in the
 * Settings tab as a scrolling log. Uses `logcat --pid` so native LiteRT-LM
 * output (NPU dispatch, TFLite, miniaudio) is captured too, not just
 * android.util.Log calls.
 */
object AppLog {

    data class Entry(
        val id: Long,
        val timeMs: Long,
        val priority: Char,
        val tag: String,
        val message: String,
    ) {
        val isError: Boolean get() = priority == 'E' || priority == 'F'

        val level: Int
            get() = when (priority) {
                'F' -> 7
                'E' -> 6
                'W' -> 5
                'I' -> 4
                'D' -> 3
                'V' -> 2
                else -> 1
            }
    }

    const val MAX_ENTRIES = 2000

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val nextId = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile
    private var started = false

    private var scope: CoroutineScope? = null

    /** Starts streaming logcat for this process. Safe to call repeatedly. */
    fun start() {
        if (started) return
        started = true
        val pid = Process.myPid()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = appScope
        appScope.launch {
            try {
                val process = ProcessBuilder("logcat", "-v", "threadtime", "--pid=$pid")
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        append(parseLine(line))
                    }
                }
                Log.w("AppLog", "logcat stream ended")
            } catch (e: Exception) {
                Log.e("AppLog", "logcat collector failed", e)
            }
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    internal fun append(entry: Entry?) {
        if (entry == null) return
        _entries.update { (it + entry.copy(id = nextId.incrementAndGet())).takeLast(MAX_ENTRIES) }
    }

    private val threadtimePattern: Pattern = Pattern.compile(
        "^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+(.*?): (.*)$",
    )

    internal fun parseLine(line: String): Entry? {
        val matcher = threadtimePattern.matcher(line)
        if (!matcher.matches()) return null
        val priority = matcher.group(1)[0]
        val tag = matcher.group(2)
        val message = matcher.group(3)
        return Entry(
            id = 0L,
            timeMs = System.currentTimeMillis(),
            priority = priority,
            tag = tag,
            message = message,
        )
    }
}
