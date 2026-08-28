package com.saarthi.feature.assistant.data

/**
 * Phase 0.3 — golden harness archetype × [RagTurnMode] matrix.
 * Locks mode classification, retrieval assembly, and citation gating across
 * representative query/session shapes (document-agnostic fixtures).
 */
internal enum class GoldenQueryArchetype {
    SMALL_TALK,
    ASSISTANT_IDENTITY,
    GENERAL_KNOWLEDGE,
    DOCUMENT_OPT_OUT,
    ATTACH_OVERVIEW,
    DOCUMENT_FACTUAL,
    CHAPTER_SPAN,
    STRUCTURE_COUNT,
    PENALTY_SCHEDULE,
    MIXED_DOC_GK,
    OFF_TOPIC_AMBIGUOUS,
    TABULAR_ENTITY,
    COMPARE_MULTI_DOC,
    NO_SESSION_DOCS,
}

internal data class GoldenMatrixCase(
    val id: String,
    val archetype: GoldenQueryArchetype,
    val query: String,
    val docs: List<GoldenDoc>,
    val attachmentsThisTurn: Boolean = false,
    val boostDocUris: Set<String> = emptySet(),
    val priorQuery: String? = null,
    val expectedMode: RagTurnMode,
    val minRagChars: Int = 0,
    val minChunkCount: Int = 0,
    val minAnchoredChunks: Int = 0,
    val expectCite: Boolean? = null,
    val expectStrongMatch: Boolean? = null,
    val retrievedTextMustContain: List<String> = emptyList(),
    val expectedTopDocUri: String? = null,
)

