package com.saarthi.feature.assistant.data

/**
 * Tier 4.12 — multi-doc golden scenario matrix (Patterns D–J).
 * JVM replay: scope, retrieval primary doc, anchoring, and shape boundaries.
 */
internal data class Tier4ScenarioCase(
    val id: String,
    val query: String,
    val docs: List<GoldenDoc>,
    val attachmentsThisTurn: Boolean = false,
    val boostDocUris: Set<String> = emptySet(),
    val attachmentUris: List<String> = emptyList(),
    val activeDocUri: String? = null,
    val priorQuery: String? = null,
    val expectedMode: RagTurnMode,
    val expectedScopeLabel: String? = null,
    val restrictMustContain: Set<String> = emptySet(),
    val restrictMustEqual: Set<String>? = null,
    val expectEqualSlots: Boolean? = null,
    val minRagChars: Int = 0,
    val minChunkCount: Int = 0,
    val minAnchoredChunks: Int = 0,
    val expectCite: Boolean? = null,
    val expectStrongMatch: Boolean? = null,
    val charBudget: Int = 4000,
    val retrievedTextMustContain: List<String> = emptyList(),
    val expectedTopDocUri: String? = null,
    val unattachedExternalMustBeActive: Boolean? = null,
    val shapeInstructionMustContain: List<String> = emptyList(),
)

internal val TIER4_GOLDEN_SCENARIO_REQUIRED_IDS = setOf(
    "guide_only_overview",
    "guide_only_penalties",
    "named_in_guide",
    "attach_guide_overview",
    "generic_penalties_act_primary",
    "this_document_active_scope",
    "compare_equal_slots",
    "unattached_gdpr_boundary",
    "post_compare_active_followup",
    "structure_count_shape",
)

internal fun tier4GoldenScenarioCases(): List<Tier4ScenarioCase> {
    val act = DpdpaActFixture.doc
    val guide = GoldenFixtures.dpdpGuide
    val guideAndAct = listOf(act, guide)
    val pair = GoldenFixtures.englishPair
    return listOf(
        Tier4ScenarioCase(
            id = "guide_only_overview",
            query = ATTACH_BRIEF_OVERVIEW_QUERY,
            docs = listOf(guide),
            attachmentsThisTurn = true,
            boostDocUris = setOf(guide.uri),
            attachmentUris = listOf(guide.uri),
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            expectedScopeLabel = RetrievalScope.ATTACH_OVERVIEW.name,
            restrictMustEqual = setOf(guide.uri),
            minRagChars = 40,
            minChunkCount = 1,
            expectedTopDocUri = guide.uri,
            retrievedTextMustContain = listOf("Practitioner guide"),
        ),
        Tier4ScenarioCase(
            id = "guide_only_penalties",
            query = "what are penalties mentioned in this practitioner guide",
            docs = listOf(guide),
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            minRagChars = 30,
            minChunkCount = 1,
            expectedTopDocUri = guide.uri,
            retrievedTextMustContain = listOf("Penalties"),
        ),
        Tier4ScenarioCase(
            id = "named_in_guide",
            query = "In the practitioner guide what are penalties",
            docs = guideAndAct,
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            expectedScopeLabel = RetrievalScope.NAMED.name,
            restrictMustEqual = setOf(guide.uri),
            minRagChars = 30,
            minChunkCount = 1,
            expectedTopDocUri = guide.uri,
            retrievedTextMustContain = listOf("Penalties"),
        ),
        Tier4ScenarioCase(
            id = "attach_guide_overview",
            query = ATTACH_OVERVIEW_QUERY,
            docs = guideAndAct,
            attachmentsThisTurn = true,
            boostDocUris = setOf(guide.uri),
            attachmentUris = listOf(guide.uri),
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            expectedScopeLabel = RetrievalScope.ATTACH_OVERVIEW.name,
            restrictMustEqual = setOf(guide.uri),
            minRagChars = 40,
            minChunkCount = 1,
            expectedTopDocUri = guide.uri,
            retrievedTextMustContain = listOf("Practitioner guide"),
        ),
        Tier4ScenarioCase(
            id = "generic_penalties_act_primary",
            query = "what are the monetary penalties in the schedule",
            docs = guideAndAct,
            activeDocUri = act.uri,
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            expectedScopeLabel = RetrievalScope.ACTIVE_DOC.name,
            restrictMustEqual = setOf(act.uri),
            minRagChars = 60,
            minChunkCount = 1,
            expectCite = true,
            expectStrongMatch = true,
            expectedTopDocUri = act.uri,
            retrievedTextMustContain = listOf("THE SCHEDULE"),
        ),
        Tier4ScenarioCase(
            id = "this_document_active_scope",
            query = "is agreement me jurmana kitna hai",
            docs = pair,
            activeDocUri = GoldenFixtures.NDA_URI,
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            expectedScopeLabel = RetrievalScope.NAMED.name,
            restrictMustEqual = setOf(GoldenFixtures.NDA_URI),
            minRagChars = 30,
            minChunkCount = 1,
            expectedTopDocUri = GoldenFixtures.NDA_URI,
            retrievedTextMustContain = listOf("5 lakh"),
        ),
        Tier4ScenarioCase(
            id = "compare_equal_slots",
            query = "compare both documents",
            docs = pair,
            expectedMode = RagTurnMode.MIXED,
            expectedScopeLabel = RetrievalScope.SESSION.name,
            expectEqualSlots = true,
            minRagChars = 40,
            minChunkCount = 1,
        ),
        Tier4ScenarioCase(
            id = "unattached_gdpr_boundary",
            query = "Compare GDPR with DPDPA",
            docs = listOf(act),
            expectedMode = RagTurnMode.MIXED,
            unattachedExternalMustBeActive = true,
            shapeInstructionMustContain = listOf("GDPR", "Do NOT"),
        ),
        Tier4ScenarioCase(
            id = "post_compare_active_followup",
            query = "what are penalties in the practitioner guide",
            docs = guideAndAct,
            priorQuery = "compare both files",
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            expectedScopeLabel = RetrievalScope.NAMED.name,
            restrictMustEqual = setOf(guide.uri),
            minRagChars = 30,
            minChunkCount = 1,
            expectedTopDocUri = guide.uri,
            retrievedTextMustContain = listOf("Penalties"),
        ),
        Tier4ScenarioCase(
            id = "structure_count_shape",
            query = "How many chapters are there in total",
            docs = listOf(act),
            expectedMode = RagTurnMode.DOCUMENT_GROUNDED,
            minRagChars = 60,
            minChunkCount = 1,
            shapeInstructionMustContain = listOf("STRUCTURE COUNT", "count"),
        ),
    )
}

