package com.pixnpu.model

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.pixnpu.util.CircuitBreaker
import com.pixnpu.util.Fmt
/**
 * Manages local .litertlm model storage: resumable segmented downloads into app-private
 * storage, SHA-256 verification, SAF imports, and POSIX path resolution for direct mmap()
 * by the native engine.
 */
class ModelManager(private val context: Context) : ModelManagerInterface {

    private val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _models = MutableStateFlow<List<LocalModel>>(emptyList())
    override val models: StateFlow<List<LocalModel>> = _models.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    override val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    // Circuit breakers for different operations
    private val downloadCircuitBreaker = CircuitBreaker(maxFailures = 3, cooldownMs = 30000)
    private val importCircuitBreaker = CircuitBreaker(maxFailures = 3, cooldownMs = 30000)

    private val stopRequested = AtomicBoolean(false)
    private var operationJob: Job? = null

    init {
        refresh()
    }

    override fun refresh() {
        _models.value = scanModels()
    }

    override fun startDownload(url: String, expectedSha256: String?) {
        try {
            validateDownloadUrl(url, expectedSha256)
        } catch (e: IllegalArgumentException) {
            _downloadState.value = DownloadState.Failed("url", e.message ?: "Invalid URL")
            return
        }
        
        if (operationJob?.isActive == true) {
            Log.w("ModelManager", "Download already in progress, ignoring start request")
            return
        }
        if (!downloadCircuitBreaker.canExecute()) {
            val state = downloadCircuitBreaker.getState()
            Log.w("ModelManager", "Download circuit breaker is ${state.name}, ignoring request")
            _downloadState.value = DownloadState.Failed(
                "download",
                "Too many download failures. Please wait before retrying."
            )
            return
        }
        Log.d("ModelManager", "Starting download: $url")
        val job = scope.launch {
            try {
                download(url, expectedSha256)
                downloadCircuitBreaker.recordSuccess()
            } catch (e: Exception) {
                downloadCircuitBreaker.recordFailure()
                throw e
            }
        }
        operationJob = job
    }

    /**
     * Maximum URL length to prevent DoS
     */
    private val maxUrlLength = 2048

    /**
     * Maximum expected SHA-256 hash length (64 hex chars)
     */
    private val maxSha256Length = 64

    /**
     * Maximum model file size in bytes (10 GB)
     */
    private val maxModelSizeBytes = 10L * 1024 * 1024 * 1024

    /**
     * Minimum free space required in bytes (500 MB)
     */
    private val minFreeSpaceBytes = 500L * 1024 * 1024

    /**
     * Validate download URL and SHA-256 before starting download
     */
    private fun validateDownloadUrl(url: String, expectedSha256: String?) {
        require(url.isNotBlank()) { "URL cannot be blank" }
        require(url.length <= maxUrlLength) { "URL exceeds maximum length of $maxUrlLength characters" }
        require(url.startsWith("http://") || url.startsWith("https://")) { "URL must start with http:// or https://" }
        
        // Validate SHA-256 if provided
        expectedSha256?.let { sha ->
            require(sha.isNotBlank()) { "SHA-256 hash cannot be blank" }
            require(sha.length <= maxSha256Length) { "SHA-256 hash exceeds maximum length of $maxSha256Length characters" }
            require(sha.matches(Regex("^[a-fA-F0-9]*$"))) { "SHA-256 hash contains invalid characters (must be hex)" }
        }
    }

    /**
     * Check if there's enough free space for a download
     */
    private fun hasEnoughFreeSpace(requiredBytes: Long): Boolean {
        val stat = StatFs(modelsDir.absolutePath)
        return stat.availableBytes >= requiredBytes + minFreeSpaceBytes
    }

    /**
     * Validate model file size before import
     */
    private fun validateImportSize(fileSizeBytes: Long?) {
        fileSizeBytes?.let { size ->
            require(size <= maxModelSizeBytes) { 
                "Model file exceeds maximum size of ${maxModelSizeBytes / (1024 * 1024 * 1024)} GB" 
            }
            require(hasEnoughFreeSpace(size)) { 
                "Not enough free space. Need at least ${minFreeSpaceBytes / (1024 * 1024)} MB free." 
            }
        }
    }

