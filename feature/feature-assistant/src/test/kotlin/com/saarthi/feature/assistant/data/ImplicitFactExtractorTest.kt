package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First real test coverage for the ~450-line multilingual implicit-fact
 * extraction bank since it was written — this class's own history is a
 * list of field bugs in exactly this logic (Devanagari combining marks
 * breaking name capture, a zodiac sign mislabeled as a name, profession
 * captures swallowing trailing clauses). Not exhaustive over every one of
 * the ~50 individual regex patterns — focused on the specific cases this
 * project's own commit history shows actually broke in the field, plus one
 * representative case per major language/fact-type so a wholesale
 * regression (e.g. an accidental delete of a language block) is caught.
 */
class ImplicitFactExtractorTest {

    private val extractor = ImplicitFactExtractor()

    // ── isPlausibleNameValue guards ─────────────────────────────────────────

    @Test
    fun `a short clean name is plausible`() {
        assertTrue(extractor.isPlausibleNameValue("Arjun"))
    }

    @Test
    fun `a Devanagari name with combining marks is plausible`() {
        // अर्जुन = अ + र + ् (virama, a combining mark) + ज + ु (vowel sign) + न
        assertTrue(extractor.isPlausibleNameValue("अर्जुन"))
    }

    @Test
    fun `a zodiac sign is never a plausible name value`() {
        // The exact field bug: a model mislabeled a zodiac fact as "name",
        // and being single-token/punctuation-free/longer than the real
        // stored name let it win the completeness guard in persistMemoryFact.
        assertFalse(extractor.isPlausibleNameValue("Sagittarius"))
        assertFalse(extractor.isPlausibleNameValue("sagittarius"))
    }

    @Test
    fun `a sentence-shaped value is not plausible`() {
        assertFalse(extractor.isPlausibleNameValue("उपयोगकर्ता का नाम अर्जुन है"))
    }

    @Test
    fun `a value with more than 3 tokens is not plausible`() {
        assertFalse(extractor.isPlausibleNameValue("this is way too many words"))
    }

    @Test
    fun `a value containing digits is not plausible`() {
        assertFalse(extractor.isPlausibleNameValue("Arjun123"))
    }

    @Test
    fun `a value containing sentence punctuation is not plausible`() {
        assertFalse(extractor.isPlausibleNameValue("Arjun."))
        assertFalse(extractor.isPlausibleNameValue("Arjun, hi"))
    }

    @Test
    fun `a bare filler word is not plausible`() {
        assertFalse(extractor.isPlausibleNameValue("mera"))
        assertFalse(extractor.isPlausibleNameValue("नाम"))
    }

    // ── English ──────────────────────────────────────────────────────────────

    @Test
    fun `English my name is`() {
        val facts = extractor.extractImplicitFacts("Hi, my name is Arjun")
        assertEquals("Arjun", facts.toMap()["name"])
    }

    @Test
    fun `English profession cuts at a trailing conjunction`() {
        // Field bug: "I am a vegetarian and Sagittarius" stored profession as
        // the truncated garbage "vegetarian and Sagittari" before this cut.
        val facts = extractor.extractImplicitFacts("I am a teacher and I love my job")
        assertEquals("teacher", facts.toMap()["profession"])
    }

    @Test
    fun `English diet statement inside a multi-clause message is captured`() {
        // Field bug: find() (not findAll()) stopped at the first first-person
        // clause ("Arjun", not a diet term) and never saw "vegetarian".
        val facts = extractor.extractImplicitFacts("I'm Arjun and I'm vegetarian")
        assertEquals("Arjun", facts.toMap()["name"])
        assertEquals("vegetarian", facts.toMap()["diet"])
    }

    @Test
    fun `English state-of-being is never captured as a name`() {
        assertTrue(extractor.extractImplicitFacts("I'm tired").none { it.first == "name" })
        assertTrue(extractor.extractImplicitFacts("I'm busy right now").none { it.first == "name" })
    }

    @Test
    fun `English whole-message lowercase self-intro is captured`() {
        // Phone keyboards auto-capitalise only the first word, so this
        // lowercase shape is how a self-intro actually arrives in the field.
        val facts = extractor.extractImplicitFacts("i'm arjun")
        assertEquals("Arjun", facts.toMap()["name"])
    }

    @Test
    fun `English age`() {
        val facts = extractor.extractImplicitFacts("I am 28 years old")
        assertEquals("28", facts.toMap()["age"])
    }

    @Test
    fun `English likes and dislikes`() {
        val likes = extractor.extractImplicitFacts("I really like cricket")
        assertEquals("cricket", likes.toMap()["likes"])
        val dislikes = extractor.extractImplicitFacts("I hate spam calls")
        assertEquals("spam calls", dislikes.toMap()["dislikes"])
    }

    @Test
    fun `English employer`() {
        val facts = extractor.extractImplicitFacts("I work at Infosys")
        assertEquals("Infosys", facts.toMap()["employer"])
    }