internal fun runTier4ScenarioCase(case: Tier4ScenarioCase): GoldenPromptMetrics =
    runGoldenTurn(
        GoldenTurnSpec(
            query = case.query,
            priorQuery = case.priorQuery,
            attachmentsThisTurn = case.attachmentsThisTurn,
            boostDocUris = case.boostDocUris,
            attachmentUris = case.attachmentUris,
            activeDocUri = case.activeDocUri,
        ),
        case.docs,
        charBudget = case.charBudget,
    )

internal fun assertTier4ScenarioCase(case: Tier4ScenarioCase, metrics: GoldenPromptMetrics) {
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
  if (case.restrictMustContain.isNotEmpty()) {
    check(case.restrictMustContain.all { it in metrics.restrictDocUris }) {
      "case=${case.id} restrict=${metrics.restrictDocUris} must contain=${case.restrictMustContain}"
    }
  }
  case.expectEqualSlots?.let { expected ->
    check(metrics.routeEqualSlots == expected) {
      "case=${case.id} equalSlots=${metrics.routeEqualSlots} expected=$expected"
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
  case.unattachedExternalMustBeActive?.let { expected ->
    check(metrics.unattachedExternalActive == expected) {
      "case=${case.id} unattachedExternal=${metrics.unattachedExternalActive} expected=$expected"
    }
  }
  if (case.shapeInstructionMustContain.isNotEmpty()) {
    val instruction = goldenAnswerShapeInstruction(
      query = case.query,
      docNames = case.docs.map { it.name },
      retrieved = metrics.retrieved,
      turnMode = metrics.turnMode,
      attachmentsThisTurn = case.attachmentsThisTurn,
    )
    for (snippet in case.shapeInstructionMustContain) {
      check(instruction.contains(snippet, ignoreCase = true)) {
        "case=${case.id} shape instruction missing '$snippet'"
      }
    }
  }
}
