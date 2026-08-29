package com.saarthi.feature.assistant.data

/**
 * Phase 0 — replay matrix for NASA Dynamic Earth session + legal baseline.
 * Baseline cases must stay green; tracking cases lock metrics for Phase A–B work.
 */
internal enum class Phase0ReplayTrack {
    /** Regression lock — must pass in CI. */
    BASELINE,
    /** Measurement only — harness runs; strict retrieval asserts optional. */
    TRACKING,
}

internal data class Phase0ReplayCase(
    val id: String,
    val query: String,
    val track: Phase0ReplayTrack = Phase0ReplayTrack.TRACKING,
    val docs: List<GoldenDoc> = DynamicEarthEducatorFixture.singleDoc,
    val attachmentsThisTurn: Boolean = false,
    val boostDocUris: Set<String> = emptySet(),
    val attachmentUris: List<String> = emptyList(),
    val activeDocUri: String? = null,
    val priorQuery: String? = null,
    val expectedMode: RagTurnMode = RagTurnMode.DOCUMENT_GROUNDED,
    val expectedScopeLabel: String? = null,
    val forbidMetaPath: Boolean = false,
    val expectedMetaReason: String? = null,
    val minRagChars: Int = 0,
    val minChunkCount: Int = 0,
    val retrievedMustContain: List<String> = emptyList(),
    val retrievedMustNotContain: List<String> = emptyList(),
    /** Current production cite gate (Wave 3 P14). */
    val expectCite: Boolean? = null,
    /** Target Policy A: grounded doc turn with citable chunks should cite. */
    val expectCitePolicyA: Boolean = false,
    val expectStrongMatch: Boolean? = null,
    val charBudget: Int = 4000,
)

internal data class Phase0ReplayMetrics(
    val prompt: GoldenPromptMetrics,
    val searchPath: RagSearchPath,
    val metaReason: String?,
    val citation: Phase0CitationProbeResult,
)

/** Baseline ids — do not regress while building Phase A/B. */
internal val PHASE0_REPLAY_BASELINE_IDS = setOf(
    "attach_overview_short",
    "guide_purpose",
    "weather_vs_climate",
    "greenhouse_gases",
    "ice_sea_level",
    "evidence_document_cue",
    "legal_dpdpa_penalties",
)

/** Full NASA chat + legal anchor — every id must exist in [phase0ReplayCases]. */
internal val PHASE0_REPLAY_ALL_IDS = setOf(
    "attach_overview_short",
    "guide_purpose",
    "climate_components",
    "weather_vs_climate",
    "sun_influence",
    "atmosphere_temperature",
    "oceans_role",
    "evidence_document_cue",
    "earth_change_factors",
    "guide_activities",
    "climate_change_effects",
    "greenhouse_gases",
    "carbon_cycle",
    "climate_models",
    "natural_vs_human",
    "feedback_examples",
    "observations_measurements",
    "ice_sea_level",
    "key_conclusions",
    "topics_not_discussed",
    "earth_system_interactions",
    "legal_dpdpa_penalties",
)

