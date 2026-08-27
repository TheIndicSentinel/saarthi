package com.saarthi.feature.assistant.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1-5 — corpus-bound generation / unattached external regime. */
class UnattachedExternalT5Test {

    @Test
    fun `detects GDPR compare without GDPR file`() {
        val decision = detectUnattachedExternalQuery(
            "How is this different from GDPR",
            listOf("Digital Personal Data Protection Act 2023.pdf"),
        )
        assertTrue(decision.active)
        assertTrue(decision.regimes.contains("GDPR"))
    }

    @Test
    fun `inactive when GDPR file is attached`() {
        val decision = detectUnattachedExternalQuery(
            "compare with GDPR",
            listOf("GDPR Compliance Guide.pdf"),
        )
        assertFalse(decision.active)
    }

    @Test
    fun `citation rules block external regime import`() {
        val rules = ragCitationRules(compact = false, blockExternalRegimes = true)
        assertTrue(rules.contains("GDPR"))
        assertTrue(rules.contains("supervisory authority"))
    }

    @Test
    fun `unattached external adds boundary to answer shape`() {
        val instruction = ragAnswerShapeInstruction(
            RagAnswerShape.NARROW_QA,
            unattachedExternal = UnattachedExternalDecision(active = true, regimes = listOf("GDPR")),
        )
        assertTrue(instruction.contains("GDPR"))
        assertTrue(instruction.contains("Do NOT"))
    }
}
