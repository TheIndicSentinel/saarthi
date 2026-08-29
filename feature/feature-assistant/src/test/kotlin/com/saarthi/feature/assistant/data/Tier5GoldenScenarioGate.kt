package com.saarthi.feature.assistant.data

/**
 * Tier 5.6 — JVM golden scenarios locking active-doc follow-up and tabular prefer.
 */
internal data class Tier5ScenarioCase(
    val id: String,
    val query: String,
    val docs: List<GoldenDoc>,
    val activeDocUri: String? = null,
    val expectedMode: RagTurnMode,
    val expectedScopeLabel: String? = null,
    val restrictMustEqual: Set<String>? = null,
    val minRagChars: Int = 0,
    val minChunkCount: Int = 0,
    val expectedTopDocUri: String? = null,
    val retrievedTextMustContain: List<String> = emptyList(),
)

internal val TIER5_GOLDEN_SCENARIO_REQUIRED_IDS = setOf(
    "active_doc_schedule_penalties",
    "active_doc_followup_obligations",
    "structure_count_registry_hint",
)

internal fun tier5GoldenScenarioCases(): List<Tier5ScenarioCase> {
    val act = DpdpaActFixture.doc
    val guide = GoldenFixtures.dpdpGuide
    val guideAndAct = listOf(act, guide)
    return listOf(
        Tier5ScenarioCase(
            id = "active_doc_schedule_penalties",
            query = "what are the monetary penalties in the schedule",
            docs = guideAndAct,
            activeDocUri = act.uri,
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            expectedScopeLabel = RetrievalScope.ACTIVE_DOC.name,
            restrictMustEqual = setOf(act.uri),
            minRagChars = 60,
            minChunkCount = 1,
            expectedTopDocUri = act.uri,
            retrievedTextMustContain = listOf("THE SCHEDULE"),
        ),
        Tier5ScenarioCase(
            id = "active_doc_followup_obligations",
            query = "what are core fiduciary obligations",
            docs = guideAndAct,
            activeDocUri = act.uri,
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            expectedScopeLabel = RetrievalScope.ACTIVE_DOC.name,
            restrictMustEqual = setOf(act.uri),
            minRagChars = 40,
            minChunkCount = 1,
            expectedTopDocUri = act.uri,
            retrievedTextMustContain = listOf("OBLIGATIONS"),
        ),
        Tier5ScenarioCase(
            id = "structure_count_registry_hint",
            query = "How many chapters are there in total",
            docs = listOf(act),
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            minRagChars = 60,
            minChunkCount = 1,
            retrievedTextMustContain = listOf("CHAPTER"),
        ),
    )
}

internal fun runTier5ScenarioCase(case: Tier5ScenarioCase): GoldenPromptMetrics =
    runGoldenTurn(
        GoldenTurnSpec(
            query = case.query,
            activeDocUri = case.activeDocUri,
        ),
        case.docs,
    )

internal fun assertTier5ScenarioCase(case: Tier5ScenarioCase, metrics: GoldenPromptMetrics) {
    check(metrics.turnMode == case.expectedMode) {
        "case=${case.id} mode=${metrics.turnMode} expected=${case.expectedMode}"
    }
    case.expectedScopeLabel?.let { expected ->
        check(metrics.retrievalScopeLabel == expected) {
            "case=${case.id} scope=${metrics.retrievalScopeLabel} expected=$expected"
        }
    }
    case.restrictMustEqual?.let { expected ->
        check(metrics.restrictDocUris == expected) {
            "case=${case.id} restrict=${metrics.restrictDocUris} expected=$expected"
        }
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
    val joined = metrics.retrieved.joinToString("\n") { it.text }
    for (snippet in case.retrievedTextMustContain) {
        check(joined.contains(snippet, ignoreCase = true)) {
            "case=${case.id} missing snippet='$snippet'"
        }
    }
    case.expectedTopDocUri?.let { uri ->
        val top = goldenPipelinePrimaryDocUri(metrics.retrieved)
        check(top == uri) {
            "case=${case.id} topDoc=$top expected=$uri"
        }
    }
}