internal fun goldenTurnMatrixCases(): List<GoldenMatrixCase> {
    val act = DpdpaActFixture.doc
    val pair = GoldenFixtures.englishPair
    return listOf(
        GoldenMatrixCase(
            id = "no_docs_plain_chat",
            archetype = GoldenQueryArchetype.NO_SESSION_DOCS,
            query = "Explain photosynthesis",
            docs = emptyList(),
            expectedMode = RagTurnMode.PLAIN_CHAT,
            expectCite = false,
            expectStrongMatch = false,
        ),
        GoldenMatrixCase(
            id = "greeting_with_indexed_docs",
            archetype = GoldenQueryArchetype.SMALL_TALK,
            query = "Hi",
            docs = listOf(act),
            expectedMode = RagTurnMode.GENERAL_KNOWLEDGE,
            expectCite = false,
            expectStrongMatch = false,
        ),
        GoldenMatrixCase(
            id = "assistant_identity_with_docs",
            archetype = GoldenQueryArchetype.ASSISTANT_IDENTITY,
            query = "Tell me about yourself",
            docs = listOf(act),
            expectedMode = RagTurnMode.PLAIN_CHAT,
            expectCite = false,
            expectStrongMatch = false,
        ),
        GoldenMatrixCase(
            id = "gk_topic_with_indexed_docs",
            archetype = GoldenQueryArchetype.GENERAL_KNOWLEDGE,
            query = "Explain photosynthesis to a school kid",
            docs = listOf(act),
            expectedMode = RagTurnMode.GENERAL_KNOWLEDGE,
            expectCite = false,
            expectStrongMatch = false,
        ),
        GoldenMatrixCase(
            id = "document_opt_out",
            archetype = GoldenQueryArchetype.DOCUMENT_OPT_OUT,
            query = "Don't consider the document and explain black holes",
            docs = listOf(act),
            expectedMode = RagTurnMode.GENERAL_KNOWLEDGE,
            expectCite = false,
            expectStrongMatch = false,
        ),
        GoldenMatrixCase(
            id = "attach_overview_turn",
            archetype = GoldenQueryArchetype.ATTACH_OVERVIEW,
            query = ATTACH_OVERVIEW_QUERY,
            docs = listOf(act),
            attachmentsThisTurn = true,
            boostDocUris = setOf(act.uri),
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            minRagChars = 80,
            minChunkCount = 1,
        ),
        GoldenMatrixCase(
            id = "chapter_span_highlights",
            archetype = GoldenQueryArchetype.CHAPTER_SPAN,
            query = "Highlights from chapter VI",
            docs = listOf(act),
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            minRagChars = 100,
            minChunkCount = 2,
            minAnchoredChunks = 1,
            retrievedTextMustContain = listOf("CHAPTER VI", "children"),
        ),
        GoldenMatrixCase(
            id = "structure_count_chapters",
            archetype = GoldenQueryArchetype.STRUCTURE_COUNT,
            query = "How many chapters are there",
            docs = listOf(act),
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            minRagChars = 40,
            minChunkCount = 1,
            expectCite = true,
        ),
        GoldenMatrixCase(
            id = "penalty_schedule_contract",
            archetype = GoldenQueryArchetype.PENALTY_SCHEDULE,
            query = "What are the monetary penalties and amounts in the schedule",
            docs = listOf(act),
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            minRagChars = 80,
            minChunkCount = 2,
            expectCite = true,
            expectStrongMatch = true,
            retrievedTextMustContain = listOf("THE SCHEDULE", "33. Penalties"),
            expectedTopDocUri = act.uri,
        ),
        GoldenMatrixCase(
            id = "document_factual_obligations",
            archetype = GoldenQueryArchetype.DOCUMENT_FACTUAL,
            query = "What are obligations of data fiduciary in this act",
            docs = listOf(act),
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            minRagChars = 80,
            minChunkCount = 1,
            expectCite = true,
            expectStrongMatch = true,
            retrievedTextMustContain = listOf("OBLIGATIONS", "Data Fiduciary"),
        ),
        GoldenMatrixCase(
            id = "mixed_doc_and_gk",
            archetype = GoldenQueryArchetype.MIXED_DOC_GK,
            query = "What are penalties in the act and explain black holes to a kid",
            docs = listOf(act),
            expectedMode = RagTurnMode.MIXED,
            minRagChars = 80,
            minChunkCount = 1,
            expectCite = true,
        ),
        GoldenMatrixCase(
            id = "off_topic_joke",
            archetype = GoldenQueryArchetype.OFF_TOPIC_AMBIGUOUS,
            query = "Tell me a joke",
            docs = listOf(act),
            expectedMode = RagTurnMode.PLAIN_CHAT,
            expectCite = false,
            expectStrongMatch = false,
        ),
        GoldenMatrixCase(
            id = "off_topic_applicability_no_doc_cues",
            archetype = GoldenQueryArchetype.OFF_TOPIC_AMBIGUOUS,
            query = "Is this act applicable to processing children's personal data",
            docs = listOf(act),
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            minRagChars = 40,
            minChunkCount = 1,
        ),
        GoldenMatrixCase(
            id = "tabular_entity_lookup",
            archetype = GoldenQueryArchetype.TABULAR_ENTITY,
            query = "what is the salary credit in the attached statement document",
            docs = pair,
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            minRagChars = 40,
            minChunkCount = 1,
            expectCite = true,
            expectStrongMatch = true,
            expectedTopDocUri = GoldenFixtures.STMT_URI,
            retrievedTextMustContain = listOf("Salary credit"),
        ),
        GoldenMatrixCase(
            id = "compare_multi_doc",
            archetype = GoldenQueryArchetype.COMPARE_MULTI_DOC,
            query = "compare both files",
            docs = pair,
            expectedMode = RagTurnMode.MIXED,
            minRagChars = 40,
            minChunkCount = 1,
        ),
        GoldenMatrixCase(
            id = "hinglish_penalty_nda",
            archetype = GoldenQueryArchetype.PENALTY_SCHEDULE,
            query = "is agreement me jurmana kitna hai",
            docs = pair,
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            minRagChars = 40,
            minChunkCount = 1,
            expectCite = true,
            expectStrongMatch = true,
            expectedTopDocUri = GoldenFixtures.NDA_URI,
        ),
    )
}

internal fun runGoldenMatrixCase(case: GoldenMatrixCase): GoldenPromptMetrics =
    runGoldenTurn(
        GoldenTurnSpec(
            query = case.query,
            priorQuery = case.priorQuery,
            attachmentsThisTurn = case.attachmentsThisTurn,
            boostDocUris = case.boostDocUris,
        ),
        case.docs,
    )

internal fun assertGoldenMatrixCase(case: GoldenMatrixCase, metrics: GoldenPromptMetrics) {
  val classified = classifyRagTurnMode(
    query = case.query,
    sessionDocCount = case.docs.size,
    attachmentsThisTurn = case.attachmentsThisTurn,
    sessionDocNames = case.docs.map { it.name },
    priorQuery = case.priorQuery,
  )
  check(classified == case.expectedMode) {
    "case=${case.id} classify=$classified expected=${case.expectedMode}"
  }
  check(metrics.turnMode == case.expectedMode) {
    "case=${case.id} harnessMode=${metrics.turnMode} expected=${case.expectedMode}"
  }
  if (case.minRagChars > 0) {
    check(metrics.ragChars >= case.minRagChars) {
      "case=${case.id} ragChars=${metrics.ragChars} min=${case.minRagChars}"
    }
  }
  if (case.minChunkCount > 0) {
    check(metrics.chunkCount >= case.minChunkCount) {
      "case=${case.id} chunkCount=${metrics.chunkCount} min=${case.minChunkCount}"
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
