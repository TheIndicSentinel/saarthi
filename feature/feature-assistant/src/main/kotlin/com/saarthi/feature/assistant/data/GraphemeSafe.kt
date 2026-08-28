package com.saarthi.feature.assistant.data

/**
 * String.take() truncates at a UTF-16 code-unit boundary, which splits
 * mid-character on scripts that use combining marks (Devanagari, Tamil,
 * Telugu, Bengali, …). For session titles built from "नमस्ते आज क्या करूँ?"
 * that meant the chip subtitle could end with an orphan virama or vowel
 * mark — rendered as a tofu box on most fonts.
 *
 * BreakIterator walks Unicode grapheme cluster boundaries, so we cut at
 * the last *complete* cluster that fits in [n] code units.
 */
internal fun graphemeSafeTake(s: String, n: Int): String {
    if (s.length <= n) return s
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(s)
    var last = 0
    var cur = it.next()
    while (cur != java.text.BreakIterator.DONE && cur <= n) {
        last = cur
        cur = it.next()
    }
    return s.substring(0, last)
}
