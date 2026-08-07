package com.pixnpu.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    // Intrinsic column widths in px, measured once per table (not per
    // recomposition of the streaming message). Header cells are measured bold
    // since the header renders SemiBold.
    val columnWidthsPx = remember(block, bodyStyle, density.fontScale) {
        val headerStyle = bodyStyle.copy(fontWeight = FontWeight.SemiBold)
        List(block.columns) { col ->
            var maxPx = 0
            block.header.getOrNull(col)?.let { maxPx = maxOf(maxPx, measurePx(textMeasurer, it, headerStyle)) }
            block.rows.forEach { row ->
                row.getOrNull(col)?.let { maxPx = maxOf(maxPx, measurePx(textMeasurer, it, bodyStyle)) }
            }
            maxPx
        }
    }
    val columnWidths = columnWidthsPx.map { px -> with(density) { px.toDp() + CELL_PADDING_X * 2 } }
    val totalWidth = columnWidths.fold(0.dp) { acc, w -> acc + w }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Local capture: nested lambda scopes (Column content) cannot resolve
        // the BoxWithConstraintsScope receiver implicitly.
        val boxMaxWidth = maxWidth
        val overflow = totalWidth > boxMaxWidth
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(8.dp)),
        ) {
            if (overflow) {
                // Fit-content widths + horizontal scroll: the table keeps its
                // intrinsic column sizes instead of squashing every column to
                // fit the bubble; a thin scrollbar strip shows the position.
                val scrollState = rememberScrollState()
                // Dp / Dp yields a Float ratio (public operator); ScrollState
                // reports its own maxValue, avoiding Dp.value (internal).
                val thumbFraction = (boxMaxWidth / totalWidth).coerceIn(0.1f, 1f)
                val thumbWidth = (boxMaxWidth * thumbFraction).coerceAtLeast(MIN_THUMB_WIDTH)
                val scrollFraction = if (scrollState.maxValue > 0) {
                    scrollState.value.toFloat() / scrollState.maxValue
                } else {
                    0f
                }
                Row(modifier = Modifier.horizontalScroll(scrollState)) {
                    TableRowRow(
                        cells = block.header,
                        columns = block.columns,
                        alignments = block.alignments,
                        columnWidths = columnWidths,
                        fixed = true,
                        header = true,
                        bodyStyle = bodyStyle,
                    )
                }
                block.rows.forEachIndexed { rowIndex, row ->
                    if (rowIndex == 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(borderColor),
                        )
                    }
                    Row(modifier = Modifier.horizontalScroll(scrollState)) {
                        TableRowRow(
                            cells = row,
                            columns = block.columns,
                            alignments = block.alignments,
                            columnWidths = columnWidths,
                            fixed = true,
                            header = false,
                            bodyStyle = bodyStyle,
                        )
                    }
                }
                // Thin always-visible scrollbar (foundation 1.9.3 has no
                // Scrollbar composable — it moved to the KMP split artifacts).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Box(
                        modifier = Modifier
                            .offset(x = (boxMaxWidth - thumbWidth) * scrollFraction)
                            .width(thumbWidth)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outline),
                    )
                }
            } else {
                // Fits: proportional widths (intrinsic sizes stretched to fill),
                // alignment still applies where a column is wider than its text.
                TableRowRow(
                    cells = block.header,
                    columns = block.columns,
                    alignments = block.alignments,
                    columnWidths = columnWidths,
                    fixed = false,
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
                        alignments = block.alignments,
                        columnWidths = columnWidths,
                        fixed = false,
                        header = false,
                        bodyStyle = bodyStyle,
                    )
                }
            }
        }
    }
}

private fun measurePx(textMeasurer: TextMeasurer, spans: List<MdSpan>, style: TextStyle): Int =
    textMeasurer
        .measure(spanString(spans), style = style, constraints = Constraints(maxWidth = Int.MAX_VALUE))
        .size
        .width

/** The spans as an AnnotatedString with layout-neutral colors (measure only). */
private fun spanString(spans: List<MdSpan>): AnnotatedString = buildAnnotatedString {
    appendSpans(spans, Color.Unspecified, Color.Unspecified, Color.Unspecified)
}

@Composable
private fun TableRowRow(
    cells: List<List<MdSpan>>,
    columns: Int,
    alignments: List<TableAlign>,
    columnWidths: List<Dp>,
    fixed: Boolean,
    header: Boolean,
    bodyStyle: TextStyle,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        for (col in 0 until columns) {
            val cell: List<MdSpan> = cells.getOrNull(col) ?: emptyList()
            val alignment = when (alignments.getOrNull(col) ?: TableAlign.Left) {
                TableAlign.Left -> TextAlign.Start
                TableAlign.Center -> TextAlign.Center
                TableAlign.Right -> TextAlign.End
            }
            val cellModifier = if (fixed) {
                Modifier.width(columnWidths[col])
            } else {
                Modifier.weight(columnWidths[col].value)
            }
            Text(
                text = renderSpans(cell),
                textAlign = alignment,
                style = if (header) {
                    bodyStyle.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    bodyStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                modifier = cellModifier.padding(
                    horizontal = CELL_PADDING_X,
                    vertical = CELL_PADDING_Y,
                ),
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
        /** Per-column alignment from the separator row's colons (GFM). */
        val alignments: List<TableAlign> = emptyList(),
    ) : MdBlock()
    data object Rule : MdBlock()
}

/** GFM table column alignment, derived from `:--:` style separator cells. */
internal enum class TableAlign { Left, Center, Right }

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

/** Horizontal cell padding (each side) added to the measured column widths. */
private val CELL_PADDING_X = 8.dp

/** Vertical cell padding, matching the previous table look. */
private val CELL_PADDING_Y = 6.dp

/** Smallest rendered scrollbar thumb, so a huge table keeps a grippable bar. */
private val MIN_THUMB_WIDTH = 24.dp

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

/**
 * GFM separator row alignment: `:---` = left, `---:` = right, `:---:` = center;
 * a bare `---` cell is left-aligned by default.
 */
private fun parseTableAlignments(separator: String): List<TableAlign> =
    parseTableRow(separator).map { cell ->
        val left = cell.startsWith(":")
        val right = cell.endsWith(":")
        when {
            left && right -> TableAlign.Center
            right -> TableAlign.Right
            else -> TableAlign.Left
        }
    }

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
                val alignments = parseTableAlignments(lines[i + 1])
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
                        alignments = alignments,
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

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun MarkdownBodyPreview() {
    MaterialTheme {
        MarkdownBody(
            text = """
                # Heading

                Some **bold**, *italic*, `inline code` and a [link](https://example.com).

                - bullet one
                - bullet two

                | Model | Tokens/s | Backend |
                |:------|---------:|:-------:|
                | gemma3-270m | 42.5 | NPU |
                | gemma3-1b | 21.3 | GPU |

                > Quote line
            """.trimIndent(),
            bodyStyle = MaterialTheme.typography.bodyLarge,
        )
    }
}
