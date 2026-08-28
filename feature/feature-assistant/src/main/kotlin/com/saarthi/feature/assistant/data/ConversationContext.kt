package com.saarthi.feature.assistant.data

/**
 * How many completed user→assistant pairs [formatConversationContext] keeps.
 *
 * `roomy` = a high-end device with the scaled 4096-token window (8000c
 * budget). Only then do we deepen LARGE history; mid-range keeps the
 * tighter caps so its prompt still fits the 2048-token window.
 *
 * Deepen only on DOCUMENT (grounded) turns. In plain chat, feeding a 2B
 * model 6 turns of its own prior verbose answers back as context reliably
 * triggers repetition loops. Normal chat keeps the tight LARGE caps
 * (3 turns); only grounded follow-ups go deep (6).
 */
internal fun conversationContextMaxTurns(
    isLarge: Boolean,
    grounded: Boolean,
    roomy: Boolean = false,
): Int {
    val deep = isLarge && roomy && grounded
    return if (deep) 6 else if (isLarge) 3 else 2
}

/**
 * True when the visible thread is longer than the recap the next prompt will
 * actually include. Compact never recaps (1B parrots any transcript).
 */
internal fun olderMessagesOmittedFromPrompt(
    completedPairCount: Int,
    isCompact: Boolean,
    isLarge: Boolean,
    grounded: Boolean,
    roomy: Boolean = false,
): Boolean {
    if (completedPairCount <= 0) return false
    if (isCompact) return true
    return completedPairCount > conversationContextMaxTurns(isLarge, grounded, roomy)
}

/**
 * Pure, dependency-free formatter for the multi-turn conversation context block.
 * Extracted as a top-level `internal` function so it is unit-testable without
 * constructing [ChatRepositoryImpl] and its 12 dependencies.
 *
 * @param turns   completed (userText, assistantText) pairs, oldest → newest,
 *                already marker-stripped and trimmed by the caller.
 * @param isLarge true for the LARGE (Gemma 4) tier; false for STANDARD (Gemma 3n).
 *                LARGE carries more thread (8000c budget); STANDARD's tight
 *                ~4900c with-docs budget gets a smaller window to avoid the
 *                high-fill repetition loops.
 * @param grounded true on document-grounded (RAG) turns, where chunks compete
 *                 for the window, so the transcript shrinks further.
 *
 * Returns the formatted block (header + "User:/Saarthi:" lines), or "" when
 * there is nothing to include. The block is sized to fit a tier/grounded budget
 * by dropping the oldest turns; the most recent turn is always kept.
 */
internal fun formatConversationContext(
    turns: List<Pair<String, String>>,
    isLarge: Boolean,
    grounded: Boolean,
    roomy: Boolean = false,
): String {
    if (turns.isEmpty()) return ""

    val deep = isLarge && roomy && grounded
    val maxTurns = conversationContextMaxTurns(isLarge, grounded, roomy)
    val perUserChars  = if (isLarge) 160 else 110
    val perReplyChars = if (isLarge) { if (grounded) 220 else 320 } else { if (grounded) 150 else 200 }
    val blockBudget   = if (deep) { if (grounded) 2200 else 3000 } else if (isLarge) { if (grounded) 1100 else 1500 } else { if (grounded) 560 else 760 }

    fun trunc(s: String, n: Int): String {
        val c = s.trim()
        return if (c.length > n) c.take(n).trimEnd() + "…" else c
    }

    val header = "Conversation so far (context only — answer the NEW message below and build on this; do not repeat or restate any of it):"

    // Largest recent window of turns that fits the block budget. Always keep at
    // least the most recent turn even if it slightly exceeds (perReplyChars
    // already bounds a single turn).
    var window = maxTurns.coerceAtMost(turns.size)
    while (window >= 1) {
        val lines = ArrayList<String>(window * 2)
        for ((u, a) in turns.takeLast(window)) {
            val uu = trunc(u, perUserChars); if (uu.isNotEmpty()) lines.add("User: $uu")
            val aa = trunc(a, perReplyChars); if (aa.isNotEmpty()) lines.add("Saarthi: $aa")
        }
        if (lines.isEmpty()) return ""
        val block = (header + "\n" + lines.joinToString("\n")).trimEnd()
        if (block.length <= blockBudget || window == 1) return block
        window--
    }
    return ""
}
