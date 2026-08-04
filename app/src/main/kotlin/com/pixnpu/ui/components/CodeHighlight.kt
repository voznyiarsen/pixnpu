package com.pixnpu.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pixnpu.engine.ThinkingSegment
import com.pixnpu.engine.parseThinking

private val FENCE = Regex("```([A-Za-z0-9_+#\\-.]*) *\\n?")

private sealed class Segment {
    class Text(val content: String) : Segment()
    class Code(val lang: String, val content: String) : Segment()
}

private val COMMENT_REGEX =
    Regex("//[^\\n]*|#[^\\n]*|/\\*.*?\\*/|--[^\\n]*", RegexOption.DOT_MATCHES_ALL)
private val STRING_REGEX = Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`[^`\\\\]*`")
private val NUMBER_REGEX = Regex("\\b\\d[\\.\\d]*\\b")
private val WORD_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")

private fun highlightCode(
    lang: String,
    code: String,
    base: Color,
    commentColor: Color,
    keywordColor: Color,
    stringColor: Color,
    numberColor: Color,
): AnnotatedString {
    val keywords = keywordSetFor(lang)
    return buildAnnotatedString {
        withStyle(SpanStyle(color = base)) { append(code) }
        for (cm in COMMENT_REGEX.findAll(code)) {
            addStyle(SpanStyle(color = commentColor), cm.range.first, cm.range.last + 1)
        }
        for (sm in STRING_REGEX.findAll(code)) {
            addStyle(SpanStyle(color = stringColor), sm.range.first, sm.range.last + 1)
        }
        for (nm in NUMBER_REGEX.findAll(code)) {
            addStyle(SpanStyle(color = numberColor), nm.range.first, nm.range.last + 1)
        }
        for (km in WORD_REGEX.findAll(code)) {
            if (km.value in keywords) {
                addStyle(
                    SpanStyle(
                        color = keywordColor,
                        fontWeight = FontWeight.Bold,
                    ),
                    km.range.first,
                    km.range.last + 1,
                )
            }
        }
    }
}

private fun scanSegments(text: String): List<Segment> {
    val segments = mutableListOf<Segment>()
    var pos = 0
    var open = false
    var openLang = ""
    for (match in FENCE.findAll(text)) {
        if (!open) {
            if (match.range.first > pos) {
                segments.add(Segment.Text(text.substring(pos, match.range.first)))
            }
            openLang = match.groupValues[1]
            pos = match.range.last + 1
            open = true
        } else {
            segments.add(Segment.Code(openLang, text.substring(pos, match.range.first)))
            pos = match.range.last + 1
            open = false
        }
    }
    val rest = text.substring(pos)
    if (rest.isNotEmpty()) {
        segments.add(if (open) Segment.Code(openLang, rest) else Segment.Text(rest))
    }
    return segments
}

@Composable
fun StreamingText(
    text: String,
    modifier: Modifier = Modifier,
    maxCodeHeight: Dp = 340.dp,
    caretVisible: Boolean = false,
    bodyStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val thinkingSegments = remember(text) { parseThinking(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        thinkingSegments.forEachIndexed { index, segment ->
            val isLast = index == thinkingSegments.lastIndex
            when (segment) {
                is ThinkingSegment.Text -> SegmentedBody(
                    segments = remember(segment.content) { scanSegments(segment.content) },
                    caretVisible = caretVisible && isLast,
                    bodyStyle = bodyStyle,
                    maxCodeHeight = maxCodeHeight,
                )
                is ThinkingSegment.Thinking -> ThinkingBlock(
                    content = segment.content,
                    closed = segment.closed,
                    caretVisible = caretVisible && isLast && !segment.closed,
                )
            }
        }
    }
}

@Composable
private fun SegmentedBody(
    segments: List<Segment>,
    caretVisible: Boolean,
    bodyStyle: TextStyle,
    maxCodeHeight: Dp,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEachIndexed { index, seg ->
            val isLast = index == segments.lastIndex
            when (seg) {
                is Segment.Text -> HighlightedBody(
                    text = if (caretVisible && isLast) "${seg.content}▌" else seg.content,
                    style = bodyStyle,
                )
                is Segment.Code -> CodeBlock(
                    lang = seg.lang,
                    content = seg.content,
                    maxHeight = maxCodeHeight,
                )
            }
        }
    }
}

/**
 * Collapsible "Thinking" section for model reasoning traces (<think>...</think>).
 * Auto-expands while the thought is still streaming in; collapses on tap.
 */
@Composable
private fun ThinkingBlock(
    content: String,
    closed: Boolean,
    caretVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    // Auto-expand while thinking is still in progress.
    if (!closed) {
        expanded = true
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!closed) {
                ThinkingPulse()
            }
            Text(
                text = "Thinking",
                style = MaterialTheme.typography.labelLarge.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                contentDescription = if (expanded) "Hide thinking" else "Show thinking",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            val lineColor = MaterialTheme.colorScheme.outlineVariant
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .drawBehind {
                        drawLine(
                            color = lineColor,
                            start = Offset(0f, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                    .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            ) {
                SegmentedBody(
                    segments = remember(content) { scanSegments(if (caretVisible) "$content▌" else content) },
                    caretVisible = false,
                    bodyStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    maxCodeHeight = 240.dp,
                )
            }
        }
    }
}

@Composable
private fun ThinkingPulse() {
    val transition = rememberInfiniteTransition(label = "thinking-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinking-pulse-alpha",
    )
    Box(
        modifier = Modifier
            .size(7.dp)
            .alpha(alpha)
            .background(
                color = MaterialTheme.colorScheme.tertiary,
                shape = CircleShape,
            ),
    )
}

@Composable
private fun HighlightedBody(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    Text(
        text = highlightCode(
            lang = "plain",
            code = text,
            base = MaterialTheme.colorScheme.onSurface,
            commentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            keywordColor = MaterialTheme.colorScheme.primary,
            stringColor = MaterialTheme.colorScheme.tertiary,
            numberColor = MaterialTheme.colorScheme.secondary,
        ),
        modifier = modifier,
        style = style,
    )
}

@Composable
private fun CodeBlock(lang: String, content: String, maxHeight: Dp) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        if (lang.isNotEmpty()) {
            Text(
                text = lang,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Text(
            text = highlightCode(
                lang = lang,
                code = content,
                base = MaterialTheme.colorScheme.onSurface,
                commentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                keywordColor = MaterialTheme.colorScheme.primary,
                stringColor = MaterialTheme.colorScheme.tertiary,
                numberColor = MaterialTheme.colorScheme.secondary,
            ),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

private val KEYWORDS = mapOf(
    "kotlin" to setOf(
        "fun", "val", "var", "class", "object", "data", "interface", "when", "if", "else",
        "for", "while", "return", "suspend", "launch", "import", "package", "private", "public",
        "internal", "override", "null", "true", "false", "sealed", "enum", "companion",
        "suspend", "async", "try", "catch", "finally", "this", "is", "in", "by",
    ),
    "java" to setOf(
        "public", "private", "protected", "class", "interface", "void", "int", "long",
        "float", "double", "boolean", "return", "if", "else", "for", "while", "new",
        "import", "package", "static", "final", "extends", "implements", "try", "catch",
        "null", "true", "false", "this",
    ),
    "python" to setOf(
        "def", "class", "import", "from", "return", "if", "elif", "else", "for", "while",
        "with", "as", "in", "not", "and", "or", "try", "except", "finally", "None", "True",
        "False", "lambda", "yield", "async", "await", "self",
    ),
    "json" to emptySet(),
    "bash" to setOf(
        "echo", "if", "then", "else", "fi", "for", "do", "done", "export", "cd", "sudo",
        "apt", "git", "npm", "curl", "wget", "ls", "mkdir", "rm",
    ),
)

private val DEFAULT_KEYWORDS = setOf("if", "else", "for", "while", "return", "import", "class", "def", "fun", "val")

private fun keywordSetFor(lang: String): Set<String> =
    KEYWORDS[lang.lowercase().trim()] ?: DEFAULT_KEYWORDS
