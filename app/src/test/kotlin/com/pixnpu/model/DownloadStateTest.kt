package com.pixnpu.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadStateTest {

    @Test
    fun idleProgress() {
        assertEquals(0f, DownloadState.Idle.progress(), 0.001f)
    }

    @Test
    fun downloadingProgress_withTotal() {
        val state = DownloadState.Downloading(
            url = "https://example.com/model.litertlm",
            fileName = "model.litertlm",
            bytesReceived = 512,
            totalBytes = 1024,
            bytesPerSecond = 100,
        )
        assertEquals(0.5f, state.progress(), 0.001f)
    }

    @Test
    fun downloadingProgress_withoutTotal() {
        val state = DownloadState.Downloading(
            url = "https://example.com/model.litertlm",
            fileName = "model.litertlm",
            bytesReceived = 512,
            totalBytes = null,
            bytesPerSecond = 100,
        )
        assertEquals(0f, state.progress(), 0.001f)
    }

    @Test
    fun downloadingProgress_clamped() {
        val state = DownloadState.Downloading(
            url = "https://example.com/model.litertlm",
            fileName = "model.litertlm",
            bytesReceived = 2048,
            totalBytes = 1024,
            bytesPerSecond = 100,
        )
        assertEquals(1f, state.progress(), 0.001f)
    }

    @Test
    fun verifyingProgress() {
        val state = DownloadState.Verifying(
            fileName = "model.litertlm",
            bytesRead = 256,
            totalBytes = 1024,
        )
        assertEquals(0.25f, state.progress(), 0.001f)
    }

    @Test
    fun importingProgress_withTotal() {
        val state = DownloadState.Importing(
            fileName = "model.litertlm",
            bytesRead = 512,
            totalBytes = 1024,
        )
        assertEquals(0.5f, state.progress(), 0.001f)
    }

    @Test
    fun importingProgress_withoutTotal() {
        val state = DownloadState.Importing(
            fileName = "model.litertlm",
            bytesRead = 512,
            totalBytes = null,
        )
        assertEquals(0f, state.progress(), 0.001f)
    }

    @Test
    fun pausedProgress_withTotal() {
        val state = DownloadState.Paused(
            fileName = "model.litertlm",
            bytesReceived = 768,
            totalBytes = 1024,
        )
        assertEquals(0.75f, state.progress(), 0.001f)
    }

    @Test
    fun pausedProgress_withoutTotal() {
        val state = DownloadState.Paused(
            fileName = "model.litertlm",
            bytesReceived = 768,
            totalBytes = null,
        )
        assertEquals(0f, state.progress(), 0.001f)
    }

    @Test
    fun pausedProgress_clamped() {
        val state = DownloadState.Paused(
            fileName = "model.litertlm",
            bytesReceived = 2048,
            totalBytes = 1024,
        )
        assertEquals(1f, state.progress(), 0.001f)
    }

    @Test
    fun completeProgress() {
        val state = DownloadState.Complete("model.litertlm", "/path/to/model.litertlm")
        assertEquals(0f, state.progress(), 0.001f)
    }

    @Test
    fun failedProgress() {
        val state = DownloadState.Failed("model.litertlm", "Error")
        assertEquals(0f, state.progress(), 0.001f)
    }

    @Test
    fun finishedState() {
        assertEquals(true, DownloadState.Complete("model.litertlm", "/path").finished)
        assertEquals(true, DownloadState.Failed("model.litertlm", "err").finished)
        assertEquals(false, DownloadState.Idle.finished)
        assertEquals(false, DownloadState.Downloading(
            url = "", fileName = "", bytesReceived = 0, totalBytes = null, bytesPerSecond = 0
        ).finished)
        assertEquals(false, DownloadState.Paused("model.litertlm", 0, null).finished)
        assertEquals(false, DownloadState.Verifying("model.litertlm", 0, 0).finished)
        assertEquals(false, DownloadState.Importing("model.litertlm", 0, null).finished)
    }
}