internal fun phase0ReplayCases(): List<Phase0ReplayCase> {
    val guide = DynamicEarthEducatorFixture.doc
    val act = DpdpaActFixture.doc
    return listOf(
        Phase0ReplayCase(
            id = "attach_overview_short",
            track = Phase0ReplayTrack.BASELINE,
            query = ATTACH_BRIEF_OVERVIEW_QUERY,
            attachmentsThisTurn = true,
            boostDocUris = setOf(guide.uri),
            attachmentUris = listOf(guide.uri),
            expectedScopeLabel = RetrievalScope.ATTACH_OVERVIEW.name,
            expectedMetaReason = "overview",
            minRagChars = 40,
            minChunkCount = 1,
            retrievedMustContain = listOf("Dynamic Earth"),
            expectCite = true,
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "guide_purpose",
            track = Phase0ReplayTrack.BASELINE,
            query = "What is the main purpose of the Dynamic Earth Educator Guide?",
            minRagChars = 40,
            minChunkCount = 1,
            retrievedMustContain = listOf("supplement", "planetarium"),
            expectCite = true,
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "climate_components",
            query = "What are the main components of Earth's climate system?",
            minRagChars = 40,
            minChunkCount = 1,
            retrievedMustContain = listOf("atmosphere", "hydrosphere"),
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "weather_vs_climate",
            track = Phase0ReplayTrack.BASELINE,
            query = "What is the difference between weather and climate?",
            minRagChars = 40,
            minChunkCount = 1,
            retrievedMustContain = listOf("Weather is what we get"),
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "sun_influence",
            query = "How does the Sun influence Earth's climate",
            minRagChars = 40,
            minChunkCount = 1,
            retrievedMustContain = listOf("primary source of energy"),
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "atmosphere_temperature",
            query = "What role does the atmosphere play in regulating Earth's temperature?",
            minRagChars = 40,
            minChunkCount = 1,
            retrievedMustContain = listOf("greenhouse"),
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "oceans_role",
            query = "How do oceans affect Earth's climate system?",
            minRagChars = 40,
            minChunkCount = 1,
            retrievedMustContain = listOf("ocean exerts"),
            retrievedMustNotContain = listOf("Venus"),
            forbidMetaPath = true,
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "evidence_document_cue",
            track = Phase0ReplayTrack.BASELINE,
            query = "What evidence does the document present for changes in Earth's climate?",
            minRagChars = 40,
            minChunkCount = 1,
            retrievedMustContain = listOf("Human activities"),
            expectCite = true,
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "earth_change_factors",
            query = "What are the major factors that can cause Earth's climate to change?",
            minRagChars = 40,
            minChunkCount = 1,
            retrievedMustContain = listOf("Sun's energy"),
            retrievedMustNotContain = listOf("Venus lacks"),
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "guide_activities",
            query = "What activities or experiments does the guide recommend for understanding Earth's climate system?",
            minRagChars = 40,
            minChunkCount = 1,
            retrievedMustContain = listOf("classroom activities", "instruments"),
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "climate_change_effects",
            query = "According to the document, what are some potential effects of climate change on Earth?",
            minRagChars = 40,
            minChunkCount = 1,
            retrievedMustContain = listOf("Climate change"),
            expectCite = true,
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "greenhouse_gases",
            track = Phase0ReplayTrack.BASELINE,
            query = "What does the document explain about greenhouse gases?",
            minRagChars = 40,
            minChunkCount = 1,
            retrievedMustContain = listOf("carbon dioxide", "methane"),
            expectCite = true,
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "carbon_cycle",
            query = "How does the carbon cycle relate to Earth's climate?",
            minRagChars = 40,
            minChunkCount = 1,
            retrievedMustContain = listOf("carbon cycle"),
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "climate_models",
            query = "What does the document say about climate models and how they are used?",
            minRagChars = 40,
            minChunkCount = 1,
            retrievedMustContain = listOf("mathematical models"),
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "natural_vs_human",
            query = "What are some differences between natural and human influences on climate described in the document?",
            minRagChars = 40,
            minChunkCount = 1,
            retrievedMustContain = listOf("Natural influences", "Human influences"),
            forbidMetaPath = true,
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "feedback_examples",
            query = "Find the section that discusses feedback mechanisms. What examples are given?",
            minRagChars = 40,
            minChunkCount = 1,
            retrievedMustContain = listOf("Feedback mechanisms", "ice-albedo"),
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "observations_measurements",
            query = "What specific observations or measurements are used to study Earth's climate?",
            minRagChars = 40,
            minChunkCount = 1,
            retrievedMustContain = listOf("tree rings", "ice cores"),
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "ice_sea_level",
            track = Phase0ReplayTrack.BASELINE,
            query = "What does the document say about changes in ice, glaciers, or sea level?",
            minRagChars = 40,
            minChunkCount = 1,
            retrievedMustContain = listOf("sea level rise"),
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "key_conclusions",
            query = "What key conclusions does the document present about Earth's changing climate",
            forbidMetaPath = true,
            minRagChars = 40,
            minChunkCount = 1,
            retrievedMustContain = listOf("Human activities"),
            expectCite = true,
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "topics_not_discussed",
            query = "Which topics related to climate change are not discussed in the document?",
            forbidMetaPath = true,
            minRagChars = 40,
            minChunkCount = 1,
            expectCite = true,
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "earth_system_interactions",
            query = "Summarize the document's explanation of how different parts of the Earth system interact to influence climate.",
            forbidMetaPath = true,
            minRagChars = 40,
            minChunkCount = 1,
            retrievedMustContain = listOf("atmosphere", "hydrosphere"),
            expectCitePolicyA = true,
        ),
        Phase0ReplayCase(
            id = "legal_dpdpa_penalties",
            track = Phase0ReplayTrack.BASELINE,
            query = "What are the monetary penalties and amounts in the schedule",
            docs = listOf(act),
            minRagChars = 60,
            minChunkCount = 1,
            retrievedMustContain = listOf("THE SCHEDULE"),
            expectCite = true,
            expectStrongMatch = true,
            expectCitePolicyA = true,
        ),
    )
}

