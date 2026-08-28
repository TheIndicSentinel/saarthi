package com.saarthi.feature.assistant.data

/**
 * Phase 5.5 — release ship eval gate for DPDPA-style golden replay.
 * Locks attach→ask, chapter VI/VII + special provisions spans, and GK/opt-out
 * citation-off paths without Room or on-device LLM.
 */
internal data class DpdpaShipEvalCase(
    val id: String,
    val query: String,
    val attachmentsThisTurn: Boolean = false,
    val expectedMode: RagTurnMode,
    val minRagChars: Int = 0,
    val minChunkCount: Int = 0,
    val minAnchoredChunks: Int = 0,
    val expectCite: Boolean? = null,
    val expectStrongMatch: Boolean? = null,
    val charBudget: Int = 4000,
    val retrievedMustContain: List<String> = emptyList(),
)

/** Case ids required for a complete ship gate — regression lock on checklist scope. */
internal val DPDPA_SHIP_EVAL_REQUIRED_IDS = setOf(
    "first_attach_overview",
    "brief_attach_overview",
    "brief_attach_tight_budget",
    "chapter_vi_highlights",
    "chapter_vii_rights",
    "special_provisions",
    "penalties_schedule",
    "gk_opt_out_english",
    "gk_opt_out_hindi",
    "gk_photosynthesis",
    "gk_black_holes",
)

internal fun dpdpaShipEvalCases(): List<DpdpaShipEvalCase> {
    return listOf(
        DpdpaShipEvalCase(
            id = "first_attach_overview",
            query = ATTACH_OVERVIEW_QUERY,
            attachmentsThisTurn = true,
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            minRagChars = 80,
            minChunkCount = 1,
        ),
        DpdpaShipEvalCase(
            id = "brief_attach_overview",
            query = ATTACH_BRIEF_OVERVIEW_QUERY,
            attachmentsThisTurn = true,
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            minRagChars = 80,
            minChunkCount = 1,
        ),
        DpdpaShipEvalCase(
            id = "brief_attach_tight_budget",
            query = ATTACH_BRIEF_OVERVIEW_QUERY,
            attachmentsThisTurn = true,
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            charBudget = TIGHT_GROUNDED_RAG_CHAR_BUDGET + 80,
            minRagChars = 40,
            minChunkCount = 1,
        ),
        DpdpaShipEvalCase(
            id = "chapter_vi_highlights",
            query = "Highlights from chapter VI",
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            minRagChars = 100,
            minChunkCount = 2,
            minAnchoredChunks = 1,
            retrievedMustContain = listOf("CHAPTER VI", "children"),
        ),
        DpdpaShipEvalCase(
            id = "chapter_vii_rights",
            query = "What does chapter VII say about rights",
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            minRagChars = 80,
            minChunkCount = 1,
            retrievedMustContain = listOf("CHAPTER VII", "Data Principal"),
        ),
        DpdpaShipEvalCase(
            id = "special_provisions",
            query = "What are special provisions in this act",
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            minRagChars = 80,
            minChunkCount = 1,
            retrievedMustContain = listOf("SPECIAL PROVISIONS"),
        ),
        DpdpaShipEvalCase(
            id = "penalties_schedule",
            query = "What are the monetary penalties and amounts in the schedule",
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            minRagChars = 80,
            minChunkCount = 2,
            expectCite = true,
            expectStrongMatch = true,
            retrievedMustContain = listOf("THE SCHEDULE"),
        ),
        DpdpaShipEvalCase(
            id = "gk_opt_out_english",
            query = "Ignore the document and explain how rainbows form",
            expectedMode = RagTurnMode.GENERAL_KNOWLEDGE,
            expectCite = false,
            expectStrongMatch = false,
        ),
        DpdpaShipEvalCase(
            id = "gk_opt_out_hindi",
            query = "दस्तावेज मत explain photosynthesis to a school kid",
            expectedMode = RagTurnMode.GENERAL_KNOWLEDGE,
            expectCite = false,
            expectStrongMatch = false,
        ),
        DpdpaShipEvalCase(
            id = "gk_photosynthesis",
            query = "What is photosynthesis",
            expectedMode = RagTurnMode.GENERAL_KNOWLEDGE,
            expectCite = false,
            expectStrongMatch = false,
        ),
        DpdpaShipEvalCase(
            id = "gk_black_holes",
            query = "Don't consider the document and explain black holes",
            expectedMode = RagTurnMode.GENERAL_KNOWLEDGE,
            expectCite = false,
            expectStrongMatch = false,
        ),
    )
}

internal fun runDpdpaShipEvalCase(case: DpdpaShipEvalCase): GoldenPromptMetrics {
    val act = DpdpaActFixture.doc
    return runGoldenTurn(
        GoldenTurnSpec(
            query = case.query,
            attachmentsThisTurn = case.attachmentsThisTurn,
            boostDocUris = if (case.attachmentsThisTurn) setOf(act.uri) else emptySet(),
        ),
        listOf(act),
        charBudget = case.charBudget,
    )
}

internal fun assertDpdpaShipEvalCase(case: DpdpaShipEvalCase, metrics: GoldenPromptMetrics) {
    check(metrics.turnMode == case.expectedMode) {
        "case=${case.id} mode=${metrics.turnMode} expected=${case.expectedMode}"
    }
    if (case.minRagChars > 0) {
        check(metrics.ragChars >= case.minRagChars) {
            "case=${case.id} ragChars=${metrics.ragChars} min=${case.minRagChars}"
        }
    }
    if (case.minChunkCount > 0) {
        check(metrics.chunkCount >= case.minChunkCount) {
            "case=${case.id} chunks=${metrics.chunkCount} min=${case.minChunkCount}"
        }
    }
    if (case.minAnchoredChunks > 0) {
        check(metrics.anchoredChunkCount >= case.minAnchoredChunks) {
            "case=${case.id} anchored=${metrics.anchoredChunkCount} min=${case.minAnchoredChunks}"
        }
    }
    case.expectCite?.let { expected ->
        check(metrics.shouldCite == expected) {
            "case=${case.id} shouldCite=${metrics.shouldCite} expected=$expected"
        }
    }
    case.expectStrongMatch?.let { expected ->
        check(metrics.strongMatch == expected) {
            "case=${case.id} strongMatch=${metrics.strongMatch} expected=$expected"
        }
    }
    val joined = metrics.retrieved.joinToString("\n") { it.text }
    for (snippet in case.retrievedMustContain) {
        check(joined.contains(snippet, ignoreCase = true)) {
            "case=${case.id} missing snippet='$snippet'"
        }
    }
}