    override fun importModel(uri: Uri) {
        if (operationJob?.isActive == true) {
            Log.w("ModelManager", "Operation already in progress, ignoring import request")
            return
        }
        if (!importCircuitBreaker.canExecute()) {
            val state = importCircuitBreaker.getState()
            Log.w("ModelManager", "Import circuit breaker is ${state.name}, ignoring request")
            _downloadState.value = DownloadState.Failed(
                "import",
                "Too many import failures. Please wait before retrying."
            )
            return
        }
        Log.d("ModelManager", "Starting import: $uri")
        val job = scope.launch {
            try {
                import(uri)
                importCircuitBreaker.recordSuccess()
            } catch (e: Exception) {
                importCircuitBreaker.recordFailure()
                throw e
            }
        }
        operationJob = job
    }

    override fun pause() {
        stopRequested.set(true)
    }

    override fun isCancelled(): Boolean = stopRequested.get()

    override fun cancel() {
        stopRequested.set(true)
        scope.launch {
            operationJob?.cancelAndJoin()
            stopRequested.set(false)
            val fileName = when (val state = _downloadState.value) {
                is DownloadState.Downloading -> state.fileName
                is DownloadState.Verifying -> state.fileName
                is DownloadState.Importing -> state.fileName
                is DownloadState.Paused -> state.fileName
                else -> null
            }
            if (fileName != null) {
                File(modelsDir, "$fileName.part").delete()
                File(modelsDir, "$fileName.part.map").delete()
                File(modelsDir, "$fileName.part.meta").delete()
                File(modelsDir, "$fileName.importing").delete()
            }
            _downloadState.value = DownloadState.Idle
        }
    }

    override fun delete(model: LocalModel): Boolean {
        val file = File(model.absolutePath)
        val deleted = file.delete()
        File(modelsDir, "${file.name}.sha256").delete()
        refresh()
        if (deleted) {
            Log.d("ModelManager", "Deleted model: ${model.name}")
        } else {
            Log.w("ModelManager", "Failed to delete model: ${model.name}")
        }
        return deleted
    }

