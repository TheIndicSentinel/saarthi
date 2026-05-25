package com.saarthi.core.i18n

/**
 * Knowledge packs are pre-curated content bundles that ship for specific
 * Personality Pal modes (Kisan, Money Mentor, Knowledge Expert, etc.).
 *
 * Each pack's chunks live under a RESERVED global session id inside
 * the regular `rag_chunks` Room table — when the active persona matches
 * a pack, `RagDocumentRepository.search` merges the pack's chunks into
 * the current session's corpus before BM25 ranking. This reuses the
 * full RAG plumbing (chunking, BM25 scoring, neighbor expansion,
 * structural sampling, page-aware citations) without a schema change.
 *
 * The sessionId namespace is `global_pack_*` so it can't collide with
 * user chat sessionIds (which are UUIDs or the literal "default").
 *
 * Adding a new pack: add the enum entry, ship a seed JSON to
 * `app/src/main/assets/packs/`, point `KisanPackInstaller` (or its
 * future sibling installers) at it.
 */
enum class PackId(
    val sessionId: String,
    val personaId: String,
    val displayName: String,
) {
    KISAN(
        sessionId = "global_pack_kisan",
        personaId = "kisan",
        displayName = "Kisan Knowledge",
    );
    // MONEY / KNOWLEDGE / FIELD_EXPERT slots can be added later — the
    // RAG search merge already handles "any number of pack sessions".

    companion object {
        /**
         * Look up the pack (if any) attached to a persona. Returns null
         * for personas that don't have a packaged knowledge bundle.
         */
        fun forPersona(personaId: String): PackId? =
            entries.firstOrNull { it.personaId.equals(personaId, ignoreCase = true) }
    }
}
