package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave 4 P19 — section-bound legal/gazette chunking. */
class LegalChunkingTest {

    private fun dpdpaStyleAct(): String = buildString {
        append("THE DIGITAL PERSONAL DATA PROTECTION ACT, 2023\n\n")
        repeat(3) { ch ->
            append("CHAPTER ${roman(ch)}\n")
            append("Sample chapter title $ch\n")
            append("Body text about chapter $ch. ".repeat(40))
            append("\n")
        }
        append("CHAPTER VIII\nPENALTIES AND ADJUDICATION\n")
        append("33. Penalties\n")
        append("The Board may impose penalties considering nature of breach and harm.\n")
        append("Adjudication shall follow the factors listed in this Chapter.\n")
        append("THE SCHEDULE\n")
        append((1..12).joinToString("\n") { i ->
            "Breach category $i — monetary penalty up to ₹${i * 25} crore"
        })
    }

    private fun roman(n: Int): String = when (n) {
        1 -> "I"
        2 -> "II"
        3 -> "III"
        else -> n.toString()
    }

    @Test
    fun `detects legal gazette style act`() {
        assertTrue(isLegalGazetteStyleDocument(dpdpaStyleAct()))
        assertTrue(!isLegalGazetteStyleDocument("Short memo about team lunch."))
    }

    @Test
    fun `splits at chapter and schedule boundaries`() {
        val sections = splitLegalGazetteSections(dpdpaStyleAct())
        assertTrue(sections.size >= 5)
        assertTrue(sections.any { it.startsWith("CHAPTER VIII") })
        assertTrue(sections.any { it.startsWith("THE SCHEDULE") })
    }

    @Test
    fun `legal chunking keeps penalties heading with section 33 body`() {
        val act = dpdpaStyleAct()
        val standard = chunkDocumentText(act, 600, 80)
        val legal = chunkLegalGazetteDocument(act)
        val standardCombined = standard.count { chunk ->
            chunk.contains("CHAPTER VIII") && chunk.contains("33. Penalties")
        }
        val legalCombined = legal.count { chunk ->
            chunk.contains("CHAPTER VIII") && chunk.contains("33. Penalties")
        }
        assertTrue("Legal path should keep heading + §33 together ($legalCombined)", legalCombined >= 1)
        assertTrue(
            "Legal should beat 600c split ($standardCombined vs $legalCombined)",
            legalCombined >= standardCombined,
        )
    }

    @Test
    fun `schedule section uses smaller table chunks`() {
        val scheduleOnly = buildString {
            append("THE SCHEDULE\n")
            append((1..20).joinToString("\n") { i ->
                "Row $i — penalty amount ₹${i * 10} crore for breach type $i"
            })
        }
        val wrapped = "CHAPTER VIII\nPENALTIES\n\n$scheduleOnly"
        val chunks = chunkLegalGazetteDocument(wrapped)
        val scheduleChunk = chunks.firstOrNull { it.contains("THE SCHEDULE") }
        assertTrue(scheduleChunk != null)
        assertTrue(
            "Schedule chunk should stay table-sized",
            scheduleChunk!!.length <= LEGAL_TABLE_CHUNK_SIZE * 2,
        )
    }

    @Test
    fun `index router picks legal path for pdf acts`() {
        val chunks = chunkDocumentTextForIndexing(dpdpaStyleAct(), mimeType = "application/pdf")
        assertTrue(chunks.any { it.contains("33. Penalties") })
    }
}
