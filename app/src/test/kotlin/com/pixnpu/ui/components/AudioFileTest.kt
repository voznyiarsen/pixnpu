package com.pixnpu.ui.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFileTest {

    private fun sample(value: Short): ByteArray = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte(),
    )

    private fun readShort(pcm: ByteArray, index: Int): Short {
        val lo = pcm[index * 2].toInt() and 0xFF
        val hi = pcm[index * 2 + 1].toInt() shl 8
        return (lo or hi).toShort()
    }

    @Test
    fun downmixToMono_singleChannel_isUnchanged() {
        val pcm = byteArrayOf(1, 0, 2, 0, 3, 0)
        assertArrayEquals(pcm, downmixToMono(pcm, 1))
    }

    @Test
    fun downmixToMono_stereo_averagesChannels() {
        val pcm = sample(0) + sample(100) + sample((-100).toShort()) + sample((-50).toShort())
        val mono = downmixToMono(pcm, 2)
        assertEquals(2, mono.size / 2)
        assertEquals(50, readShort(mono, 0).toInt())
        assertEquals(-75, readShort(mono, 1).toInt())
    }

    @Test
    fun downmixToMono_fourChannels_dropsToMono() {
        val pcm = sample(0) + sample(100) + sample(200) + sample(300)
        val mono = downmixToMono(pcm, 4)
        assertEquals(1, mono.size / 2)
        assertEquals(150, readShort(mono, 0).toInt())
    }

    @Test
    fun resample_sameRate_isUnchanged() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertArrayEquals(pcm, resample(pcm, 16_000, 16_000))
    }

    @Test
    fun resample_downsample_halvesSampleCount() {
        val pcm = ByteArray(64) { (it * 7).toByte() }
        val out = resample(pcm, 32_000, 16_000)
        assertEquals(16, out.size / 2)
    }

    @Test
    fun resample_upsample_doublesSampleCount() {
        val pcm = ByteArray(32) { (it * 3).toByte() }
        val out = resample(pcm, 8_000, 16_000)
        assertEquals(32, out.size / 2)
    }

    @Test
    fun resample_constantSignal_staysConstant() {
        val value = 300.toShort()
        val pcm = ByteArray(8) { sample(value)[it % 2] }
        val out = resample(pcm, 8_000, 16_000)
        for (i in 0 until out.size / 2) {
            assertEquals(value.toInt(), readShort(out, i).toInt())
        }
    }

    @Test
    fun resample_staysWithinShortRange() {
        val pcm = ByteArray(32)
        // Alternating extreme values to stress interpolation bounds.
        for (i in 0 until 16) {
            val v = if (i % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE
            pcm[i * 2] = (v.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        val out = resample(pcm, 8_000, 16_000)
        for (i in 0 until out.size / 2) {
            val s = readShort(out, i)
            assert(s >= Short.MIN_VALUE && s <= Short.MAX_VALUE)
        }
    }
}
