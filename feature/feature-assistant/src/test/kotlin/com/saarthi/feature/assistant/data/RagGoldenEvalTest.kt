package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RagGoldenEvalTest {

    private fun topUri(query: String, docs: List<GoldenDoc> = GoldenFixtures.englishPair): String =
        retrieveGolden(query, docs).first().docUri

    @Test
    fun `english penalty question hits the NDA not the statement`() {
        assertEquals(GoldenFixtures.NDA_URI, topUri("what is the penalty"))
        assertTrue(retrieveGolden("what is the penalty", GoldenFixtures.englishPair).first().score > 0)
    }

    @Test
    fun `english salary question hits the statement`() {
        assertEquals(GoldenFixtures.STMT_URI, topUri("what is the salary credit"))
    }

    @Test
    fun `hindi and hinglish penalty questions hit the english NDA`() {
        assertEquals(GoldenFixtures.NDA_URI, topUri("इसमें जुर्माना क्या है"))
        assertEquals(GoldenFixtures.NDA_URI, topUri("is agreement me jurmana kitna hai"))
    }

    @Test
    fun `tamil telugu bengali kannada queries still hit the english NDA`() {
        assertEquals(GoldenFixtures.NDA_URI, topUri("ஒப்பந்தத்தில் அபராதம் என்ன"))
        assertEquals(GoldenFixtures.NDA_URI, topUri("ఒప్పందంలో జరిమానా ఎంత"))
        assertEquals(GoldenFixtures.NDA_URI, topUri("চুক্তিতে জরিমানা কত"))
        assertEquals(GoldenFixtures.NDA_URI, topUri("ಒಪ್ಪಂದದಲ್ಲಿ ದಂಡ ಎಷ್ಟು"))
    }

    @Test
    fun `gujarati punjabi odia marathi queries expand onto the english NDA`() {
        assertEquals(GoldenFixtures.NDA_URI, topUri("કરારમાં દંડ કેટલો છે"))
        assertEquals(GoldenFixtures.NDA_URI, topUri("ਇਕਰਾਰਨਾਮੇ ਵਿੱਚ ਜੁਰਮਾਨਾ ਕਿੰਨਾ ਹੈ"))
        assertEquals(GoldenFixtures.NDA_URI, topUri("ଚୁକ୍ତିରେ ଜରିମାନା କେତେ"))
        assertEquals(GoldenFixtures.NDA_URI, topUri("या करारात दंड किती आहे"))
    }

    @Test
    fun `same-script hindi circular beats a hindi leave note`() {
        val docs = listOf(GoldenFixtures.hindiCircular, GoldenFixtures.hindiLeave)
        assertEquals(GoldenFixtures.HINDI_URI, topUri("जुर्माना कितना है", docs))
    }

    @Test
    fun `same-script tamil salary notice beats a tamil holiday note`() {
        val docs = listOf(GoldenFixtures.tamilNotice, GoldenFixtures.tamilHoliday)
        assertEquals(GoldenFixtures.TAMIL_URI, topUri("இந்த மாத சம்பளம் எப்போது", docs))
    }

    @Test
    fun `unreadable scan is never retrieved`() {
        val docs = GoldenFixtures.englishPair + GoldenFixtures.unreadableScan
        val hits = retrieveGolden("what is the salary credit", docs)
        assertTrue(hits.none { it.docUri == GoldenFixtures.SCAN_URI })
        assertTrue(extractionFailureMessage(GoldenFixtures.unreadableScan.text) != null)
    }

    @Test
    fun `compare query sets equal slots on two english files`() {
        val docs = GoldenFixtures.englishPair.map { it.uri to it.name }
        val route = routeQuery("compare both files", docs)
        assertTrue(route.equalSlots)
        assertFalse(routeQuery("what is the term", docs).equalSlots)
    }

    @Test
    fun `every offered UI language has a native-script or english query fixture`() {
        // English + 10 Indian languages. Native-script rows use a real
        // query; ENGLISH uses the penalty question. This is the coverage
        // lock so a new SupportedLanguage cannot ship without a golden.
        val covered = setOf(
            SupportedLanguage.ENGLISH,
            SupportedLanguage.HINDI,
            SupportedLanguage.TAMIL,
            SupportedLanguage.TELUGU,
            SupportedLanguage.BENGALI,
            SupportedLanguage.MARATHI,
            SupportedLanguage.KANNADA,
            SupportedLanguage.GUJARATI,
            SupportedLanguage.PUNJABI,
            SupportedLanguage.ODIA,
        )
        assertEquals(SupportedLanguage.entries.toSet(), covered)
    }
}
