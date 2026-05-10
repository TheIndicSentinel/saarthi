package com.saarthi.core.memory.domain

import com.saarthi.core.memory.db.MemoryDao
import com.saarthi.core.memory.db.MemoryEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MemoryRepositoryImpl is the surface that feeds stored user facts back
 * into the chat system prompt. Two pieces are worth covering:
 *
 *   • buildContextSummary — pure string-building from a list of entities.
 *     This is the ONE function whose output ends up in the LLM's input,
 *     so any drift in formatting silently degrades chat quality.
 *
 *   • CRUD pass-through — set/get/delete delegate to the DAO. We mock
 *     the DAO and verify that the contract holds: arguments arrive
 *     unchanged, and Entity↔Domain mapping doesn't lose data.
 */
class MemoryRepositoryImplTest {

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
        coEvery { dao.getAll() } returns emptyList()

        val summary = repository.buildContextSummary()

        assertEquals("", summary)
    }

    @Test
    fun `buildContextSummary uses friendly label for known key`() = runTest {
        coEvery { dao.getAll() } returns listOf(
            MemoryEntity(key = "name", value = "Rahul", packSource = "USER")
        )

        val summary = repository.buildContextSummary()

        // The key "name" maps to the "Name" label in the rich label map —
        // a regression here means the LLM sees raw key strings instead of
        // human-readable facts.
        assertTrue("Should use 'Name' label for 'name' key. Got:\n$summary",
            summary.contains("- Name: Rahul"))
    }

    @Test
    fun `buildContextSummary maps user-prefixed keys to same labels`() = runTest {
        // Both "name" and "user_name" should land on the same human label,
        // so two memory writers (model emitting the marker vs. settings UI)
        // don't surface the user's name twice under different labels.
        coEvery { dao.getAll() } returns listOf(
            MemoryEntity(key = "user_name", value = "Priya", packSource = "USER")
        )

        val summary = repository.buildContextSummary()

        assertTrue("Should use 'Name' label for 'user_name'. Got:\n$summary",
            summary.contains("- Name: Priya"))
    }

    @Test
    fun `buildContextSummary humanises unknown keys`() = runTest {
        // An unmapped key like "preferred_payment_method" should still be
        // readable — falls back to a Title-Cased version with underscores
        // replaced by spaces.
        coEvery { dao.getAll() } returns listOf(
            MemoryEntity(key = "preferred_payment_method", value = "UPI", packSource = "USER")
        )

        val summary = repository.buildContextSummary()

        assertTrue("Unknown key should be humanised. Got:\n$summary",
            summary.contains("- Preferred payment method: UPI"))
    }

    @Test
    fun `buildContextSummary opens with a directive header`() = runTest {
        // The header is the contract with the LLM — it tells the model
        // that what follows is "personal knowledge to use naturally"
        // rather than instructions or tool output. If this drifts the
        // model may misread the bullets as new tool definitions.
        coEvery { dao.getAll() } returns listOf(
            MemoryEntity(key = "city", value = "Pune", packSource = "USER")
        )

        val summary = repository.buildContextSummary()

        assertTrue("Summary must start with the personal-knowledge header. Got:\n$summary",
            summary.startsWith("What you know about this user"))
    }

    @Test
    fun `buildContextSummary lists all entries in DAO order`() = runTest {
        coEvery { dao.getAll() } returns listOf(
            MemoryEntity(key = "name", value = "A", packSource = "USER"),
            MemoryEntity(key = "city", value = "B", packSource = "USER"),
            MemoryEntity(key = "profession", value = "C", packSource = "USER"),
        )

        val summary = repository.buildContextSummary()

        // All three values should appear, in the order the DAO returned.
        // The DAO orders by updatedAt DESC; the repository preserves order.
        val nameIdx = summary.indexOf("Name: A")
        val cityIdx = summary.indexOf("City / Location: B")
        val profIdx = summary.indexOf("Profession / Work: C")
        assertTrue("All entries must appear:\n$summary",
            nameIdx >= 0 && cityIdx >= 0 && profIdx >= 0)
        assertTrue("Order must be preserved", nameIdx < cityIdx && cityIdx < profIdx)
    }

    // ── CRUD pass-through ──────────────────────────────────────────────

    @Test
    fun `get returns null when DAO has no row`() = runTest {
        coEvery { dao.get("missing") } returns null

        assertNull(repository.get("missing"))
    }

    @Test
    fun `get maps Entity to Domain preserving all fields`() = runTest {
        coEvery { dao.get("name") } returns
            MemoryEntity(key = "name", value = "Anjali", packSource = "USER", updatedAt = 12345L)

        val entry = repository.get("name")!!

        assertEquals("name", entry.key)
        assertEquals("Anjali", entry.value)
        assertEquals("USER", entry.packSource)
        assertEquals(12345L, entry.updatedAt)
    }

    @Test
    fun `set forwards key value and packSource to DAO upsert`() = runTest {
        repository.set("city", "Bengaluru", "USER")

        coVerify(exactly = 1) {
            dao.upsert(match { it.key == "city" && it.value == "Bengaluru" && it.packSource == "USER" })
        }
    }

    @Test
    fun `delete forwards key to DAO`() = runTest {
        repository.delete("name")

        coVerify(exactly = 1) { dao.delete("name") }
    }
}
