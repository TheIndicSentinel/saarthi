package com.saarthi.feature.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 5.1 — follow-up topic carry and scope routing. */
class FollowUpTopicCarryTest {

    @Test
    fun `mergeFollowUpRetrievalQuery joins prior and current`() {
        val merged = mergeFollowUpRetrievalQuery(
            "Is this act applicable to children's data",
            "what about penalties",
        )
        assertTrue(merged.contains("children"))
        assertTrue(merged.contains("penalties"))
    }

    @Test
    fun `shouldPassPriorQueryToRetrieval for continuation after topical prior`() {
        assertTrue(
            shouldPassPriorQueryToRetrieval(
                "also explain more",
                "Is this act applicable to children's data",
            ),
        )
    }

    @Test
    fun `shouldPassPriorQueryToRetrieval for what about section follow-up`() {
        assertTrue(
            shouldPassPriorQueryToRetrieval(
                "what about section 15",
                "Is this act applicable to children's data",
            ),
        )
    }

    @Test
    fun `shouldPassPriorQueryToRetrieval rejects identical prior`() {
        assertFalse(
            shouldPassPriorQueryToRetrieval(
                "what are the penalties",
                "what are the penalties",
            ),
        )
    }

    @Test
    fun `shouldPassPriorQueryToRetrieval rejects short prior`() {
        assertFalse(shouldPassPriorQueryToRetrieval("also more", "Hi"))
    }

    @Test
    fun `isFollowUpScopeUpgrade when continuation adds section cue`() {
        assertTrue(
            isFollowUpScopeUpgrade(
                "what about section 15",
                "Is this applicable to processing",
            ),
        )
        assertFalse(
            isFollowUpScopeUpgrade(
                "what about section 15",
                "what does section 15 say about consent",
            ),
        )
    }

    @Test
    fun `followUpScopeRoutingQuery merges for carry turns`() {
        val merged = followUpScopeRoutingQuery(
            "what about penalties",
            "Is this act applicable to children's data",
        )
        assertTrue(merged.contains("children"))
        assertTrue(merged.contains("penalties"))
    }

    @Test
    fun `followUpScopeRoutingQuery unchanged without carry`() {
        assertEquals(
            "Explain photosynthesis",
            followUpScopeRoutingQuery("Explain photosynthesis", "prior topical question"),
        )
    }

    @Test
    fun `shouldMergePriorQueryInSearch aligns with pass gate`() {
        assertTrue(
            shouldMergePriorQueryInSearch(
                "also explain more",
                "Is this act applicable to children's data",
            ),
        )
        assertFalse(shouldMergePriorQueryInSearch("Explain photosynthesis", "some prior question"))
    }
}
