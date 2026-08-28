package com.saarthi.feature.assistant.data

import com.saarthi.core.inference.DebugLogger

/**
 * Intelligent Sliding Window: Handles context overflow for the model's compiled KV limits.
 *
 * Strategy (in priority order):
 * 1. Fast path: already within budget, return immediately.
 * 2. Drop middle history turns one-by-one until budget is met.
 * 3. If still over budget (e.g. system prompt alone overflows), hard-truncate
 *    the system turn as a last resort.
 *
 * On a single-turn (no `<start_of_turn>` markers) prompt that overflows, [pinnedTail]
 * is kept intact and the system prefix is squeezed to whatever room is left — see
 * [ChatRepositoryImpl]'s call site for why the pinned text must be the exact tail
 * [com.saarthi.core.inference.prompt.SystemPromptProvider] assembled the prompt with,
 * not a separately reconstructed copy.
 *
 * Extracted as a top-level `internal` function so it is unit-testable without
 * constructing [ChatRepositoryImpl] and its dependencies.
 */
internal fun trimPrompt(prompt: String, budget: Int = 3000, pinnedTail: String = ""): String {
    if (prompt.length <= budget) return prompt

    val marker = "<start_of_turn>"
    val turns = prompt.split(marker).filter { it.isNotBlank() }.map { marker + it }

    if (turns.size < 2) {
        DebugLogger.log("PROMPT", "WARN: single-turn prompt (${prompt.length}c) exceeds budget ($budget) — preserving user tail")
        // Critical: when the FRESH prompt is one big concatenated block
        // (system + "\n\n" + userMessage), the user message lives at the
        // end. take(budget) would chop the tail off and the model would
        // read the system prompt aloud. Instead, KEEP the user tail intact
        // and squeeze the system prefix to whatever room is left.
        //
        // We require at least 32 chars of system prefix; if pinnedTail
        // alone would blow the budget, fall back to keeping the LAST
        // `budget` chars of the prompt (still tail-aligned — guarantees
        // the user message is the most-recent thing the model sees).
        if (pinnedTail.isNotBlank() && prompt.endsWith(pinnedTail)) {
            val tailLen = pinnedTail.length
            val systemRoom = budget - tailLen - 4  // " … " separator
            return if (systemRoom >= 32) {
                val systemPrefix = prompt.substring(0, prompt.length - tailLen)
                val trimmedSystem = systemPrefix.take(systemRoom)
                "$trimmedSystem … \n$pinnedTail"
            } else {
                // User message alone is larger than budget — keep the tail.
                prompt.substring(prompt.length - budget)
            }
        }
        return prompt.takeLast(budget)
    }

    val systemTurn  = turns.first()
    val latestTurns = turns.takeLast(minOf(2, turns.size - 1))
    val middleTurns = turns.drop(1).dropLast(latestTurns.size).toMutableList()

    // Phase 1: Try to fit by dropping middle history
    var currentPrompt = buildString {
        append(systemTurn)
        middleTurns.forEach { append(it) }
        latestTurns.forEach { append(it) }
    }

    while (currentPrompt.length > budget && middleTurns.isNotEmpty()) {
        middleTurns.removeAt(0)
        currentPrompt = buildString {
            append(systemTurn)
            middleTurns.forEach { append(it) }
            latestTurns.forEach { append(it) }
        }
    }

    // Phase 2: If still over, drop ALL history except system and latest turn
    if (currentPrompt.length > budget) {
        currentPrompt = systemTurn + latestTurns.joinToString("")
    }

    // Phase 3: Final hard truncation if even system + latest user Q is too big.
    // We take the budget but ensure we at least try to keep the roles intact.
    if (currentPrompt.length > budget) {
        DebugLogger.log("PROMPT", "WARN: critical truncation to budget $budget")
        return currentPrompt.take(budget)
    }

    return currentPrompt
}
