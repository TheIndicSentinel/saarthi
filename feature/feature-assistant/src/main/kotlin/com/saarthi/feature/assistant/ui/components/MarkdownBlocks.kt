package com.saarthi.feature.assistant.ui.components

/**
 * Block-level split of an assistant reply for [MarkdownText]: list items get
 * their own row (hanging indent — wrapped lines align under the text, not the
 * bullet) and pipe tables render as a grid. Everything else stays a [MdBlock.Text]
 * rendered by [renderMarkdown] exactly as before. Pure — unit-testable without
 * Compose.
 */
internal sealed interface MdBlock {
    data class Text(val text: String) : MdBlock
    /** [marker] is "•" or the list number ("3."); [level] is the nesting depth (0 = top). */
    data class ListItem(val marker: String, val text: String, val level: Int) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
}

private val LIST_ITEM = Regex("""^(\s*)(?:([-*•])|(\d{1,3})[.)])\s+(.*)$""")
private val TABLE_SEPARATOR = Regex("""^\s*\|?\s*:?-{2,}:?\s*(?:\|\s*:?-{2,}:?\s*)*\|?\s*$""")

internal fun splitMarkdownBlocks(text: String): List<MdBlock> {
    val lines = text.split('\n')
    val blocks = ArrayList<MdBlock>()
    val pending = ArrayList<String>()
    fun flushText() {
        if (pending.isNotEmpty()) {
            blocks += MdBlock.Text(pending.joinToString("\n"))
            pending.clear()
        }
    }
    var inFence = false
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        // Code fences stay inside a Text block untouched — renderMarkdown owns them.
        if (line.trimStart().startsWith("```")) {
            inFence = !inFence
            pending += line
            i++
            continue
        }
        if (inFence) {
            pending += line
            i++
            continue
        }
        // Pipe table: a "|…|" row followed by a "|---|---|" separator row.
        if (line.trimStart().startsWith("|") && i + 1 < lines.size && TABLE_SEPARATOR.matches(lines[i + 1])) {
            flushText()
            val header = tableCells(line)
            val rows = ArrayList<List<String>>()
            var j = i + 2
            while (j < lines.size && lines[j].trimStart().startsWith("|")) {
                rows += tableCells(lines[j])
                j++
            }
            blocks += MdBlock.Table(header, rows)
            i = j
            continue
        }
        val item = LIST_ITEM.matchEntire(line)
        if (item != null) {
            flushText()
            val indent = item.groupValues[1].replace("\t", "    ").length
            val marker = if (item.groupValues[2].isNotEmpty()) "•" else "${item.groupValues[3]}."
            blocks += MdBlock.ListItem(marker, item.groupValues[4], level = indent / 2)
            i++
            continue
        }
        pending += line
        i++
    }
    flushText()
    return blocks
}

private fun tableCells(row: String): List<String> {
    var r = row.trim()
    if (r.startsWith("|")) r = r.substring(1)
    if (r.endsWith("|")) r = r.dropLast(1)
    return r.split('|').map { it.trim() }
}

/**
 * Cheap cleanup for the STREAMING bubble (plain Text, no markdown parse): hide
 * raw `**` / `__`, heading `#`s and code-fence lines, and show list dashes as
 * bullets — so the text doesn't flash raw syntax and then snap to formatted
 * when the reply completes. One linear pass per coalesced update.
 */
internal fun lightStreamingMarkdown(text: String): String =
    text.split('\n')
        .filterNot { it.trimStart().startsWith("```") }
        .joinToString("\n") { line ->
            val trimmed = line.trimStart()
            val indent = line.substring(0, line.length - trimmed.length)
            val body = when {
                trimmed.startsWith("### ") -> trimmed.substring(4)
                trimmed.startsWith("## ") -> trimmed.substring(3)
                trimmed.startsWith("# ") -> trimmed.substring(2)
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> "•  " + trimmed.substring(2)
                else -> trimmed
            }
            indent + body.replace("**", "").replace("__", "")
        }
