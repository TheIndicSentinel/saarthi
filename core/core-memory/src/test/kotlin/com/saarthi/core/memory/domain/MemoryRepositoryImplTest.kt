package com.saarthi.core.memory.domain

import com.saarthi.core.memory.db.MemoryDao
import com.saarthi.core.memory.db.MemoryEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MemoryRepositoryImpl is the surface that feeds stored user facts back
 * into the chat system prompt. Two pieces are worth covering:
 *
 *   • buildContextSummary — pure string-building from a list of entities,
 *     SCOPED to the calling session. New chats see only their own memories;
 *     deleted chats are wiped via deleteForSession(). This is the contract
 *     that prevents leak-across-chat bugs.
 *
 *   • CRUD pass-through — set/get/delete delegate to the DAO. We mock the
 *     DAO and verify the contract holds: sessionId arrives unchanged,
 *     Entity↔Domain mapping doesn't lose data.
 */
class MemoryRepositoryImplTest {

    private val SESSION = "test-session-1"

    private lateinit var dao: MemoryDao
    private lateinit var repository: MemoryRepositoryImpl

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        repository = MemoryRepositoryImpl(dao)
    }

    // ── buildContextSummary ────────────────────────────────────────────

    @Test
    fun `buildContextSummary returns empty string when no facts stored`() = runTest {
        coEvery { dao.getBySession(SESSION) } returns emptyList()

        val summary = repository.buildContextSummary(SESSION)

        assertEquals("", summary)
    }

    @Test
    fun `buildContextSummary uses friendly label for known key`() = runTest {
        coEvery { dao.getBySession(SESSION) } returns listOf(
            MemoryEntity(sessionId = SESSION, key = "name", value = "Rahul", packSource = "USER")
        )

        val summary = repository.buildContextSummary(SESSION)

        // "name" maps to the "Name" label. Regression here means the LLM
        // sees raw key strings instead of human-readable facts.
        assertTrue(
            "Should use 'Name' label for 'name' key. Got:\n$summary",
            summary.contains("- Name: Rahul"),
        )
    }

    @Test
    fun `buildContextSummary maps user-prefixed keys to same labels`() = runTest {
        coEvery { dao.getBySession(SESSION) } returns listOf(
            MemoryEntity(sessionId = SESSION, key = "user_name", value = "Priya", packSource = "USER")
        )

        val summary = repository.buildContextSummary(SESSION)

        assertTrue(
            "Should use 'Name' label for 'user_name'. Got:\n$summary",
            summary.contains("- Name: Priya"),
        )
    }

    @Test
    fun `buildContextSummary humanises unknown keys`() = runTest {
        coEvery { dao.getBySession(SESSION) } returns listOf(
            MemoryEntity(
                sessionId = SESSION,
                key = "preferred_payment_method",
                value = "UPI",
                packSource = "USER",
            )
        )

        val summary = repository.buildContextSummary(SESSION)

        assertTrue(
            "Unknown key should be humanised. Got:\n$summary",
            summary.contains("- Preferred payment method: UPI"),
        )
    }

    @Test
    fun `buildContextSummary returns bullets only — no header`() = runTest {
        // Header lives in SystemPromptProvider (single source of truth) so
        // buildContextSummary returns just the bullet list. Keeping the
        // header out of here prevents duplicate headers in the final prompt
        // (the prompt provider wraps the bullets with its own scope-
        // aware header).
        coEvery { dao.getBySession(SESSION) } returns listOf(
            MemoryEntity(sessionId = SESSION, key = "city", value = "Pune", packSource = "USER")
        )

        val summary = repository.buildContextSummary(SESSION)

        assertTrue(
            "Bullets must be present. Got:\n$summary",
            summary.contains("- City / Location: Pune"),
        )
        assertFalse(
            "Repo must NOT add its own header — that's the provider's job",
            summary.contains("What the user shared"),
        )
    }

    @Test
    fun `buildContextSummary lists all entries in DAO order`() = runTest {
        coEvery { dao.getBySession(SESSION) } returns listOf(
            MemoryEntity(sessionId = SESSION, key = "name", value = "A", packSource = "USER"),
            MemoryEntity(sessionId = SESSION, key = "city", value = "B", packSource = "USER"),
            MemoryEntity(sessionId = SESSION, key = "profession", value = "C", packSource = "USER"),
        )

        val summary = repository.buildContextSummary(SESSION)

        val nameIdx = summary.indexOf("Name: A")
        val cityIdx = summary.indexOf("City / Location: B")
        val profIdx = summary.indexOf("Profession / Work: C")
        assertTrue(
            "All entries must appear:\n$summary",
            nameIdx >= 0 && cityIdx >= 0 && profIdx >= 0,
        )
        assertTrue("Order must be preserved", nameIdx < cityIdx && cityIdx < profIdx)
    }

    @Test
    fun `buildContextSummary reads ONLY this session and the global user profile`() = runTest {
        coEvery { dao.getBySession(SESSION) } returns emptyList()
        coEvery { dao.getBySession(MemoryRepository.USER_SCOPE) } returns emptyList()

        repository.buildContextSummary(SESSION)

        // Two intentional tiers: the calling chat + the global identity
        // profile. It must NEVER read another CHAT's memories (no observeAll,
        // no other concrete session id) — that's the cross-chat-bleed contract.
        coVerify(exactly = 1) { dao.getBySession(SESSION) }
        coVerify(exactly = 1) { dao.getBySession(MemoryRepository.USER_SCOPE) }
        coVerify(exactly = 0) { dao.observeAll() }
    }

    @Test
    fun `buildContextSummary merges global user profile with session facts`() = runTest {
        // Durable identity lives in USER_SCOPE; chat-specific notes in SESSION.
        coEvery { dao.getBySession(MemoryRepository.USER_SCOPE) } returns listOf(
            MemoryEntity(sessionId = MemoryRepository.USER_SCOPE, key = "name", value = "Arjun", packSource = "USER"),
        )
        coEvery { dao.getBySession(SESSION) } returns listOf(
            MemoryEntity(sessionId = SESSION, key = "topic", value = "DPDP Act", packSource = "USER"),
        )

        val summary = repository.buildContextSummary(SESSION)

        // Global profile fact appears in every chat …
        assertTrue("Global identity must surface. Got:\n$summary", summary.contains("- Name: Arjun"))
        // … alongside this chat's own context.
        assertTrue("Session fact must surface. Got:\n$summary", summary.contains("- Topic: DPDP Act"))
    }

    @Test
    fun `session fact overrides global profile for the same key`() = runTest {
        coEvery { dao.getBySession(MemoryRepository.USER_SCOPE) } returns listOf(
            MemoryEntity(sessionId = MemoryRepository.USER_SCOPE, key = "city", value = "Delhi", packSource = "USER"),
        )
        coEvery { dao.getBySession(SESSION) } returns listOf(
            MemoryEntity(sessionId = SESSION, key = "city", value = "Mumbai", packSource = "USER"),
        )

        val summary = repository.buildContextSummary(SESSION)

        assertTrue("Session value must win. Got:\n$summary", summary.contains("- City / Location: Mumbai"))
        assertFalse("Global value must be overridden. Got:\n$summary", summary.contains("Delhi"))
    }

    @Test
    fun `isUserScopedKey classifies identity facts as global and context as session`() {
        // Identity → global profile
        assertTrue(MemoryRepository.isUserScopedKey("name"))
        assertTrue(MemoryRepository.isUserScopedKey("user_name"))
        assertTrue(MemoryRepository.isUserScopedKey("profession"))
        assertTrue(MemoryRepository.isUserScopedKey("city"))
        assertTrue(MemoryRepository.isUserScopedKey("age"))
        // Conversational context → stays in the chat
        assertFalse(MemoryRepository.isUserScopedKey("topic"))
        assertFalse(MemoryRepository.isUserScopedKey("grocery_list"))
        assertFalse(MemoryRepository.isUserScopedKey("current_task"))
    }

    // ── CRUD pass-through ──────────────────────────────────────────────

    @Test
    fun `get returns null when DAO has no row in this session`() = runTest {
        coEvery { dao.getInSession(SESSION, "missing") } returns null

        assertNull(repository.get(SESSION, "missing"))
    }

    @Test
    fun `get maps Entity to Domain preserving all fields`() = runTest {
        coEvery { dao.getInSession(SESSION, "name") } returns
            MemoryEntity(
                sessionId = SESSION,
                key = "name",
                value = "Anjali",
                packSource = "USER",
                updatedAt = 12345L,
            )

        val entry = repository.get(SESSION, "name")!!

        assertEquals(SESSION, entry.sessionId)
        assertEquals("name", entry.key)
        assertEquals("Anjali", entry.value)
        assertEquals("USER", entry.packSource)
        assertEquals(12345L, entry.updatedAt)
    }

    @Test
    fun `set forwards sessionId key value and packSource to DAO upsert`() = runTest {
        repository.set(SESSION, "city", "Bengaluru", "USER")

        coVerify(exactly = 1) {
            dao.upsert(
                match {
                    it.sessionId == SESSION &&
                        it.key == "city" &&
                        it.value == "Bengaluru" &&
                        it.packSource == "USER"
                },
            )
        }
    }

    @Test
    fun `delete forwards sessionId and key to DAO`() = runTest {
        repository.delete(SESSION, "name")

        coVerify(exactly = 1) { dao.deleteInSession(SESSION, "name") }
    }

    @Test
    fun `deleteForSession cascades to DAO once`() = runTest {
        repository.deleteForSession(SESSION)

        // Critical contract: chat-deletion path MUST wipe all memories
        // tied to that session, atomically. Failure here means deleted
        // chats leave memory residue that surfaces in future chats —
        // the exact bug we shipped this fix for.
        coVerify(exactly = 1) { dao.deleteAllInSession(SESSION) }
    }

    @Test
    fun `deleteForSession does not touch other sessions`() = runTest {
        repository.deleteForSession(SESSION)

        coVerify(exactly = 0) { dao.deleteEverything() }
        coVerify(exactly = 0) { dao.deleteAllInSession("some-other-session") }
    }

    @Test
    fun `buildContextSummary returns empty when no entries — never just header`() = runTest {
        coEvery { dao.getBySession(SESSION) } returns emptyList()

        val summary = repository.buildContextSummary(SESSION)

        assertEquals(
            "Empty memory list must return empty string — never just a header",
            "",
            summary,
        )
    }
}
