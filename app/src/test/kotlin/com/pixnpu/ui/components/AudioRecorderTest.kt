package com.pixnpu.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRecorderTest {

    @Test
    fun pcmBytesToDurationMs_empty() {
        assertEquals(0L, pcmBytesToDurationMs(ByteArray(0)))
    }

    @Test
    fun pcmBytesToDurationMs_oneSecond() {
        // 16 kHz mono PCM16: 16_000 samples/s * 2 bytes = 32_000 bytes per second.
        assertEquals(1000L, pcmBytesToDurationMs(ByteArray(32_000)))
    }

    @Test
    fun pcmBytesToDurationMs_halfSecond() {
        assertEquals(500L, pcmBytesToDurationMs(ByteArray(16_000)))
    }

    @Test
    fun pcmBytesToDurationMs_smallClip() {
        assertEquals(1L, pcmBytesToDurationMs(ByteArray(32)))
    }

    @Test
    fun pcmBytesToDurationMs_oddBytes() {
        // 1 byte can't form a complete sample; floors to 0ms.
        assertEquals(0L, pcmBytesToDurationMs(ByteArray(1)))
    }
}
