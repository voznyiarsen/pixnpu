package com.pixnpu.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningUtilsTest {

    @Test
    fun parseThinking_closedBlock() {
        val segments = parseThinking("<think>Some inner thought</think>Hello world!")
        assertEquals(
            listOf(
                ThinkingSegment.Thinking("Some inner thought", closed = true),
                ThinkingSegment.Text("Hello world!"),
            ),
            segments,
        )
    }

    @Test
    fun parseThinking_multiLine() {
        val segments = parseThinking("<think>\nStep 1: Calculate\nStep 2: Solve\n</think>\nThe answer is 42.")
        assertEquals(2, segments.size)
        assertEquals(ThinkingSegment.Thinking("\nStep 1: Calculate\nStep 2: Solve\n", closed = true), segments[0])
        assertEquals(ThinkingSegment.Text("\nThe answer is 42."), segments[1])
    }

    @Test
    fun parseThinking_unclosedTrailing() {
        val segments = parseThinking("<think>I am still thinking...")
        assertEquals(
            listOf(ThinkingSegment.Thinking("I am still thinking...", closed = false)),
            segments,
        )
    }

    @Test
    fun parseThinking_unclosedWithPrefix() {
        val segments = parseThinking("Initial thought\n<think>I am thinking forever...")
        assertEquals(
            listOf(
                ThinkingSegment.Text("Initial thought\n"),
                ThinkingSegment.Thinking("I am thinking forever...", closed = false),
            ),
            segments,
        )
    }

    @Test
    fun parseThinking_noThinking() {
        val segments = parseThinking("Just normal response.")
        assertEquals(listOf(ThinkingSegment.Text("Just normal response.")), segments)
    }

    @Test
    fun parseThinking_multipleBlocks() {
        val segments = parseThinking("<think>Part 1</think>Middle text<think>Part 2</think>End text")
        assertEquals(
            listOf(
                ThinkingSegment.Thinking("Part 1", closed = true),
                ThinkingSegment.Text("Middle text"),
                ThinkingSegment.Thinking("Part 2", closed = true),
                ThinkingSegment.Text("End text"),
            ),
            segments,
        )
    }

    @Test
    fun parseThinking_thinkingVariant() {
        val segments = parseThinking("<thinking>Reason here</thinking>Answer")
        assertEquals(
            listOf(
                ThinkingSegment.Thinking("Reason here", closed = true),
                ThinkingSegment.Text("Answer"),
            ),
            segments,
        )
    }

    @Test
    fun parseThinking_caseInsensitive() {
        val segments = parseThinking("<THINK>Reason</THINK>Answer")
        assertEquals(2, segments.size)
        assertEquals(ThinkingSegment.Thinking("Reason", closed = true), segments[0])
    }

    @Test
    fun parseThinking_emptyBlockIsDropped() {
        val segments = parseThinking("<think></think>Answer")
        assertEquals(listOf(ThinkingSegment.Text("Answer")), segments)
    }

    @Test
    fun parseThinking_thinkingFirstThenAnswer() {
        val segments = parseThinking("<think>Plan the answer</think>\n\nThe result is 42.")
        assertEquals(
            listOf(
                ThinkingSegment.Thinking("Plan the answer", closed = true),
                ThinkingSegment.Text("\n\nThe result is 42."),
            ),
            segments,
        )
    }

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

    @Test
    fun stripReasoning_thinkingVariant() {
        val input = "<thinking>Reason here</thinking>Answer"
        assertEquals("Answer", input.stripReasoning())
    }
}
