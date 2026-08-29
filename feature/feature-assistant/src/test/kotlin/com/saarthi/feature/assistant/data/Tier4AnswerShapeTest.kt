package com.saarthi.feature.assistant.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier 4.13 — LIST / structure / weak-match answer-shape polish. */
class Tier4AnswerShapeTest {

    @Test
    fun `structure count query adds count-first instruction`() {
        val instruction = ragAnswerShapeInstruction(
            RagAnswerShape.LIST,
            structureCountQuery = true,
            structureListQuery = true,
        )
        assertTrue(instruction.contains("STRUCTURE COUNT"))
        assertTrue(instruction.contains("count first", ignoreCase = true))
    }

    @Test
    fun `structure list query adds enumerate-only instruction`() {
        val instruction = ragAnswerShapeInstruction(
            RagAnswerShape.LIST,
            structureListQuery = true,
        )
        assertTrue(instruction.contains("STRUCTURE LIST"))
        assertTrue(instruction.contains("explicitly", ignoreCase = true))
    }

    @Test
    fun `weak match list encourages short honest miss`() {
        val instruction = ragAnswerShapeInstruction(
            RagAnswerShape.LIST,
            strongMatch = false,
            tabularAmount = true,
        )
        assertTrue(instruction.contains("WEAK MATCH"))
        assertTrue(instruction.contains("do not pad", ignoreCase = true))
        assertTrue(instruction.contains("TABULAR"))
    }

    @Test
    fun `strong match list omits weak match block`() {
        val instruction = ragAnswerShapeInstruction(
            RagAnswerShape.LIST,
            strongMatch = true,
            tabularAmount = true,
        )
        assertFalse(instruction.contains("WEAK MATCH"))
        assertTrue(instruction.contains("TABULAR"))
    }

    @Test
    fun `weak match narrow qa discourages padding`() {
        val instruction = ragAnswerShapeInstruction(
            RagAnswerShape.NARROW_QA,
            strongMatch = false,
        )
        assertTrue(instruction.contains("WEAK MATCH"))
        assertTrue(instruction.contains("not in the excerpts", ignoreCase = true))
    }
}
