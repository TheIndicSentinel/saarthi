package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphemeSafeTakeTest {

    @Test
    fun `ASCII shorter than n is unchanged`() {
        assertEquals("hello", graphemeSafeTake("hello", 40))
        assertEquals("hi", graphemeSafeTake("hi", 2))
    }

    @Test
    fun `ASCII longer than n is a prefix of length n`() {
        // For ASCII, each grapheme cluster is one code unit, so the cut
        // matches String.take(n).
        assertEquals("hello", graphemeSafeTake("hello world", 5))
        assertEquals(5, graphemeSafeTake("hello world", 5).length)
        assertEquals("hello world".take(5), graphemeSafeTake("hello world", 5))
    }

    @Test
    fun `Hindi KDoc example does not leave a dangling virama from a cut cluster`() {
        val s = "नमस्ते आज क्या करूँ?"
        // "स्ते" begins with the cluster "स्" = स (U+0938) + virama (U+094D)
        // at UTF-16 indices [2, 4). n=3 sits inside that cluster:
        //   String.take(3) = "नमस" — splits the cluster (virama dropped, or
        //   on conjunct-aware ICU, a mid-conjunct cut that can leave an
        //   orphan virama at the end for nearby n).
        // graphemeSafeTake stops at the last complete cluster: "नम".
        val n = 3
        val naive = s.take(n)
        val safe = graphemeSafeTake(s, n)

        assertEquals("नमस", naive)
        assertNotEquals(
            "String.take must split a combining-mark cluster at this n",
            naive,
            safe,
        )
        assertEquals("नम", safe)
        assertFalse(
            "must not end with a dangling virama leftover of a cut cluster",
            safe.endsWith("\u094D"),
        )
        assertTrue(safe.length <= n)
    }

    @Test
    fun `Tamil combining-mark snippet length in code units is at most n`() {
        // "மி" is ம + ி (vowel sign); "ழ்" is ழ + ் (pulli). n=4 sits
        // inside the second combining cluster of "தமிழ்".
        val s = "தமிழ்"
        val n = 4
        val safe = graphemeSafeTake(s, n)
        assertTrue(safe.length <= n)
        assertNotEquals(s.take(n), safe)
        assertEquals("தமி", safe)
    }

    @Test
    fun `n of zero or empty string yields empty`() {
        assertEquals("", graphemeSafeTake("", 0))
        assertEquals("", graphemeSafeTake("", 10))
        assertEquals("", graphemeSafeTake("नमस्ते", 0))
        assertEquals("", graphemeSafeTake("hello", 0))
    }
}
