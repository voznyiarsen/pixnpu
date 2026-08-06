package com.pixnpu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppLogTest {

    @Test
    fun parsesErrorLine() {
        val entry = AppLog.parseLine(
            "08-06 12:34:56.789  1234  5678 E LiteRTLMEngine: Warmup failed on NPU",
        )
        assertNotNull(entry)
        assertEquals('E', entry!!.priority)
        assertEquals("LiteRTLMEngine", entry.tag)
        assertEquals("Warmup failed on NPU", entry.message)
        assertEquals(true, entry.isError)
    }

    @Test
    fun identicalLinesGetUniqueIdsAfterAppend() {
        val first = AppLog.parseLine("08-06 12:34:56.789  1234  5678 E Tag: boom")!!
        val second = AppLog.parseLine("08-06 12:34:56.789  1234  5678 E Tag: boom")!!
        AppLog.append(first)
        AppLog.append(second)
        val entries = AppLog.entries.value
        assertEquals(2, entries.size)
        assertEquals(entries[0].id + 1, entries[1].id)
    }

    @Test
    fun parsesDebugLine() {
        val entry = AppLog.parseLine(
            "08-06 12:34:56.789  1234  5678 D ModelManager: download progress 42%",
        )
        assertNotNull(entry)
        assertEquals('D', entry!!.priority)
        assertEquals("ModelManager", entry.tag)
        assertEquals(false, entry.isError)
    }

    @Test
    fun rejectsMalformedLines() {
        assertNull(AppLog.parseLine("not a logcat line"))
        assertNull(AppLog.parseLine(""))
        assertNull(AppLog.parseLine("08-06 12:34:56.789  1234  5678 E nocolon"))
    }

    @Test
    fun priorityFiltering() {
        val error = AppLog.parseLine("08-06 12:34:56.789  1234  5678 E Tag: boom")!!
        val warning = AppLog.parseLine("08-06 12:34:56.789  1234  5678 W Tag: careful")!!
        val info = AppLog.parseLine("08-06 12:34:56.789  1234  5678 I Tag: fine")!!
        assertEquals(6, error.level)
        assertEquals(5, warning.level)
        assertEquals(4, info.level)
        assertEquals(true, error.level >= 5)
        assertEquals(true, warning.level >= 5)
        assertEquals(false, info.level >= 5)
        assertEquals(true, error.isError)
        assertEquals(false, warning.isError)
    }
}
