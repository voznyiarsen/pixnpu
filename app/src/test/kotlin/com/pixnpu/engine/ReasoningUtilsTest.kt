package com.pixnpu.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningUtilsTest {

    @Test
    fun stripReasoning_singleLine() {
        val input = "<think>Some inner thought</think>Hello world!"
        assertEquals("Hello world!", input.stripReasoning())
    }

    @Test
    fun stripReasoning_multiLine() {
        val input = """
            <think>
            Step 1: Calculate
            Step 2: Solve
            </think>
            The answer is 42.
        """.trimIndent()
        assertEquals("The answer is 42.", input.stripReasoning())
    }

    @Test
    fun stripReasoning_unclosed() {
        val input = "<think>I am still thinking..."
        assertEquals("", input.stripReasoning())
    }

    @Test
    fun stripReasoning_unclosedWithPrefix() {
        val input = "Initial thought\n<think>I am thinking forever..."
        assertEquals("Initial thought", input.stripReasoning())
    }

    @Test
    fun stripReasoning_noReasoning() {
        val input = "Just normal response."
        assertEquals("Just normal response.", input.stripReasoning())
    }

    @Test
    fun stripReasoning_multipleBlocks() {
        val input = "<think>Part 1</think>Middle text<think>Part 2</think>End text"
        assertEquals("Middle textEnd text", input.stripReasoning())
    }
}