internal fun runPhase0ReplayCase(case: Phase0ReplayCase): Phase0ReplayMetrics {
    val prompt = runGoldenTurn(
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
    val (path, metaReason) = inferPhase0SearchPath(
        query = case.query,
        retrieved = prompt.retrieved,
        isFollowUp = case.priorQuery != null,
    )
    val synthetic = syntheticAnswerFromRetrieval(prompt.retrieved)
    val citation = probePhase0Citation(
        metrics = prompt,
        query = case.query,
        attachmentsThisTurn = case.attachmentsThisTurn,
        syntheticAnswerBody = synthetic,
    )
    return Phase0ReplayMetrics(
        prompt = prompt,
        searchPath = path,
        metaReason = metaReason,
        citation = citation,
    )
}

internal fun assertPhase0ReplayBaseline(case: Phase0ReplayCase, metrics: Phase0ReplayMetrics) {
    check(case.track == Phase0ReplayTrack.BASELINE) {
        "assertPhase0ReplayBaseline called for non-baseline case=${case.id}"
    }
    val prompt = metrics.prompt
    check(prompt.turnMode == case.expectedMode) {
        "case=${case.id} mode=${prompt.turnMode} expected=${case.expectedMode}"
    }
    case.expectedScopeLabel?.let { expected ->
        check(prompt.retrievalScopeLabel == expected) {
            "case=${case.id} scope=${prompt.retrievalScopeLabel} expected=$expected"
        }
    }
    if (case.forbidMetaPath) {
        check(metrics.searchPath != RagSearchPath.meta) {
            "case=${case.id} path=${metrics.searchPath} expected not meta"
        }
    }
    case.expectedMetaReason?.let { expected ->
        check(metrics.metaReason == expected) {
            "case=${case.id} meta=${metrics.metaReason} expected=$expected"
        }
    }
    if (case.minRagChars > 0) {
        check(prompt.ragChars >= case.minRagChars) {
            "case=${case.id} ragChars=${prompt.ragChars} min=${case.minRagChars}"
        }
    }
    if (case.minChunkCount > 0) {
        check(prompt.chunkCount >= case.minChunkCount) {
            "case=${case.id} chunks=${prompt.chunkCount} min=${case.minChunkCount}"
        }
    }
    val joined = prompt.retrieved.joinToString("\n") { it.text }
    for (snippet in case.retrievedMustContain) {
        check(joined.contains(snippet, ignoreCase = true)) {
            "case=${case.id} missing snippet='$snippet'"
        }
    }
    for (snippet in case.retrievedMustNotContain) {
        check(!joined.contains(snippet, ignoreCase = true)) {
            "case=${case.id} forbidden snippet='$snippet' present in retrieval"
        }
    }
    case.expectCite?.let { expected ->
        check(prompt.shouldCite == expected) {
            "case=${case.id} shouldCite=${prompt.shouldCite} expected=$expected"
        }
    }
    case.expectStrongMatch?.let { expected ->
        check(prompt.strongMatch == expected) {
            "case=${case.id} strongMatch=${prompt.strongMatch} expected=$expected"
        }
    }
}

/** Records Policy A citation gaps without failing CI. */
internal fun phase0CitationPolicyAGaps(metrics: Phase0ReplayMetrics, case: Phase0ReplayCase): List<String> {
    if (!case.expectCitePolicyA) return emptyList()
    if (case.expectedMode != RagTurnMode.DOCUMENT_GROUNDED) return emptyList()
    if (metrics.prompt.chunkCount <= 0) return emptyList()
    val gaps = mutableListOf<String>()
    if (!metrics.prompt.shouldCite) gaps += "shouldCite=false"
    if (metrics.prompt.shouldCite && !metrics.citation.footerPresent) {
        if (metrics.citation.overlapDrop) gaps += "overlap-drop"
        else gaps += "footer-missing"
    }
    return gaps
}
