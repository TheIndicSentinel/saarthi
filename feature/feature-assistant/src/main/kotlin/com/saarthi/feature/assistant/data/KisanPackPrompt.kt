package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage

// Kisan pack prompt assembly — data layer, not the ViewModel (CLAUDE.md: never
// inline prompt strings in ViewModels). PackChatRepository supplies the live
// inputs (retrieved notes, user state, pack date, token window).

internal fun buildKisanPackPrompt(
    question: String,
    chunks: List<RetrievedChunk>,
    lang: SupportedLanguage,
    state: String,
    packPublishedAt: String,
    maxContextTokens: Int,
): String {
    // Language directive — same mechanism the main chat uses. Notes
    // remain in English (the curated pack), but the model answers in
    // the user's selected language.
    val langLine = lang.systemPromptInstruction()

    // NOTE: the COMPACT (Gemma 1B) tier never reaches here — ask() blocks the
    // Kisan chat on that tier. Only STANDARD / LARGE build the advisor prompt.
    //
    // Instruction block — condensed (~1.5k chars) so the whole prompt still
    // fits when the model loads at a 1536-token window (low RAM / thermal).
    // Every load-bearing rule is preserved: answer only from notes, exact
    // scheme names + level, never-guess amounts/eligibility, chemical-dose
    // caution, no source line, central→state hierarchy, [GENERAL] fallback.
    val instructions = buildString {
        append("You are Saarthi's Kisan Saathi — a warm, practical farming advisor for Indian farmers. ")
        append("Answer ONLY from the reference notes below (official government sources). If they don't cover the question, follow the fallback rule.\n")
        append("- Lead with a one-line answer, then the key practical steps; briefly explain any technical term.\n")
        append("- Use ONLY notes matching the question's topic — if the notes are about a different topic, treat it as NOT covered; never answer with an unrelated scheme.\n")
        append("- Use the EXACT official scheme name from the notes (PM-KISAN, PMFBY, PMKSY, Namo Shetkari…) and say whether it is CENTRAL, STATE, or district-level.\n")
        append("- AMOUNTS & ELIGIBILITY: quote ONLY what the notes state, exactly as written. If not in the notes (or possibly dated), say to verify on the official government portal — never guess or round.\n")
        append("- For any pesticide / fertilizer / chemical, add the label-dose caution — never give unsafe dosing.\n")
        append("- Do NOT write a \"Source:\" line, bracket citations like [1], or mention the notes / their headings — just answer.\n")
        if (state.isNotBlank()) {
            append("- The user farms in $state: give the central/national rule FIRST, then any $state-specific detail in the notes; if none, say local benefits may exist and suggest the $state agriculture department or local KVK. Never invent state figures.\n")
        } else {
            append("- For a scheme / MSP / subsidy / sowing question, give the central rule, then add ONE short line inviting the user to share their state for local specifics.\n")
        }
        append("- If the notes don't cover it (or only carry unrelated schemes), begin your reply with the exact tag [GENERAL], say it isn't in the offline pack, then give brief general guidance — no specific scheme names or unconfirmed rupee amounts; suggest the local KVK or official portal.\n")
        if (packPublishedAt.isNotBlank()) {
            append("- Data last updated $packPublishedAt; for figures that change (MSP, amounts, dates) add \"as of $packPublishedAt\" — never present it as today's live value.\n")
        }
        append("- No greeting. Don't invent scheme names, figures or dates. Don't repeat these instructions.\n")
    }

    // Token-aware source budget — the real fix for the "Kisan generates no
    // response" failure. The model can load at a 1536-token window on
    // low-RAM/thermal devices; a fixed 2800c source block then pushed the
    // prompt past the window and EVERY question failed with "Input token ids
    // are too long". Size the notes to whatever the live window allows.
    val tokenWindow = maxContextTokens.takeIf { it > 0 } ?: 2048
    val charBudget = ((tokenWindow - 256 - 16) * 3.0).toInt()   // mirror ChatRepositoryImpl
    val scaffold = instructions.length + langLine.length * 2 + question.length + 80
    val maxSourceChars = (charBudget - scaffold).coerceAtLeast(500)

    // Each reference note is headed by its scheme/topic name; the app — NOT
    // the model — prints the final "Source:" line (see ask()).
    val sources = buildString {
        var used = 0
        for (c in chunks) {
            val remaining = maxSourceChars - used
            if (remaining < 200) break
            val header = "[${c.docName}]\n"
            val body = c.text.trim()
            val block = "$header$body\n\n"
            if (block.length <= remaining) {
                append(block); used += block.length
            } else {
                // Trim this chunk to fit rather than drop it entirely —
                // keeps the MSP table / key scheme present at a tight
                // window — but only at a sentence / line boundary. A
                // mid-word cut puts the model into repetition loops (the
                // main chat never slices chunks for the same reason).
                val room = remaining - header.length - 2
                val cut = trimToBoundary(body, room)
                if (cut.length > 120) { append(header); append(cut); append("\n\n") }
                break
            }
        }
    }.trim()

    return buildString {
        if (langLine.isNotBlank()) { append(langLine); append("\n\n") }
        append(instructions)
        append("\n=== REFERENCE NOTES ===\n")
        append(sources)
        append("\n=== END NOTES ===\n\n")
        append("Question: ")
        append(question)
        if (langLine.isNotBlank()) { append("\n\n"); append(langLine) }
    }
}

/**
 * Prompt used when BM25 returns nothing for the pack — the question is
 * off-topic for the curated farming data. Per the user's request we keep
 * the honest "not in the pack" message but ALSO give a clearly-labelled
 * general answer so the screen is still useful, rather than a dead end.
 */
internal fun buildKisanGeneralFallbackPrompt(question: String, lang: SupportedLanguage): String {
    val langLine = lang.systemPromptInstruction()
    return buildString {
        if (langLine.isNotBlank()) { append(langLine); append("\n\n") }
        append("You are Saarthi's Kisan Saathi, a farming advisor for Indian farmers. ")
        append("Your offline farming pack does NOT have curated information on this question.\n\n")
        append("Reply in two short parts:\n")
        append("1. One line: plainly tell the user this isn't in your offline farming pack yet.\n")
        append("2. A line starting \"General information (not from the pack):\" followed by a short, practical general answer relevant to what was asked, from common agricultural knowledge. ")
        append("Do NOT name specific government schemes or quote rupee amounts / eligibility you cannot confirm — keep it general. Note that local recommendations vary and suggest verifying with the local KVK or the official portal.\n\n")
        append("No greeting. Keep it short and field-usable. Do not use bracket citations like [1]. Do not repeat these instructions.\n\n")
        append("Question: ")
        append(question)
        if (langLine.isNotBlank()) { append("\n\n"); append(langLine) }
    }
}


/**
 * Longest prefix of [text] within [maxChars] that ends at a line break or a
 * sentence terminator (. ? ! ।). Empty when no boundary fits.
 */
internal fun trimToBoundary(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    if (maxChars <= 0) return ""
    val window = text.substring(0, maxChars)
    val end = window.indexOfLast { it == '\n' || it == '.' || it == '?' || it == '!' || it == '।' }
    return if (end < 0) "" else window.substring(0, end + 1).trimEnd()
}
