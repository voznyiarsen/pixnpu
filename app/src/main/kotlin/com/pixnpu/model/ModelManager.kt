package com.pixnpu.model

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Manages local .litertlm model storage: resumable downloads into app-private storage,
 * SHA-256 verification, and POSIX path resolution for direct mmap() by the native engine.
 */
class ModelManager(private val context: Context) {

    private val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val _models = MutableStateFlow<List<LocalModel>>(emptyList())
    val models: StateFlow<List<LocalModel>> = _models.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val stopRequested = AtomicBoolean(false)
    private var downloadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        _models.value = scanModels()
    }

    fun startDownload(url: String, expectedSha256: String?) {
        if (downloadJob?.isActive == true) return
        val job = scope.launch { download(url, expectedSha256) }
        downloadJob = job
    }

    fun pause() {
        stopRequested.set(true)
    }

    fun cancel() {
        stopRequested.set(true)
        scope.launch {
            downloadJob?.cancelAndJoin()
            stopRequested.set(false)
            val pausedName =
                (_downloadState.value as? DownloadState.Paused)?.fileName
                    ?: (_downloadState.value as? DownloadState.Downloading)?.fileName
            if (pausedName != null) {
                File(modelsDir, "$pausedName.part").delete()
            }
            _downloadState.value = DownloadState.Idle
        }
    }

    fun delete(model: LocalModel): Boolean {
        val file = File(model.absolutePath)
        val deleted = file.delete()
        File(modelsDir, "${file.name}.sha256").delete()
        refresh()
        return deleted
    }

    suspend fun verify(model: LocalModel): String? {
        val hash = withContext(Dispatchers.IO) {
            hashFile(File(model.absolutePath)) { _ -> }
        }
        if (hash != null) {
            File(modelsDir, "${File(model.absolutePath).name}.sha256").writeText(hash)
            refresh()
        }
        return hash
    }

    private fun scanModels(): List<LocalModel> {
        val files = modelsDir.listFiles { f ->
            f.isFile && f.extension.equals("litertlm", ignoreCase = true)
        }.orEmpty()
        return files.map { file ->
            val sha = readSidecarHash(file)
            LocalModel(
                name = file.name,
                absolutePath = file.absolutePath,
                fileSizeBytes = file.length(),
                lastModified = file.lastModified(),
                sha256 = sha,
                verified = sha != null,
            )
        }.sortedBy { it.name }
    }

    private fun readSidecarHash(file: File): String? {
        val sidecar = File(modelsDir, "${file.name}.sha256")
        return if (sidecar.exists() && sidecar.length() > 0) {
            sidecar.readText().trim().lowercase(Locale.ROOT).ifBlank { null }
        } else {
            null
        }
    }

    private suspend fun download(url: String, expectedSha256: String?) {
        val fileName = deriveFileName(url)
        val partFile = File(modelsDir, "$fileName.part")
        stopRequested.set(false)

        try {
            var received = partFile.length()
            var totalBytes: Long? = null

            if (received > 0) {
                _downloadState.value = DownloadState.Paused(
                    fileName = fileName,
                    bytesReceived = received,
                )
            }

            val requestBuilder = Request.Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
            if (received > 0) {
                requestBuilder.header("Range", "bytes=$received-")
            }

            _downloadState.value = DownloadState.Downloading(
                url = url,
                fileName = fileName,
                bytesReceived = received,
                totalBytes = null,
                bytesPerSecond = 0,
            )

            if (stopRequested.get()) {
                _downloadState.value = DownloadState.Paused(fileName, received)
                return
            }

            var windowStartNs = System.nanoTime()
            var windowStartBytes = received

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.code != 200 && response.code != 206) {
                    _downloadState.value =
                        DownloadState.Failed(fileName, "Server returned HTTP ${response.code}")
                    return
                }
                val body = response.body ?: throw IllegalStateException("Empty response body")
                when (response.code) {
                    206 -> totalBytes = response.header("Content-Length")
                        ?.toLongOrNull()?.let { received + it }
                    else -> totalBytes = response.headers["Content-Length"]?.toLongOrNull()
                }

                val randomAccessFile = RandomAccessFile(partFile, "rw")
                if (response.code == 200) {
                    randomAccessFile.setLength(0L)
                    received = 0
                    windowStartBytes = 0
                } else {
                    randomAccessFile.seek(received)
                }

                body.byteStream().use { input ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (stopRequested.get()) {
                            randomAccessFile.close()
                            _downloadState.value = DownloadState.Paused(fileName, received)
                            return
                        }
                        val read = input.read(buf)
                        if (read < 0) break
                        randomAccessFile.write(buf, 0, read)
                        received += read

                        val nowNs = System.nanoTime()
                        val elapsedSecs = (nowNs - windowStartNs) / 1_000_000_000.0
                        if (elapsedSecs >= 0.2) {
                            val speed =
                                ((received - windowStartBytes) / elapsedSecs).toLong()
                            _downloadState.value = DownloadState.Downloading(
                                url = url,
                                fileName = fileName,
                                bytesReceived = received,
                                totalBytes = totalBytes,
                                bytesPerSecond = speed,
                            )
                            windowStartNs = nowNs
                            windowStartBytes = received
                        }
                    }
                }
                randomAccessFile.close()
            }

            if (stopRequested.get()) {
                val state = _downloadState.value
                if (state is DownloadState.Downloading) {
                    _downloadState.value =
                        DownloadState.Paused(fileName, state.bytesReceived)
                }
                return
            }

            _downloadState.value = DownloadState.Verifying(
                fileName = fileName,
                bytesRead = 0,
                totalBytes = partFile.length(),
            )

            val actualHash = hashFile(partFile) { bytesRead ->
                val total = partFile.length()
                _downloadState.value = DownloadState.Verifying(
                    fileName = fileName,
                    bytesRead = bytesRead,
                    totalBytes = total,
                )
            } ?: ""

            if (expectedSha256 != null && !expectedSha256.equals(actualHash, ignoreCase = true)) {
                partFile.delete()
                _downloadState.value = DownloadState.Failed(
                    fileName,
                    "Checksum mismatch: expected $expectedSha256, got $actualHash",
                )
                return
            }

            val finalFile = File(modelsDir, fileName)
            partFile.renameTo(finalFile)
            File(modelsDir, "$fileName.sha256").writeText(actualHash.lowercase(Locale.ROOT))
            refresh()
            _downloadState.value = DownloadState.Complete(fileName, finalFile.absolutePath)
        } catch (e: Exception) {
            _downloadState.value = DownloadState.Failed(fileName, e.message ?: e.javaClass.simpleName)
        } finally {
            stopRequested.set(false)
        }
    }

    private fun deriveFileName(url: String): String {
        val cleaned = url.substringAfterLast('/').trim().ifBlank { "model.litertlm" }
        return if (cleaned.endsWith(".litertlm", ignoreCase = true)) cleaned else "$cleaned.litertlm"
    }

    private fun hashFile(file: File, onProgress: (Long) -> Unit): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                var lastEmit = 0L
                while (true) {
                    val read = raf.read(buf)
                    if (read < 0) break
                    digest.update(buf, 0, read)
                    total += read
                    if (total - lastEmit >= PROGRESS_EMIT_BYTES) {
                        onProgress(total)
                        lastEmit = total
                    }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun cleanupOldParts() {
        modelsDir.listFiles { f -> f.name.endsWith(".part") }.orEmpty().forEach { it.delete() }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_EMIT_BYTES = 4 * 1024 * 1024L
    }
}