    @Test
    fun `an overly long message is not scanned at all`() {
        assertTrue(extractor.extractImplicitFacts("my name is Arjun " + "x".repeat(500)).isEmpty())
    }

    // ── Hindi / Devanagari ───────────────────────────────────────────────────

    @Test
    fun `Hindi name`() {
        val facts = extractor.extractImplicitFacts("मेरा नाम अर्जुन है")
        assertEquals("अर्जुन", facts.toMap()["name"])
    }

    @Test
    fun `Hindi city`() {
        val facts = extractor.extractImplicitFacts("मैं पुणे से हूँ")
        assertEquals("पुणे", facts.toMap()["city"])
    }

    @Test
    fun `Hindi age`() {
        val facts = extractor.extractImplicitFacts("मैं 28 साल का हूँ")
        assertEquals("28", facts.toMap()["age"])
    }

    @Test
    fun `Hindi whole-message self-intro does not capture a state-of-being word`() {
        // NATIVE_STATE_WORDS guard — "मैं ठीक" (I'm fine) must never be stored
        // as a name via the bare-pronoun self-intro pattern.
        assertTrue(extractor.extractImplicitFacts("मैं ठीक").none { it.first == "name" })
    }

    @Test
    fun `Hindi devanagari diet requires a first-person pronoun nearby`() {
        assertTrue(extractor.extractImplicitFacts("मैं शाकाहारी हूँ").any { it == ("diet" to "vegetarian") })
        // "शाकाहारी होटल" (veg restaurant/hotel) has no first-person pronoun —
        // must not be captured as the user's own diet.
        assertTrue(extractor.extractImplicitFacts("शाकाहारी होटल कहाँ है").none { it.first == "diet" })
    }

    // ── Marathi / Devanagari ─────────────────────────────────────────────────

    @Test
    fun `Marathi name`() {
        val facts = extractor.extractImplicitFacts("माझे नाव अर्जुन आहे")
        assertEquals("अर्जुन", facts.toMap()["name"])
    }

    // ── Romanised (Latin-script) Indian languages ───────────────────────────

    @Test
    fun `romanised Hindi name with copula`() {
        val facts = extractor.extractImplicitFacts("mera naam Arjun hai")
        assertEquals("Arjun", facts.toMap()["name"])
    }

    @Test
    fun `romanised Marathi name`() {
        val facts = extractor.extractImplicitFacts("majhe naav Arjun aahe")
        assertEquals("Arjun", facts.toMap()["name"])
    }

    // ── Other Indian scripts (one representative case each) ─────────────────

    @Test
    fun `Telugu name`() {
        val facts = extractor.extractImplicitFacts("నా పేరు అర్జున్")
        assertEquals("అర్జున్", facts.toMap()["name"])
    }

    @Test
    fun `Tamil name`() {
        val facts = extractor.extractImplicitFacts("என் பெயர் அர்ஜுன்")
        assertEquals("அர்ஜுன்", facts.toMap()["name"])
    }

    @Test
    fun `Bengali name`() {
        val facts = extractor.extractImplicitFacts("আমার নাম অর্জুন")
        assertEquals("অর্জুন", facts.toMap()["name"])
    }

    @Test
    fun `Kannada name`() {
        val facts = extractor.extractImplicitFacts("ನನ್ನ ಹೆಸರು ಅರ್ಜುನ್")
        assertEquals("ಅರ್ಜುನ್", facts.toMap()["name"])
    }

    @Test
    fun `Gujarati name`() {
        val facts = extractor.extractImplicitFacts("મારું નામ અર્જુન")
        assertEquals("અર્જુન", facts.toMap()["name"])
    }

    @Test
    fun `Punjabi name`() {
        val facts = extractor.extractImplicitFacts("ਮੇਰਾ ਨਾਮ ਅਰਜੁਨ")
        assertEquals("ਅਰਜੁਨ", facts.toMap()["name"])
    }

    @Test
    fun `Malayalam name`() {
        val facts = extractor.extractImplicitFacts("എന്റെ പേര് അർജുൻ")
        assertEquals("അർജുൻ", facts.toMap()["name"])
    }

    @Test
    fun `Odia name`() {
        val facts = extractor.extractImplicitFacts("ମୋ ନାମ ଅର୍ଜୁନ")
        assertEquals("ଅର୍ଜୁନ", facts.toMap()["name"])
    }

    // ── Priority: an earlier-matched fact type is not overwritten ───────────

    @Test
    fun `once a name is captured, later weaker patterns do not overwrite it`() {
        // "मेरा नाम अर्जुन है" matches the high-precision Hindi pattern first;
        // the generic whole-message fallback later in the function must skip
        // (out.none { it.first == "name" } guards) rather than re-add/overwrite.
        val facts = extractor.extractImplicitFacts("मेरा नाम अर्जुन है").filter { it.first == "name" }
        assertEquals(1, facts.size)
        assertEquals("अर्जुन", facts.first().second)
    }

    @Test
    fun `a message with no extractable facts returns an empty list`() {
        assertTrue(extractor.extractImplicitFacts("What is the capital of France?").isEmpty())
    }
}
