package com.pixnpu.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FmtTest {

    @Test
    fun bytes_under1kb() {
        assertEquals("512 B", Fmt.bytes(512))
        assertEquals("1 B", Fmt.bytes(1))
    }

    @Test
    fun bytes_kb() {
        assertEquals("1.0 KB", Fmt.bytes(1024))
        assertEquals("1.5 KB", Fmt.bytes(1536))
        assertEquals("1023.0 KB", Fmt.bytes(1024 * 1023))
    }

    @Test
    fun bytes_mb() {
        assertEquals("1.0 MB", Fmt.bytes(1024 * 1024))
        assertEquals("1.5 MB", Fmt.bytes(1572864))
    }

    @Test
    fun bytes_gb() {
        assertEquals("1.00 GB", Fmt.bytes(1024L * 1024 * 1024))
        assertEquals("2.50 GB", Fmt.bytes(2684354560L))
    }

    @Test
    fun speed_zero() {
        assertEquals("-", Fmt.speed(0))
        assertEquals("-", Fmt.speed(-1))
    }

    @Test
    fun speed_nonzero() {
        assertEquals("1.0 KB/s", Fmt.speed(1024))
        assertEquals("1.0 MB/s", Fmt.speed(1024 * 1024))
    }

    @Test
    fun ms_formatting() {
        assertEquals("0.00s", Fmt.ms(0))
        assertEquals("1.00s", Fmt.ms(1000))
        assertEquals("1.50s", Fmt.ms(1500))
    }

    @Test
    fun sha_nullOrBlank() {
        assertEquals("unknown", Fmt.sha(null))
        assertEquals("unknown", Fmt.sha(""))
    }

    @Test
    fun sha_truncated() {
        val hex = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
        val result = Fmt.sha(hex)
        assertEquals("abcdef12…34567890", result)
    }

    @Test
    fun sha_shortHash() {
        val hex = "abc12345"
        val result = Fmt.sha(hex)
        assertEquals("abc12345…abc12345", result)
    }
}