    override suspend fun verify(model: LocalModel, isCancelled: () -> Boolean): String? {
        val file = File(model.absolutePath)
        val totalBytes = file.length()
        Log.d("ModelManager", "Verifying model: ${model.name} (${Fmt.bytes(totalBytes)})")
        _downloadState.value = DownloadState.Verifying(model.name, 0, totalBytes)
        val hash = withContext(Dispatchers.IO) {
            hashFile(file, isCancelled = isCancelled) { bytesRead ->
                _downloadState.value = DownloadState.Verifying(model.name, bytesRead, totalBytes)
            }
        }
        if (hash == null) {
            if (!isCancelled()) {
                Log.w("ModelManager", "Verification failed (read error): ${model.name}")
                _downloadState.value =
                    DownloadState.Failed(model.name, "Failed to read file for verification")
            }
        } else {
            File(modelsDir, "${file.name}.sha256").writeText(hash)
            refresh()
            Log.d("ModelManager", "Verification complete: ${model.name}, sha256=${hash.take(16)}...")
            _downloadState.value = DownloadState.Complete(model.name, file.absolutePath)
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

    // ------------------------------------------------------------------ download

    private suspend fun download(url: String, expectedSha256: String?) {
        stopRequested.set(false)
        val fileName = deriveFileName(url)
        val partFile = File(modelsDir, "$fileName.part")
        val mapFile = File(modelsDir, "$fileName.part.map")
        val metaFile = File(modelsDir, "$fileName.part.meta")

        if (partFile.exists() && partFile.length() > 0) {
        _downloadState.value = DownloadState.Paused(fileName, partFile.length())
        }

        try {
            if (partFile.exists() && !metaMatches(metaFile, url, expectedSha256)) {
                partFile.delete()
                mapFile.delete()
                metaFile.delete()
            }
            writeMeta(metaFile, url, expectedSha256)

            val probe = client.newCall(
                Request.Builder().url(url)
                    .header("Accept-Encoding", "identity")
                    .header("Range", "bytes=0-0")
                    .build()
            ).execute()
            val (totalBytes, rangeSupported) = probe.use { resp ->
                when (resp.code) {
                    200 -> {
                        val total = resp.headers["Content-Length"]?.toLongOrNull()
                        (total?.takeIf { it > 0 }) to false
                    }
                    206 -> {
                        val total = resp.header("Content-Range")
                            ?.substringAfter('/')
                            ?.toLongOrNull()
                        (total?.takeIf { it > 0 }) to (total != null)
                    }
                    else -> {
                        _downloadState.value =
                            DownloadState.Failed(fileName, "Server returned HTTP ${resp.code}")
                        return
                    }
                }
            }

            if (totalBytes != null && !hasSpaceFor(totalBytes, partFile.length())) {
                _downloadState.value = DownloadState.Failed(fileName, "Not enough storage space")
                return
            }

            var attempt = 1
            var lastError: String? = null
            while (attempt <= MAX_ATTEMPTS) {
                if (attempt > 1) {
                    _downloadState.value = DownloadState.Downloading(
                        url = url,
                        fileName = fileName,
                        bytesReceived = partFile.length(),
                        totalBytes = totalBytes,
                        bytesPerSecond = 0,
                        attempt = attempt,
                        maxAttempts = MAX_ATTEMPTS,
                    )
                    delay(backoffMs(attempt))
                    if (stopRequested.get()) {
                        _downloadState.value =
                            DownloadState.Paused(fileName, partFile.length(), totalBytes)
                        return
                    }
                }

                val outcome = if (rangeSupported && totalBytes != null) {
                    downloadSegmented(url, fileName, partFile, mapFile, totalBytes, attempt)
                } else {
                    downloadStream(url, fileName, partFile, totalBytes, rangeSupported, attempt)
                }

                when (outcome) {
                    Outcome.Success -> {
                        if (!finalize(fileName, partFile, mapFile, metaFile, expectedSha256)) {
                            _downloadState.value =
                                DownloadState.Paused(fileName, partFile.length(), totalBytes)
                        }
                        return
                    }
                    Outcome.Paused -> {
                        _downloadState.value =
                            DownloadState.Paused(fileName, partFile.length(), totalBytes)
                        return
                    }
                    is Outcome.Retryable -> {
                        lastError = outcome.message
                        attempt++
                    }
                    is Outcome.Fatal -> {
                        _downloadState.value = DownloadState.Failed(fileName, outcome.message)
                        return
                    }
                }
            }
            _downloadState.value = DownloadState.Failed(
                fileName,
                lastError ?: "Download failed after $MAX_ATTEMPTS attempts",
            )
            Log.w("ModelManager", "Download failed after $MAX_ATTEMPTS attempts for: $fileName")
        } catch (e: Exception) {
            Log.e("ModelManager", "Download exception for $fileName", e)
            _downloadState.value =
                DownloadState.Failed(fileName, e.message ?: e.javaClass.simpleName)
        } finally {
            stopRequested.set(false)
        }
    }

    private suspend fun downloadSegmented(
        url: String,
        fileName: String,
        partFile: File,
        mapFile: File,
        totalBytes: Long,
        attempt: Int,
    ): Outcome {
        val chunkSize = CHUNK_SIZE
        val numChunks = ((totalBytes - 1) / chunkSize) + 1
        if (numChunks > Int.MAX_VALUE) return Outcome.Fatal("File too large for segmented download")
        val n = numChunks.toInt()
        val bitmap = loadBitmap(mapFile, n)
        val cursor = AtomicInteger(0)
        val received = AtomicLong(doneBytes(bitmap, chunkSize, totalBytes))
        val claim = Mutex()
        val progress = ProgressWindow(received.get())

        FileChannel.open(
            partFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            return try {
                coroutineScope {
                    repeat(PARALLEL_STREAMS) {
                        launch {
                            segmentWorker(
                                url, channel, bitmap, cursor, received, claim, progress, mapFile,
                                chunkSize, totalBytes, fileName, attempt,
                            )
                        }
                    }
                }
                Outcome.Success
            } catch (e: PausedException) {
                Outcome.Paused
            } catch (e: FatalDownloadException) {
                Outcome.Fatal(e.message ?: "Download failed")
            } catch (e: IOException) {
                Outcome.Retryable(e.message ?: e.javaClass.simpleName)
            } catch (e: Exception) {
                Outcome.Fatal(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun segmentWorker(
        url: String,
        channel: FileChannel,
        bitmap: ByteArray,
        cursor: AtomicInteger,
        received: AtomicLong,
        claim: Mutex,
        progress: ProgressWindow,
        mapFile: File,
        chunkSize: Long,
        totalBytes: Long,
        fileName: String,
        attempt: Int,
    ) {
        val data = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            if (stopRequested.get()) throw PausedException()
            val idx = claim.withLock {
                while (cursor.get() < bitmap.size && bitmap[cursor.get()] == CHUNK_DONE) {
                    cursor.incrementAndGet()
                }
                if (cursor.get() >= bitmap.size) -1 else cursor.getAndIncrement()
            }
            if (idx < 0) return

            val start = idx * chunkSize
            val end = minOf(start + chunkSize - 1, totalBytes - 1)
            val request = Request.Builder().url(url)
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=$start-$end")
                .build()

            var wrote = 0L
            client.newCall(request).execute().use { resp ->
                when {
                    resp.code == 206 -> {
                        val cr = resp.header("Content-Range")
                        val crStart = cr?.substringAfter("bytes ")
                            ?.substringBefore('-')?.trim()?.toLongOrNull()
                        if (crStart != start) {
                            throw IOException("Unexpected Content-Range: ${cr ?: "missing"}")
                        }
                        val body = resp.body ?: throw IOException("Empty response body")
                        body.byteStream().use { input ->
                            while (true) {
                                if (stopRequested.get()) throw PausedException()
                                val read = input.read(data)
                                if (read < 0) break
                                val wb = ByteBuffer.wrap(data, 0, read)
                                while (wb.hasRemaining()) channel.write(wb, start + wrote)
                                wrote += read
                                received.addAndGet(read.toLong())
                                if (progress.shouldEmit(System.nanoTime())) {
                                    val speed = progress.speedAndReset(received.get())
                                    _downloadState.value = DownloadState.Downloading(
                                        url = url,
                                        fileName = fileName,
                                        bytesReceived = received.get(),
                                        totalBytes = totalBytes,
                                        bytesPerSecond = speed,
                                        attempt = attempt,
                                        maxAttempts = MAX_ATTEMPTS,
                                    )
                                }
                            }
                        }
                        if (wrote != end - start + 1) {
                            throw IOException("Chunk truncated: got $wrote bytes, expected ${end - start + 1}")
                        }
                    }
                    resp.code == 200 -> throw FatalDownloadException("Server does not support Range")
                    resp.code == 416 -> throw FatalDownloadException("Server rejected range (HTTP 416)")
                    resp.code in RETRYABLE_STATUS -> throw IOException("HTTP ${resp.code}")
                    else -> throw FatalDownloadException("Server returned HTTP ${resp.code}")
                }
            }

            claim.withLock {
                bitmap[idx] = CHUNK_DONE
                runCatching { mapFile.writeBytes(bitmap) }
                received.set(doneBytes(bitmap, chunkSize, totalBytes))
            }
            val speed = progress.speedAndReset(received.get())
            _downloadState.value = DownloadState.Downloading(
                url = url,
                fileName = fileName,
                bytesReceived = received.get(),
                totalBytes = totalBytes,
                bytesPerSecond = speed,
                attempt = attempt,
                maxAttempts = MAX_ATTEMPTS,
            )
        }
    }

    private suspend fun downloadStream(
        url: String,
        fileName: String,
        partFile: File,
        totalBytes: Long?,
        rangeSupported: Boolean,
        attempt: Int,
    ): Outcome {
        var received = 0L
        if (rangeSupported && partFile.length() > 0) {
            received = partFile.length()
        } else if (partFile.length() > 0) {
            RandomAccessFile(partFile, "rw").use { it.setLength(0L) }
        }
        _downloadState.value = DownloadState.Downloading(
            url = url,
            fileName = fileName,
            bytesReceived = received,
            totalBytes = totalBytes,
            bytesPerSecond = 0,
            attempt = attempt,
            maxAttempts = MAX_ATTEMPTS,
        )
        if (stopRequested.get()) return Outcome.Paused

        val progress = ProgressWindow(received)
        return try {
            val requestBuilder = Request.Builder().url(url)
                .header("Accept-Encoding", "identity")
            if (received > 0) {
                requestBuilder.header("Range", "bytes=$received-")
            }
            client.newCall(requestBuilder.build()).execute().use { resp ->
                when {
                    resp.code == 200 -> {
                        if (received > 0) {
                            RandomAccessFile(partFile, "rw").use { it.setLength(0L) }
                            received = 0
                            progress.reset(0L)
                        }
                    }
                    resp.code == 206 -> {
                        val crStart = resp.header("Content-Range")
                            ?.substringAfter("bytes ")
                            ?.substringBefore('-')?.trim()?.toLongOrNull()
                        if (crStart != received) {
                            throw IOException("Unexpected Content-Range: ${resp.header("Content-Range")}")
                        }
                    }
                    resp.code in RETRYABLE_STATUS -> throw IOException("HTTP ${resp.code}")
                    else -> throw FatalDownloadException("Server returned HTTP ${resp.code}")
                }
                val body = resp.body ?: throw IOException("Empty response body")
                RandomAccessFile(partFile, "rw").use { raf ->
                    raf.seek(received)
                    val buf = ByteArray(COPY_BUFFER_SIZE)
                    body.byteStream().use { input ->
                        while (true) {
                            if (stopRequested.get()) throw PausedException()
                            val read = input.read(buf)
                            if (read < 0) break
                            raf.write(buf, 0, read)
                            received += read
                            if (progress.shouldEmit(System.nanoTime())) {
                                val speed = progress.speedAndReset(received)
                                _downloadState.value = DownloadState.Downloading(
                                    url = url,
                                    fileName = fileName,
                                    bytesReceived = received,
                                    totalBytes = totalBytes,
                                    bytesPerSecond = speed,
                                    attempt = attempt,
                                    maxAttempts = MAX_ATTEMPTS,
                                )
                            }
                        }
                    }
                }
            }
            Outcome.Success
        } catch (e: PausedException) {
            Outcome.Paused
        } catch (e: FatalDownloadException) {
            Outcome.Fatal(e.message ?: "Download failed")
        } catch (e: IOException) {
            Outcome.Retryable(e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            Outcome.Fatal(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun finalize(
        fileName: String,
        partFile: File,
        mapFile: File,
        metaFile: File,
        expectedSha256: String?,
    ): Boolean {
        Log.d("ModelManager", "Finalizing download: $fileName")
        _downloadState.value = DownloadState.Verifying(fileName, 0, partFile.length())
        val actualHash = hashFile(partFile, isCancelled = { stopRequested.get() }) { bytesRead ->
            _downloadState.value = DownloadState.Verifying(fileName, bytesRead, partFile.length())
        }
        if (actualHash == null) {
            if (stopRequested.get()) {
                Log.d("ModelManager", "Verification cancelled: $fileName")
                return false
            }
            _downloadState.value =
                DownloadState.Failed(fileName, "Failed to read file for verification")
            return true
        }
        if (stopRequested.get()) return false
        if (expectedSha256 != null && !expectedSha256.equals(actualHash, ignoreCase = true)) {
            partFile.delete()
            mapFile.delete()
            metaFile.delete()
            Log.w("ModelManager", "Checksum mismatch for $fileName: expected=$expectedSha256, actual=$actualHash")
            _downloadState.value = DownloadState.Failed(
                fileName,
                "Checksum mismatch: expected $expectedSha256, got $actualHash",
            )
            return true
        }
        val finalFile = File(modelsDir, fileName)
        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) throw IOException("Could not move file into place")
        File(modelsDir, "$fileName.sha256").writeText(actualHash.lowercase(Locale.ROOT))
        mapFile.delete()
        metaFile.delete()
        refresh()
        Log.d("ModelManager", "Download complete: $fileName -> ${finalFile.absolutePath}")
        _downloadState.value = DownloadState.Complete(fileName, finalFile.absolutePath)
        return true
    }

    // ------------------------------------------------------------------ import

    private suspend fun import(uri: Uri) {
        stopRequested.set(false)
        val resolver = context.contentResolver
        val name = try {
            importFileName(resolver, uri)
        } catch (e: Exception) {
            _downloadState.value =
                DownloadState.Failed("import", "Could not read file: ${e.message}")
            return
        }
        if (!name.lowercase(Locale.ROOT).endsWith(".litertlm")) {
            _downloadState.value =
                DownloadState.Failed(name, "Not a .litertlm file")
            return
        }
        val totalBytes = resolver.openAssetFileDescriptor(uri, "r")?.length
            ?: querySize(resolver, uri)
        
        // Validate file size before starting import
        try {
            validateImportSize(totalBytes)
        } catch (e: IllegalArgumentException) {
            _downloadState.value = DownloadState.Failed(name, e.message ?: "File validation failed")
            return
        }
        
        val tmpFile = File(modelsDir, "$name.importing")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var lastEmit = 0L
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmpFile).use { out ->
                    val buf = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        if (stopRequested.get()) {
                            tmpFile.delete()
                            _downloadState.value = DownloadState.Paused(name, written, totalBytes)
                            return
                        }
                        val read = input.read(buf)
                        if (read < 0) break
                        out.write(buf, 0, read)
                        digest.update(buf, 0, read)
                        written += read
                        if (written - lastEmit >= PROGRESS_EMIT_BYTES) {
                            lastEmit = written
                            _downloadState.value =
                                DownloadState.Importing(name, written, totalBytes)
                        }
                    }
                }
            } ?: throw IOException("Could not open content stream")

            if (stopRequested.get()) {
                tmpFile.delete()
                _downloadState.value = DownloadState.Paused(name, written, totalBytes)
                return
            }

            val finalFile = File(modelsDir, name)
            tmpFile.renameTo(finalFile)
             val hash = digest.digest().joinToString("") { "%02x".format(it) }
            File(modelsDir, "$name.sha256").writeText(hash)
            refresh()
            Log.d("ModelManager", "Import complete: $name -> ${finalFile.absolutePath}")
            _downloadState.value = DownloadState.Complete(name, finalFile.absolutePath)
        } catch (e: Exception) {
            Log.e("ModelManager", "Import failed: $name", e)
            tmpFile.delete()
            _downloadState.value =
                DownloadState.Failed(name, e.message ?: e.javaClass.simpleName)
        } finally {
            stopRequested.set(false)
        }
    }

    private fun importFileName(resolver: ContentResolver, uri: Uri): String {
        var displayName: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) displayName = c.getString(0)
        }
        val raw = displayName ?: uri.lastPathSegment ?: "model"
        val cleaned = sanitizeFileName(raw)
        val withExt =
            if (cleaned.endsWith(".litertlm", ignoreCase = true)) cleaned else "$cleaned.litertlm"
        return uniqueName(withExt)
    }

    private fun querySize(resolver: ContentResolver, uri: Uri): Long? {
        return resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 } else null
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun deriveFileName(url: String): String {
        val path = runCatching { URI(url).path }.getOrNull()
        val segment = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast('/').substringBefore('?').trim()
        val decoded = try {
            URLDecoder.decode(segment, "UTF-8")
        } catch (e: Exception) {
            segment
        }
        val cleaned = sanitizeFileName(decoded)
        return if (cleaned.endsWith(".litertlm", ignoreCase = true)) cleaned else "$cleaned.litertlm"
    }

    private fun sanitizeFileName(raw: String): String {
        val decoded = try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (e: Exception) {
            raw
        }
        return decoded.replace(Regex("[\\\\/:\\x00-\\x1f]"), "_").trim().ifBlank { "model" }
    }

    private fun uniqueName(base: String): String {
        val existing = modelsDir.list()?.toSet().orEmpty()
        if (base !in existing) return base
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        var i = 1
        while ("$stem ($i)$ext" in existing) i++
        return "$stem ($i)$ext"
    }

    private fun metaMatches(metaFile: File, url: String, expectedSha256: String?): Boolean {
        if (!metaFile.exists()) return false
        val lines = runCatching { metaFile.readText().split('\n') }.getOrNull() ?: return false
        if (lines.size < 2) return false
        if (lines[0] != url) return false
        return lines[1].ifBlank { null } == expectedSha256
    }

    private fun writeMeta(metaFile: File, url: String, expectedSha256: String?) {
        metaFile.writeText("$url\n${expectedSha256 ?: ""}\n")
    }

    private fun hasSpaceFor(totalBytes: Long, existing: Long): Boolean {
        val need = totalBytes - existing
        if (need <= 0) return true
        
        // Check if total file size exceeds maximum
        if (totalBytes > maxModelSizeBytes) {
            return false
        }
        
        val stat = StatFs(modelsDir.absolutePath)
        return stat.availableBytes >= need + minFreeSpaceBytes
    }

    private fun loadBitmap(mapFile: File, size: Int): ByteArray {
        val bitmap = ByteArray(size)
        if (mapFile.exists()) {
            runCatching { mapFile.readBytes() }.getOrNull()?.let { src ->
                val n = minOf(src.size, size)
                System.arraycopy(src, 0, bitmap, 0, n)
            }
        }
        return bitmap
    }

    private fun doneBytes(bitmap: ByteArray, chunkSize: Long, totalBytes: Long): Long {
        var sum = 0L
        for (i in bitmap.indices) {
            if (bitmap[i] == CHUNK_DONE) {
                val start = i * chunkSize
                sum += minOf(chunkSize, totalBytes - start)
            }
        }
        return sum
    }

    private fun hashFile(
        file: File,
        isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(COPY_BUFFER_SIZE)
                var total = 0L
                var lastEmit = 0L
                while (true) {
                    if (isCancelled()) return null
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

    private fun backoffMs(attempt: Int): Long = when (attempt) {
        2 -> 1000L
        else -> 2000L
    }

    private class ProgressWindow(initialBytes: Long) {
        private var startNs = System.nanoTime()
        private var startBytes = initialBytes
        private var lastEmitNs = 0L

        @Synchronized
        fun shouldEmit(nowNs: Long): Boolean {
            if (nowNs - lastEmitNs < 200_000_000L) return false
            lastEmitNs = nowNs
            return true
        }

        @Synchronized
        fun speedAndReset(received: Long): Long {
            val now = System.nanoTime()
            val elapsed = (now - startNs) / 1_000_000_000.0
            val speed = if (elapsed > 0) ((received - startBytes) / elapsed).toLong() else 0L
            startNs = now
            startBytes = received
            return speed
        }

        @Synchronized
        fun reset(initialBytes: Long) {
            startNs = System.nanoTime()
            startBytes = initialBytes
            lastEmitNs = 0L
        }
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 256 * 1024
        private const val CHUNK_SIZE = 8 * 1024 * 1024L
        private const val PARALLEL_STREAMS = 4
        private const val MAX_ATTEMPTS = 3
        private const val PROGRESS_EMIT_BYTES = 4 * 1024 * 1024L
        private const val CHUNK_DONE: Byte = 1
        private val RETRYABLE_STATUS = setOf(408, 425, 429, 500, 502, 503, 504)
    }
}

private sealed class Outcome {
    object Success : Outcome()
    object Paused : Outcome()
    data class Retryable(val message: String) : Outcome()
    data class Fatal(val message: String) : Outcome()
}

private class PausedException : Exception()
private class FatalDownloadException(message: String) : Exception(message)
