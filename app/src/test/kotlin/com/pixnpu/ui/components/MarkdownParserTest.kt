package com.pixnpu.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    private fun texts(spans: List<MdSpan>): String =
        spans.joinToString("") { span ->
            when (span) {
                is MdSpan.Text -> span.text
                is MdSpan.Bold -> "**${texts(span.children)}**"
                is MdSpan.Italic -> "*${texts(span.children)}*"
                is MdSpan.InlineCode -> "`${span.text}`"
                is MdSpan.Link -> "[${texts(span.text)}](${span.url})"
            }
        }

    @Test
    fun heading_levels() {
        val blocks = parseBlocks("# Title\n\n## Sub\n\n### Deep")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MdBlock.Heading)
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertEquals(2, (blocks[1] as MdBlock.Heading).level)
        assertEquals(3, (blocks[2] as MdBlock.Heading).level)
    }

    @Test
    fun paragraph_joins_lines_with_space() {
        val blocks = parseBlocks("first line\nsecond line")
        assertEquals(1, blocks.size)
        val paragraph = blocks[0] as MdBlock.Paragraph
        assertEquals("first line second line", texts(paragraph.spans))
    }

    @Test
    fun bold_italic_code_link() {
        val blocks = parseBlocks("**b** *i* `c` [l](https://x.example)")
        val paragraph = blocks[0] as MdBlock.Paragraph
        assertEquals("**b** *i* `c` [l](https://x.example)", texts(paragraph.spans))
        val spans = paragraph.spans
        assertEquals(MdSpan.Bold(listOf(MdSpan.Text("b"))), spans[0])
        assertEquals(MdSpan.Text(" "), spans[1])
        assertEquals(MdSpan.Italic(listOf(MdSpan.Text("i"))), spans[2])
        assertEquals(MdSpan.Text(" "), spans[3])
        assertEquals(MdSpan.InlineCode("c"), spans[4])
        assertEquals(MdSpan.Text(" "), spans[5])
        assertEquals(MdSpan.Link(listOf(MdSpan.Text("l")), "https://x.example"), spans[6])
    }

    @Test
    fun code_span_is_not_parsed_inline() {
        val spans = parseInline("`**not bold**`")
        assertEquals(listOf(MdSpan.InlineCode("**not bold**")), spans)
    }

    @Test
    fun bullet_and_ordered_lists() {
        val bullet = parseBlocks("- one\n- two")
        assertEquals(1, bullet.size)
        val listBlock = bullet[0] as MdBlock.ListBlock
        assertTrue(!listBlock.ordered)
        assertEquals(2, listBlock.items.size)
        assertEquals("one", texts(listBlock.items[0]))

        val ordered = parseBlocks("1. first\n2. second")
        val orderedBlock = ordered[0] as MdBlock.ListBlock
        assertTrue(orderedBlock.ordered)
        assertEquals(2, orderedBlock.items.size)
    }

    @Test
    fun quote_and_rule() {
        val blocks = parseBlocks("> quoted\n\n---")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Quote)
        assertEquals("quoted", texts((blocks[0] as MdBlock.Quote).spans))
        assertTrue(blocks[1] is MdBlock.Rule)
    }

    @Test
    fun non_markdown_text_passes_through() {
        val blocks = parseBlocks("just 2x + 3 = 5, no *stars* here?")
        val paragraph = blocks[0] as MdBlock.Paragraph
        assertEquals("just 2x + 3 = 5, no *stars* here?", texts(paragraph.spans))
    }

    @Test
    fun dashes_inside_paragraph_not_list() {
        val blocks = parseBlocks("a - b")
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test
    fun simple_table() {
        val blocks = parseBlocks("| Name | Age |\n|------|-----|\n| Ada  | 36  |\n| Bob  | 42  |")
        assertEquals(1, blocks.size)
        val table = blocks[0] as MdBlock.Table
        assertEquals(2, table.columns)
        assertEquals("Name", texts(table.header[0]))
        assertEquals("Age", texts(table.header[1]))
        assertEquals(2, table.rows.size)
        assertEquals("Ada", texts(table.rows[0][0]))
        assertEquals("36", texts(table.rows[0][1]))
    }

    @Test
    fun table_without_leading_and_trailing_pipes() {
        val blocks = parseBlocks("A | B\n--- | ---\n1 | 2")
        val table = blocks[0] as MdBlock.Table
        assertEquals(2, table.columns)
        assertEquals("1", texts(table.rows[0][0]))
    }

    @Test
    fun table_with_alignment_colons() {
        val blocks = parseBlocks("| Q | A |\n|:-:|---:|\n| x | y |")
        val table = blocks[0] as MdBlock.Table
        assertEquals(2, table.columns)
        assertEquals(1, table.rows.size)
        assertEquals(listOf(TableAlign.Center, TableAlign.Right), table.alignments)
    }

    @Test
    fun table_alignment_defaults_to_left() {
        val blocks = parseBlocks("| A | B |\n|:--|---|\n| 1 | 2 |")
        val table = blocks[0] as MdBlock.Table
        assertEquals(listOf(TableAlign.Left, TableAlign.Left), table.alignments)
    }

    @Test
    fun table_single_dash_alignment_cells() {
        val blocks = parseBlocks("| A | B |\n|:-:|:-:|\n| 1 | 2 |")
        val table = blocks[0] as MdBlock.Table
        assertEquals(listOf(TableAlign.Center, TableAlign.Center), table.alignments)
    }

    @Test
    fun table_cells_support_inline_formatting() {
        val blocks = parseBlocks("| **bold** | `code` |\n|---|---|\n| *it* | plain |")
        val table = blocks[0] as MdBlock.Table
        assertEquals(MdSpan.Bold(listOf(MdSpan.Text("bold"))), table.header[0].single())
        assertEquals(MdSpan.InlineCode("code"), table.header[1].single())
        assertEquals(MdSpan.Italic(listOf(MdSpan.Text("it"))), table.rows[0][0].single())
    }

    @Test
    fun pipe_line_without_separator_is_paragraph() {
        val blocks = parseBlocks("| not a table\njust text")
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertEquals("| not a table just text", texts((blocks[0] as MdBlock.Paragraph).spans))
    }

    @Test
    fun ragged_rows_render_padded_to_column_count() {
        val blocks = parseBlocks("| A | B | C |\n|---|---|---|\n| only one |")
        val table = blocks[0] as MdBlock.Table
        assertEquals(3, table.columns)
        assertEquals(1, table.rows[0].size)
    }
}
