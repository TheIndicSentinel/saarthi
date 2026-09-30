package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage

/**
 * What a chat turn does after prompt assembly.
 *
 * [Generate] runs the model on [Generate.prompt]. [DirectReply] shows
 * [DirectReply.text] as the assistant reply WITHOUT inference — used for
 * deterministic outcomes (retrieval miss, grounded delivery failure) that
 * used to be sent to the model as if the user had typed them, so the reply
 * was unpredictable and always English.
 */
/**
 * Send the system prompt as LiteRT-LM's Conversation system instruction (a
 * separate system turn) instead of one concatenated user message. OFF until
 * validated on physical phones (SM8550 / Android 16 SIGKILL history, answer
 * quality per tier and language). When ON, only the FRESH chat path splits:
 * everything before the pinned tail becomes the system instruction; the
 * critical tail (language + persona rules), excerpts and the question stay in
 * the user turn, still last.
 */
internal const val SYSTEM_INSTRUCTION_SPLIT_ENABLED = false

internal sealed interface TurnPlan {
    /**
     * [grounded] selects the engine's grounded sampler (strict document-excerpt
     * turns). [systemInstruction], when set, goes to the engine as a separate
     * system turn and [prompt] is only the user turn — see
     * [SYSTEM_INSTRUCTION_SPLIT_ENABLED].
     */
    data class Generate(
        val prompt: String,
        val grounded: Boolean = false,
        val systemInstruction: String? = null,
    ) : TurnPlan
    data class DirectReply(val text: String) : TurnPlan
}

/**
 * Localized deterministic retrieval-miss reply. English keeps the detailed,
 * query-specific message ([englishMessage]); other languages get the
 * localized generic form, since the detailed templates splice English
 * phrasing around query terms.
 */
internal fun localizedRetrievalMissReply(
    lang: SupportedLanguage,
    englishMessage: String,
    isDocumentMismatch: Boolean,
): String = when {
    lang == SupportedLanguage.ENGLISH -> englishMessage
    isDocumentMismatch -> lang.ragDocumentMismatchReply
    else -> lang.ragRetrievalMissReply
}
