package com.pixnpu.model

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for model management operations to enable testability and dependency injection.
 */
interface ModelManagerInterface {
    
    /**
     * Observable list of local models
     */
    val models: StateFlow<List<LocalModel>>
    
    /**
     * Observable download state
     */
    val downloadState: StateFlow<DownloadState>
    
    /**
     * Refresh the list of local models
     */
    fun refresh()
    
    /**
     * Start downloading a model from a URL
     * @param url The URL to download from
     * @param expectedSha256 Optional expected SHA-256 hash for verification
     */
    fun startDownload(url: String, expectedSha256: String?)
    
    /**
     * Import a model from a content URI (SAF)
     * @param uri The content URI to import from
     */
    fun importModel(uri: Uri)
    
    /**
     * Pause the current download/import operation
     */
    fun pause()
    
    /**
     * Check if a cancellation has been requested
     */
    fun isCancelled(): Boolean
    
    /**
     * Cancel the current download/import operation
     */
    fun cancel()
    
    /**
     * Delete a local model
     * @param model The model to delete
     * @return true if deletion was successful
     */
    fun delete(model: LocalModel): Boolean
    
    /**
     * Verify a model's SHA-256 hash
     * @param model The model to verify
     * @param isCancelled Lambda to check if operation was cancelled
     * @return The computed SHA-256 hash, or null if verification failed or was cancelled
     */
    suspend fun verify(model: LocalModel, isCancelled: () -> Boolean = { false }): String?
}
