package com.saarthi.feature.assistant.data

/**
 * A small, self-contained sample document used to demo the PDF/Document Q&A
 * feature in minute one — without the user having to find and attach a file of
 * their own. Tapping "Try the document assistant" on the empty chat screen
 * attaches this as a synthetic attachment and pre-fills a question, so a brand
 * new user immediately sees the "attach → ask → grounded answer" wow that is the
 * product's strongest hook.
 *
 * The text carries concrete, answerable facts (rights, consent rules, the
 * ₹250 crore penalty) so the model has real material to ground a good answer in.
 * Page markers mirror what the real PDF extractor emits so the RAG path treats
 * it identically to a genuine document.
 */
object DemoDocument {
    const val NAME = "Sample — Data Protection (DPDP) Act.pdf"
    const val MIME = "application/pdf"

    /** A demo-only synthetic URI; used as the RAG chunk key for this document. */
    const val URI = "saarthi://demo/dpdp-sample"

    val SUGGESTED_QUESTIONS = listOf(
        "What penalties can be imposed under this Act?",
        "What rights does a person have over their data?",
        "When is consent required?",
    )

    val TEXT = """
        --- Page 1 ---
        Digital Personal Data Protection (DPDP) Act — plain-language summary (sample document)

        Purpose. The Act governs how organisations ("Data Fiduciaries") collect, store and
        use the personal data of individuals ("Data Principals") in India, while still
        allowing lawful processing for legitimate purposes.

        Consent. A Data Fiduciary must obtain free, informed, specific and unambiguous
        consent before processing personal data, and must give a clear notice in plain
        language. Consent can be withdrawn at any time, and withdrawing it must be as easy
        as giving it.

        Rights of the individual. A Data Principal has the right to:
        - access a summary of the personal data being processed about them;
        - correction and erasure of their personal data;
        - nominate another person to exercise these rights in case of death or incapacity;
        - grievance redressal from the Data Fiduciary before approaching the Board.

        --- Page 2 ---
        Obligations. Data Fiduciaries must keep data accurate, protect it with reasonable
        security safeguards, delete it once the purpose is served, and report any personal
        data breach to the Data Protection Board and to the affected users.

        Penalties. The Act imposes substantial financial penalties for non-compliance.
        Failure to take reasonable security safeguards to prevent a data breach can attract
        a penalty of up to Rs 250 crore. Failure to notify a breach, or breaching the
        obligations around children's data, can attract penalties of up to Rs 200 crore.
        The Data Protection Board decides the amount based on the nature and gravity of the
        breach.

        Children. Processing a child's data needs verifiable parental consent, and tracking
        or targeted advertising directed at children is restricted.
    """.trimIndent()
}
