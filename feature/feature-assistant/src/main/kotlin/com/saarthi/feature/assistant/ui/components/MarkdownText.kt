package com.saarthi.feature.assistant.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/**
 * Renders a subset of markdown commonly produced by Gemma chat responses:
 *
 *  - **bold** / __bold__
 *  - *italic* / _italic_
 *  - `inline code`
 *  - `# Heading`, `## Subheading`, `### Sub-sub`
 *  - `- bullet` / `* bullet` → "•  bullet"
 *  - `1. numbered` (kept as-is)
 *  - Triple-backtick fenced code blocks (treated as inline code styling)
 *
 * Pure Compose (no AndroidView/Markwon) — fast enough that re-rendering on every
 * streamed token is cheap. Mid-stream half-tokens like `**bo` simply don't get
 * styled until the closing `**` arrives, which gracefully degrades to plain text.
 */
@Composable
fun MarkdownText(
    text: String,
    color: Color,
    style: TextStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
    modifier: Modifier = Modifier,
) {
    val rendered = remember(text) { renderMarkdown(text) }
    Text(
        text = rendered,
        style = style,
        color = color,
        modifier = modifier,
    )
}

/** Public for unit-testing the parser without spinning up Compose. */
fun renderMarkdown(input: String): AnnotatedString = buildAnnotatedString {
    // First strip fenced code blocks: render their content as monospace, drop the fences.
    val noFences = stripFences(input) { codeBody ->
        withStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = Color(0x33000000),
            )
        ) { append(codeBody) }
    }
    val lines = noFences.split('\n')
    lines.forEachIndexed { idx, rawLine ->
        appendLineMarkdown(rawLine)
        if (idx < lines.lastIndex) append('\n')
    }
}

/**
 * Walk the text once, peeling off ``` … ``` fences. Anything outside fences is
 * appended via [append]; anything inside is passed to [onCode] for styled append.
 * Returns the fence-free text only when there are no fences at all (fast path).
 */
private inline fun AnnotatedString.Builder.stripFences(
    text: String,
    onCode: AnnotatedString.Builder.(String) -> Unit,
): String {
    if (!text.contains("```")) return text
    var i = 0
    val plain = StringBuilder()
    while (i < text.length) {
        val fence = text.indexOf("```", i)
        if (fence == -1) {
            plain.append(text, i, text.length)
            break
        }
        plain.append(text, i, fence)
        // flush the plain prefix accumulated so far
        if (plain.isNotEmpty()) {
            // recurse-render: split lines, apply inline rules
            plain.toString().split('\n').forEachIndexed { j, ln ->
                appendLineMarkdown(ln)
                if (j < plain.toString().split('\n').lastIndex) append('\n')
            }
            plain.clear()
        }
        // skip the optional language tag on opening fence
        var codeStart = fence + 3
        val nl = text.indexOf('\n', codeStart)
        if (nl != -1 && nl < text.length && text.substring(codeStart, nl).all { it.isLetterOrDigit() }) {
            codeStart = nl + 1
        }
        val close = text.indexOf("```", codeStart)
        if (close == -1) {
            // unterminated fence — render rest as code
            onCode(text.substring(codeStart))
            return ""
        }
        onCode(text.substring(codeStart, close).trimEnd())
        i = close + 3
        if (i < text.length && text[i] == '\n') i++  // consume trailing newline
    }
    return plain.toString()
}

/** Apply line-level markdown (headings, bullets) then walk inline (bold/italic/code). */
private fun AnnotatedString.Builder.appendLineMarkdown(rawLine: String) {
    val trimmed = rawLine.trimStart()
    val indent = rawLine.length - trimmed.length
    if (indent > 0) append(" ".repeat(indent))

    // Bullets — replace `- ` or `* ` at the start with a real bullet glyph.
    if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
        append("•  ")
        appendInline(trimmed.substring(2))
        return
    }

    // Numbered lists — keep "1. " / "2. " etc. as-is, just style inline content.
    val numberedMatch = NUMBERED_REGEX.matchAt(trimmed, 0)
    if (numberedMatch != null) {
        append(numberedMatch.value)  // includes "N. "
        appendInline(trimmed.substring(numberedMatch.value.length))
        return
    }

    // Headings.
    when {
        trimmed.startsWith("### ") -> {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp)) {
                appendInline(trimmed.substring(4))
            }
            return
        }
        trimmed.startsWith("## ") -> {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp)) {
                appendInline(trimmed.substring(3))
            }
            return
        }
        trimmed.startsWith("# ") -> {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp)) {
                appendInline(trimmed.substring(2))
            }
            return
        }
    }

    appendInline(trimmed)
}

private val NUMBERED_REGEX = Regex("""^\d+\.\s""")

/**
 * Walks a line and emits styled spans for `**bold**`, `*italic*`, `__bold__`,
 * `_italic_`, and `` `code` ``. Unmatched markers (lone `*`, half-finished
 * during streaming) are dropped silently so streamed output never shows raw
 * asterisks.
 */
private fun AnnotatedString.Builder.appendInline(text: String) {
    var i = 0
    while (i < text.length) {
        when {
            // **bold** — must check before single *italic*
            i + 1 < text.length && text[i] == '*' && text[i + 1] == '*' -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInlineNoStrong(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    // unterminated — drop the marker (mid-stream or stray)
                    i += 2
                }
            }
            // __bold__
            i + 1 < text.length && text[i] == '_' && text[i + 1] == '_' -> {
                val end = text.indexOf("__", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    i += 2
                }
            }
            // *italic*  — only if a closing * exists on the same line and the
            // content isn't empty/whitespace (which would be a stray asterisk).
            text[i] == '*' -> {
                val end = findInlineClose(text, i + 1, '*')
                if (end != -1) {
                    val body = text.substring(i + 1, end)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(body) }
                    i = end + 1
                } else {
                    // stray asterisk — drop it
                    i++
                }
            }
            // _italic_ — same logic, but only when not adjacent to a word char
            // (so words like "snake_case" don't get italicised).
            text[i] == '_' && (i == 0 || !text[i - 1].isLetterOrDigit()) -> {
                val end = findInlineClose(text, i + 1, '_')
                if (end != -1 && (end + 1 == text.length || !text[end + 1].isLetterOrDigit())) {
                    val body = text.substring(i + 1, end)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(body) }
                    i = end + 1
                } else {
                    append('_')
                    i++
                }
            }
            // `inline code`
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color(0x33000000),
                        )
                    ) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append('`')
                    i++
                }
            }
            else -> {
                append(text[i])
                i++
            }
        }
    }
}

/** Inside a bold span, single * should still be honoured for italic. */
private fun AnnotatedString.Builder.appendInlineNoStrong(text: String) {
    appendInline(text)
}

/**
 * Find the next non-whitespace [marker] occurrence so " * stray" doesn't open
 * an italic. Returns -1 if no valid close found.
 */
private fun findInlineClose(text: String, start: Int, marker: Char): Int {
    if (start >= text.length || text[start].isWhitespace()) return -1
    var j = start
    while (j < text.length) {
        val idx = text.indexOf(marker, j)
        if (idx == -1) return -1
        // marker must not be at the very next char (would mean empty span)
        if (idx > start) return idx
        j = idx + 1
    }
    return -1
}
