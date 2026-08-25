package com.saarthi.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R5 — regression corpus for [Bm25Retriever.stemQueryToken] and end-to-end
 * BM25 recall on inflected query forms. Each supported script gets:
 *  • a direct stem assertion (positive)
 *  • a "must not over-strip" assertion (negative)
 *  • a document-style rank assertion where practical
 *
 * Not a substitute for ongoing native-speaker review on new suffixes, but
 * locks in the validated tables so drift is caught in CI.
 */
class IndicStemValidationTest {

    // ── Direct stem assertions (positive) ────────────────────────────────────

    @Test fun devanagari_masculine_plural_oblique() =
        assertEquals("किसान", Bm25Retriever.stemQueryToken("किसानों"))

    @Test fun devanagari_feminine_plural_oblique() =
        assertEquals("योजना", Bm25Retriever.stemQueryToken("योजनाओं"))

    @Test fun devanagari_feminine_plural() =
        assertEquals("किताब", Bm25Retriever.stemQueryToken("किताबें"))

    @Test fun bengali_animate_plural_genitive() =
        assertEquals("কৃষক", Bm25Retriever.stemQueryToken("কৃষকদের"))

    @Test fun bengali_simple_plural() =
        assertEquals("কৃষক", Bm25Retriever.stemQueryToken("কৃষকরা"))

    @Test fun gujarati_locative_plural() =
        assertEquals("ખેતર", Bm25Retriever.stemQueryToken("ખેતરોમાં"))

    @Test fun punjabi_oblique_plural() =
        assertEquals("ਕਿਸਾਨ", Bm25Retriever.stemQueryToken("ਕਿਸਾਨਾਂ"))

    @Test fun odia_plural_genitive() =
        assertEquals("କୃଷକ", Bm25Retriever.stemQueryToken("କୃଷକମାନଙ୍କ"))

    @Test fun tamil_dative() =
        assertEquals("விவசாயி", Bm25Retriever.stemQueryToken("விவசாயிக்கு"))

    @Test fun telugu_dative() =
        assertEquals("రైతు", Bm25Retriever.stemQueryToken("రైతుకి"))

    @Test fun telugu_accusative() =
        assertEquals("రైతు", Bm25Retriever.stemQueryToken("రైతును"))

    @Test fun kannada_dative() =
        assertEquals("ರೈತ", Bm25Retriever.stemQueryToken("ರೈತಗೆ"))

    // ── Must not over-strip (negative) ─────────────────────────────────────────

    @Test fun devanagari_base_noun_unchanged() =
        assertEquals("सहमति", Bm25Retriever.stemQueryToken("सहमति"))

    @Test fun bengali_base_noun_unchanged() =
        assertEquals("আবহাওয়া", Bm25Retriever.stemQueryToken("আবহাওয়া"))

    @Test fun tamil_base_noun_unchanged() =
        assertEquals("வானிலை", Bm25Retriever.stemQueryToken("வானிலை"))

    @Test fun telugu_base_noun_unchanged() =
        assertEquals("వాతావరణం", Bm25Retriever.stemQueryToken("వాతావరణం"))

    @Test fun kannada_base_noun_unchanged() =
        assertEquals("ಹವಾಮಾನ", Bm25Retriever.stemQueryToken("ಹವಾಮಾನ"))

    @Test fun english_plural_still_stems() =
        assertEquals("penalty", Bm25Retriever.stemQueryToken("penalties"))

    @Test fun short_tokens_not_stripped() =
        assertEquals("MSP", Bm25Retriever.stemQueryToken("MSP"))

    // ── Document-style BM25 recall ─────────────────────────────────────────────

    @Test
    fun `inflected Hindi scheme query finds base noun in policy chunk`() {
        val corpus = listOf(
            "आज का मौसम शुष्क रहेगा।",
            "PM-Kisan योजना के लिए पात्रता की शर्तें सरकार ने निर्धारित की हैं।",
        )
        val ranked = Bm25Retriever.rank(corpus, "योजनाओं", topK = 2)
        assertEquals(1, ranked.first().index)
    }

    @Test
    fun `inflected Hindi farmer query finds base noun in farming chunk`() {
        val corpus = listOf(
            "बाजार में आज गेहूं की कीमत बढ़ी।",
            "छोटे किसान को फसल बीमा योजना का लाभ मिल सकता है।",
        )
        val ranked = Bm25Retriever.rank(corpus, "किसानों", topK = 2)
        assertEquals(1, ranked.first().index)
    }

    @Test
    fun `Bengali plural query finds singular farmer in corpus`() {
        val corpus = listOf(
            "আবহাওয়া আজ ভালো।",
            "ক্ষুদ্র কৃষক crop insurance scheme এর জন্য eligible হতে পারেন।",
        )
        val ranked = Bm25Retriever.rank(corpus, "কৃষকরা", topK = 2)
        assertEquals(1, ranked.first().index)
    }

    @Test
    fun `Telugu accusative query finds nominative farmer in corpus`() {
        val corpus = listOf(
            "ఈ రోజు వర్షం ఉంది.",
            "రైతు PM-Kisan కింద ₹6000 సహాయం పొందవచ్చు.",
        )
        val ranked = Bm25Retriever.rank(corpus, "రైతును", topK = 2)
        assertEquals(1, ranked.first().index)
    }

    @Test
    fun `unrelated inflected query does not spuriously match unrelated chunk`() {
        val corpus = listOf(
            "crop calendar for wheat sowing windows",
            "penalty for data breach under the act",
        )
        // Stem adds extra candidates but must not force a match when IDF says no.
        val ranked = Bm25Retriever.rank(corpus, "किसानों", topK = 2)
        assertTrue("Unrelated Latin corpus must not rank on Indic stem alone", ranked.isEmpty())
    }

    @Test
    fun `feminine oblique stem differs from broken three-char chop`() {
        val stem = Bm25Retriever.stemQueryToken("भाषाओं")
        assertEquals("भाषा", stem)
        assertNotEquals("भाष", stem)
    }
}
