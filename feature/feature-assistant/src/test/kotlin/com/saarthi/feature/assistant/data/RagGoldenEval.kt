package com.saarthi.feature.assistant.data

import com.saarthi.core.rag.Bm25Retriever

/**
 * Slice 4.7 — retrieval golden harness (test-only, no runtime callers).
 * BM25 + query routing over in-memory fixtures; no Room and no LLM.
 */
internal data class GoldenDoc(
    val uri: String,
    val name: String,
    val text: String,
)

internal data class GoldenHit(
    val docUri: String,
    val score: Double,
    val chunkIndex: Int,
)

internal fun retrieveGolden(
    query: String,
    docs: List<GoldenDoc>,
    topK: Int = 8,
    chunkSize: Int = 600,
    overlap: Int = 80,
): List<GoldenHit> {
    val chunks = ArrayList<Triple<String, Int, String>>()
    for (doc in docs) {
        if (extractionFailureMessage(doc.text) != null) continue
        chunkDocumentText(doc.text, chunkSize, overlap).forEachIndexed { idx, piece ->
            chunks += Triple(doc.uri, idx, piece)
        }
    }
    if (chunks.isEmpty()) return emptyList()
    val route = routeQuery(query, docs.map { it.uri to it.name })
    return Bm25Retriever.rank(chunks.map { it.third }, route.expandedQuery, topK).map { scored ->
        val (uri, idx, _) = chunks[scored.index]
        GoldenHit(docUri = uri, score = scored.score, chunkIndex = idx)
    }
}

internal object GoldenFixtures {
    const val NDA_URI = "content://nda"
    const val STMT_URI = "content://stmt"
    const val HINDI_URI = "content://hindi-circular"
    const val TAMIL_URI = "content://tamil-notice"
    const val SCAN_URI = "content://scan"

    val nda = GoldenDoc(
        uri = NDA_URI,
        name = "Mallikarjuna Rao_NDA Agreement.pdf",
        text = """
            CONFIDENTIALITY AGREEMENT
            The receiving party shall not disclose confidential information.
            Term is 24 months from the effective date.
            Penalty for breach of this clause is Rs 5 lakh.
        """.trimIndent(),
    )

    val statement = GoldenDoc(
        uri = STMT_URI,
        name = "Account Statement.pdf",
        text = """
            --- Page 1 ---
            Account statement for January
            Date        Description           Amount
            12/01/2026  UPI grocery           1,250.00
            25/01/2026  Salary credit        50,000.00
        """.trimIndent(),
    )

    val hindiCircular = GoldenDoc(
        uri = HINDI_URI,
        name = "परिपत्र.pdf",
        text = """
            कार्यालय परिपत्र
            धारा 12 के अंतर्गत जुर्माना पाँच लाख रुपये तक हो सकता है।
            गोपनीय जानकारी का खुलासा निषिद्ध है।
        """.trimIndent(),
    )

    val tamilNotice = GoldenDoc(
        uri = TAMIL_URI,
        name = "அறிவிப்பு.pdf",
        text = """
            அலுவலக அறிவிப்பு
            இந்த மாத சம்பளம் 25 ஆம் தேதி வழங்கப்படும்.
            ஒப்பந்த மீறலுக்கு அபராதம் விதிக்கப்படும்.
        """.trimIndent(),
    )

    val hindiLeave = GoldenDoc(
        uri = "content://hindi-leave",
        name = "अवकाश.pdf",
        text = "कर्मचारी अवकाश नियम। वार्षिक छुट्टी बारह दिन की है। यात्रा भत्ता अलग से मिलेगा।",
    )

    val tamilHoliday = GoldenDoc(
        uri = "content://tamil-holiday",
        name = "விடுமுறை.pdf",
        text = "பள்ளி அறிவிப்பு. நாளை விடுமுறை. மாலை விழா நடைபெறும்.",
    )

    val unreadableScan = GoldenDoc(
        uri = SCAN_URI,
        name = "Scanned Statement.pdf",
        text = "[PDF: Scan had little readable text]",
    )

    val englishPair = listOf(nda, statement)
}
