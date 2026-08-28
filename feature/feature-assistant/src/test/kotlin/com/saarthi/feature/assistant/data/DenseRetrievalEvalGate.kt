package com.saarthi.feature.assistant.data

/**
 * Phase 6.1 — eval gate comparing lexical golden replay vs dense spike readiness.
 * Spike stays off until these lexical cases fail and [denseRetrievalSpikeEnabled] is true.
 */
internal data class DenseRetrievalEvalCase(
    val id: String,
    val query: String,
    val retrievedMustContain: List<String> = emptyList(),
    val minRagChars: Int = 60,
)

internal val DENSE_RETRIEVAL_EVAL_REQUIRED_IDS = setOf(
    "paraphrase_data_boss",
    "colloquial_appeal",
    "special_provisions_lexical",
)

internal fun denseRetrievalEvalCases(): List<DenseRetrievalEvalCase> = listOf(
    DenseRetrievalEvalCase(
        id = "paraphrase_data_boss",
        query = "What are data boss duties in this act",
        retrievedMustContain = listOf("Data Fiduciary", "OBLIGATIONS"),
    ),
    DenseRetrievalEvalCase(
        id = "colloquial_appeal",
        query = "How can I appeal a penalty decision",
        retrievedMustContain = listOf("appeal", "Board"),
    ),
    DenseRetrievalEvalCase(
        id = "special_provisions_lexical",
        query = "What are special provisions in this act",
        retrievedMustContain = listOf("SPECIAL PROVISIONS"),
    ),
)

internal fun runDenseRetrievalEvalCase(case: DenseRetrievalEvalCase): GoldenPromptMetrics =
    runGoldenTurn(
        GoldenTurnSpec(query = case.query),
        listOf(DpdpaActFixture.doc),
    )

internal fun assertDenseRetrievalEvalCase(case: DenseRetrievalEvalCase, metrics: GoldenPromptMetrics) {
    check(metrics.ragChars >= case.minRagChars) {
        "case=${case.id} ragChars=${metrics.ragChars} min=${case.minRagChars}"
    }
    val joined = metrics.retrieved.joinToString("\n") { it.text }
    for (snippet in case.retrievedMustContain) {
        check(joined.contains(snippet, ignoreCase = true)) {
            "case=${case.id} missing snippet='$snippet'"
        }
    }
}

/** Spike is not production-ready until flag is on and lexical gate still passes. */
internal fun denseRetrievalSpikeReadyForExperiment(): Boolean =
    !denseRetrievalSpikeEnabled()
