package com.pixnpu.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Minimal CommonMark-flavoured renderer for model replies. Fenced code blocks
 * are handled upstream (CodeHighlight's fence scanner), so this deals with the
 * remaining block constructs: headings, paragraphs, bullet/ordered lists,
 * blockquotes, GFM tables and horizontal rules, plus inline **bold**,
 * *italic*, `code` and [links](url).
 */
@Composable
fun MarkdownBody(
    text: String,
    bodyStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(
                        text = renderSpans(block.spans),
                        style = style.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
                is MdBlock.Paragraph -> Text(
                    text = renderSpans(block.spans),
                    style = bodyStyle,
                )
                is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { itemIndex, spans ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = if (block.ordered) "${itemIndex + 1}." else "•",
                                style = bodyStyle.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = renderSpans(spans),
                                style = bodyStyle,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                is MdBlock.Quote -> {
                    val lineColor = MaterialTheme.colorScheme.outlineVariant
                    Text(
                        text = renderSpans(block.spans),
                        style = bodyStyle.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontStyle = FontStyle.Italic,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .drawBehind {
                                drawLine(
                                    color = lineColor,
                                    start = Offset(0f, 0f),
                                    end = Offset(0f, size.height),
                                    strokeWidth = 2.dp.toPx(),
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                is MdBlock.Table -> TableBlock(block, bodyStyle)
                is MdBlock.Rule -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}

@Composable
private fun TableBlock(block: MdBlock.Table, bodyStyle: TextStyle) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val columnWidths = tableColumnWeights(block)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(8.dp)),
    ) {
        TableRowRow(
            cells = block.header,
            columns = block.columns,
            columnWidths = columnWidths,
            header = true,
            bodyStyle = bodyStyle,
        )
        block.rows.forEachIndexed { rowIndex, row ->
            if (rowIndex == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(borderColor),
                )
            }
            TableRowRow(
                cells = row,
                columns = block.columns,
                columnWidths = columnWidths,
                header = false,
                bodyStyle = bodyStyle,
            )
        }
    }
}

/**
 * Column widths are proportional to the header's cell text lengths (longest
 * run wins), so narrow columns like "Q" don't eat half the table. Zero-length
 * cells get a minimum weight.
 */
private fun tableColumnWeights(block: MdBlock.Table): List<Float> {
    fun cellLen(cell: List<MdSpan>): Int = cell.sumOf { span ->
        when (span) {
            is MdSpan.Text -> span.text.length
            is MdSpan.Bold -> cellLen(span.children)
            is MdSpan.Italic -> cellLen(span.children)
            is MdSpan.InlineCode -> span.text.length
            is MdSpan.Link -> cellLen(span.text)
        }
    }
    return List(block.columns) { col ->
        val headerLen = block.header.getOrNull(col)?.let { cellLen(it) } ?: 0
        val maxRowLen = block.rows.maxOfOrNull { row ->
            row.getOrNull(col)?.let { cellLen(it) } ?: 0
        } ?: 0
        (maxOf(headerLen, maxRowLen) + 2).coerceAtLeast(1).toFloat()
    }
}

@Composable
private fun TableRowRow(
    cells: List<List<MdSpan>>,
    columns: Int,
    columnWidths: List<Float>,
    header: Boolean,
    bodyStyle: TextStyle,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        for (col in 0 until columns) {
            val cell: List<MdSpan> = cells.getOrNull(col) ?: emptyList()
            Text(
                text = renderSpans(cell),
                style = if (header) {
                    bodyStyle.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    bodyStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                modifier = Modifier
                    .weight(columnWidths[col])
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun renderSpans(spans: List<MdSpan>): AnnotatedString {
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        appendSpans(spans, codeColor, codeBackground, linkColor)
    }
}

private fun AnnotatedString.Builder.appendSpans(
    spans: List<MdSpan>,
    codeColor: androidx.compose.ui.graphics.Color,
    codeBackground: androidx.compose.ui.graphics.Color,
    linkColor: androidx.compose.ui.graphics.Color,
) {
    for (span in spans) {
        when (span) {
            is MdSpan.Text -> append(span.text)
            is MdSpan.Bold -> {
                val start = length
                appendSpans(span.children, codeColor, codeBackground, linkColor)
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
            }
            is MdSpan.Italic -> {
                val start = length
                appendSpans(span.children, codeColor, codeBackground, linkColor)
                addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
            }
            is MdSpan.InlineCode -> {
                val start = length
                append(span.text)
                addStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        color = codeColor,
                        background = codeBackground,
                    ),
                    start,
                    length,
                )
            }
            is MdSpan.Link -> {
                val start = length
                appendSpans(span.text, codeColor, codeBackground, linkColor)
                addStyle(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    start,
                    length,
                )
            }
        }
    }
}

internal sealed class MdBlock {
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock()
    data class Paragraph(val spans: List<MdSpan>) : MdBlock()
    data class ListBlock(val ordered: Boolean, val items: List<List<MdSpan>>) : MdBlock()
    data class Quote(val spans: List<MdSpan>) : MdBlock()
    data class Table(
        val columns: Int,
        val header: List<List<MdSpan>>,
        val rows: List<List<List<MdSpan>>>,
    ) : MdBlock()
    data object Rule : MdBlock()
}

internal sealed class MdSpan {
    data class Text(val text: String) : MdSpan()
    data class Bold(val children: List<MdSpan>) : MdSpan()
    data class Italic(val children: List<MdSpan>) : MdSpan()
    data class InlineCode(val text: String) : MdSpan()
    data class Link(val text: List<MdSpan>, val url: String) : MdSpan()
}

private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.*)$")
private val RULE_REGEX = Regex("^(-{3,}|\\*{3,}|_{3,})\\s*$")
private val ORDERED_ITEM_REGEX = Regex("^\\d+\\.\\s+(.*)$")
private val BULLET_ITEM_REGEX = Regex("^[-*+]\\s+(.*)$")
private val TABLE_SEPARATOR_REGEX =
    Regex("^\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)+\\|?$")

/**
 * A GFM-style table: a header row of `|`-separated cells (leading/trailing
 * pipes optional), a separator row of `---` cells (with optional alignment
 * colons), then zero or more body rows. A row is recognized as the table
 * start only when the following line is a valid separator — otherwise the
 * text falls through to paragraph parsing.
 */
private fun isTableSeparator(line: String): Boolean =
    TABLE_SEPARATOR_REGEX.matches(line.trim())

private fun parseTableRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

internal fun parseBlocks(text: String): List<MdBlock> {
    val lines = text.lines()
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> i++

            HEADING_REGEX.matches(trimmed) -> {
                val match = HEADING_REGEX.matchEntire(trimmed)!!
                blocks.add(MdBlock.Heading(match.groupValues[1].length, parseInline(match.groupValues[2])))
                i++
            }

            RULE_REGEX.matches(trimmed) -> {
                blocks.add(MdBlock.Rule)
                i++
            }

            trimmed.startsWith(">") -> {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quoteLines.add(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                blocks.add(MdBlock.Quote(parseInline(quoteLines.joinToString(" "))))
            }

            ORDERED_ITEM_REGEX.matches(trimmed) || BULLET_ITEM_REGEX.matches(trimmed) -> {
                val ordered = trimmed.first().isDigit()
                val pattern = if (ordered) ORDERED_ITEM_REGEX else BULLET_ITEM_REGEX
                val items = mutableListOf<List<MdSpan>>()
                while (i < lines.size) {
                    val match = pattern.matchEntire(lines[i].trim()) ?: break
                    items.add(parseInline(match.groupValues[1]))
                    i++
                }
                blocks.add(MdBlock.ListBlock(ordered, items))
            }

            trimmed.contains("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1]) -> {
                val header = parseTableRow(lines[i])
                i += 2 // header + separator
                val rows = mutableListOf<List<List<MdSpan>>>()
                while (i < lines.size) {
                    val rowLine = lines[i].trim()
                    if (!rowLine.contains("|")) break
                    rows += parseTableRow(rowLine).map { parseInline(it) }
                    i++
                }
                blocks.add(
                    MdBlock.Table(
                        columns = header.size,
                        header = header.map { parseInline(it) },
                        rows = rows,
                    ),
                )
            }

            else -> {
                val paragraphLines = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank()) {
                    val t = lines[i].trim()
                    if (HEADING_REGEX.matches(t) || t.startsWith(">") ||
                        ORDERED_ITEM_REGEX.matches(t) || BULLET_ITEM_REGEX.matches(t) ||
                        RULE_REGEX.matches(t)
                    ) {
                        break
                    }
                    paragraphLines.add(t)
                    i++
                }
                if (paragraphLines.isNotEmpty()) {
                    blocks.add(MdBlock.Paragraph(parseInline(paragraphLines.joinToString(" "))))
                }
            }
        }
    }
    return blocks
}

internal fun parseInline(text: String): List<MdSpan> {
    val spans = mutableListOf<MdSpan>()
    val plain = StringBuilder()
    var pos = 0
    fun flushPlain() {
        if (plain.isNotEmpty()) {
            spans.add(MdSpan.Text(plain.toString()))
            plain.clear()
        }
    }
    while (pos < text.length) {
        val rest = text.substring(pos)
        when {
            rest.startsWith("`") -> {
                val end = rest.indexOf('`', 1)
                if (end > 0) {
                    flushPlain()
                    spans.add(MdSpan.InlineCode(rest.substring(1, end)))
                    pos += end + 1
                } else {
                    plain.append('`')
                    pos++
                }
            }
            rest.startsWith("**") -> {
                val end = rest.indexOf("**", 2)
                if (end > 0) {
                    flushPlain()
                    spans.add(MdSpan.Bold(parseInline(rest.substring(2, end))))
                    pos += end + 2
                } else {
                    plain.append("**")
                    pos += 2
                }
            }
            rest[0] == '*' || rest[0] == '_' -> {
                val marker = rest[0]
                val end = rest.indexOf(marker, 1)
                if (end > 0) {
                    flushPlain()
                    spans.add(MdSpan.Italic(parseInline(rest.substring(1, end))))
                    pos += end + 1
                } else {
                    plain.append(marker)
                    pos++
                }
            }
            rest.startsWith("[") -> {
                val close = rest.indexOf(']')
                val paren = if (close > 0) rest.indexOf('(', close) else -1
                val parenEnd = if (paren > 0) rest.indexOf(')', paren) else -1
                if (close > 0 && paren == close + 1 && parenEnd > paren) {
                    flushPlain()
                    spans.add(
                        MdSpan.Link(
                            parseInline(rest.substring(1, close)),
                            rest.substring(paren + 1, parenEnd),
                        ),
                    )
                    pos += parenEnd + 1
                } else {
                    plain.append('[')
                    pos++
                }
            }
            else -> {
                plain.append(rest[0])
                pos++
            }
        }
    }
    flushPlain()
    return spans
}
