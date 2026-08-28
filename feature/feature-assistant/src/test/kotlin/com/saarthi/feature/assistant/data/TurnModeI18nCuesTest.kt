package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.SupportedLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 5.4 — i18n doc-scope and opt-out turn-mode cues across UI languages. */
class TurnModeI18nCuesTest {

    private val sessionDocs = 1

      @Test
  fun `native doc scope cues ground retrieval`() {
    val samples = listOf(
      SupportedLanguage.ENGLISH to "What does it say in this document about consent",
      SupportedLanguage.HINDI to "इस दस्तावेज में सहमति के बारे में क्या लिखा है",
      SupportedLanguage.MARATHI to "या दस्तावेजात संमती काय आहे",
      SupportedLanguage.TAMIL to "இந்த ஆவணத்தில் என்ன சொல்கிறது",
      SupportedLanguage.TELUGU to "ఈ పత్రంలో ఏమి చెప్పింది",
      SupportedLanguage.BENGALI to "এই নথিতে কী লেখা আছে",
      SupportedLanguage.KANNADA to "ಈ ದಾಖಲೆಯಲ್ಲಿ ಏನು ಹೇಳಿದೆ",
      SupportedLanguage.GUJARATI to "આ દસ્તાવેજમાં શું લખ્યું છે",
      SupportedLanguage.PUNJABI to "ਇਸ ਦਸਤਾਵੇਜ਼ ਵਿੱਚ ਕੀ ਲਿਖਿਆ ਹੈ",
      SupportedLanguage.ODIA to "ଏହି ଦଲିଲରେ କଣ ଲେଖାଯାଇଛି",
    )
    for ((_, query) in samples) {
      assertTrue("scope: $query", hasDocumentQueryCues(query))
      assertEquals(
        RagTurnMode.DOCUMENT_GROUNDED,
        classifyRagTurnMode(
          query = query,
          sessionDocCount = sessionDocs,
          attachmentsThisTurn = false,
        ),
      )
    }
  }

    @Test
  fun `native opt-out cues skip document retrieval`() {
    val gkTail = "explain photosynthesis to a school kid"
    val samples = listOf(
      SupportedLanguage.ENGLISH to "don't consider the document and $gkTail",
      SupportedLanguage.HINDI to "दस्तावेज मत $gkTail",
      SupportedLanguage.MARATHI to "दस्तऐवज नको $gkTail",
      SupportedLanguage.TAMIL to "ஆவணம் வேண்டாம் $gkTail",
      SupportedLanguage.TELUGU to "పత్రం వద్దు $gkTail",
      SupportedLanguage.BENGALI to "নথি না $gkTail",
      SupportedLanguage.KANNADA to "ದಾಖಲೆ ಬಿಟ್ಟು $gkTail",
      SupportedLanguage.GUJARATI to "દસ્તાવેજ નહીં $gkTail",
      SupportedLanguage.PUNJABI to "ਫਾਈਲ ਨਹੀਂ $gkTail",
      SupportedLanguage.ODIA to "ଦଲିଲ ନାହିଁ $gkTail",
    )
    for ((_, query) in samples) {
      assertTrue("opt-out: $query", isDocumentOptOutQuery(query))
      assertEquals(
        RagTurnMode.GENERAL_KNOWLEDGE,
        classifyRagTurnMode(
          query = query,
          sessionDocCount = sessionDocs,
          attachmentsThisTurn = false,
        ),
      )
    }
  }

    @Test
  fun `indic topical leads classify without english document words`() {
    val samples = listOf(
      "என்ன தண்டனை" to SupportedLanguage.TAMIL,
      "ఏమి జరిమానా" to SupportedLanguage.TELUGU,
      "কী জরিমানা" to SupportedLanguage.BENGALI,
      "काय शिक्षा" to SupportedLanguage.MARATHI,
      "ಏನು ದಂಡ" to SupportedLanguage.KANNADA,
      "શું દંડ" to SupportedLanguage.GUJARATI,
      "ਕੀ ਜੁਰਮਾਨਾ" to SupportedLanguage.PUNJABI,
      "କଣ ଜରିମାନା" to SupportedLanguage.ODIA,
    )
    for ((query, lang) in samples) {
      assertTrue("$lang topical: $query", isIndexedSessionTopicalQuestion(query))
      assertEquals(
        RagTurnMode.DOCUMENT_GROUNDED,
        classifyRagTurnMode(
          query = query,
          sessionDocCount = sessionDocs,
          attachmentsThisTurn = false,
        ),
      )
    }
  }

    @Test
  fun `cue phrase helper respects script`() {
    assertTrue(hasI18nDocumentScopeCue("ఈ పత్రంలో ఏమి"))
    assertFalse(hasI18nDocumentScopeCue("random unrelated ask"))
    assertTrue(hasI18nDocumentOptOutCue("દસ્તાવેજ નહીં"))
    assertFalse(hasI18nDocumentOptOutCue("what are penalties in the act"))
  }
}
