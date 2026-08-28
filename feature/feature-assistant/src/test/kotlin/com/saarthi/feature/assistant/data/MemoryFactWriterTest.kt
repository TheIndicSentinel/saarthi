package com.saarthi.feature.assistant.data

import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.memory.domain.MemoryEntry
import com.saarthi.core.memory.domain.MemoryRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Persistence policy extracted from ChatRepositoryImpl.persistMemoryFact.
 * Asserts repository interactions only — DebugLogger is stubbed so JVM
 * unit tests don't depend on Android Log / file sinks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryFactWriterTest {

    private val memoryRepository: MemoryRepository = mockk(relaxed = true)
    // Real extractor: name shape/completeness must use the production
    // isPlausibleNameValue rules, not a mock defaulting to false.
    private val implicitFactExtractor = ImplicitFactExtractor()
    private val writer = MemoryFactWriter(memoryRepository, implicitFactExtractor)

    @Before
    fun setUp() {
        mockkObject(DebugLogger)
        every { DebugLogger.log(any(), any()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkObject(DebugLogger)
    }

    @Test
    fun `blank key or value does not call memoryRepository set`() = runTest {
        writer.persist(sessionId = SESSION, rawKey = "", value = "Arjun")
        writer.persist(sessionId = SESSION, rawKey = "name", value = "")
        writer.persist(sessionId = SESSION, rawKey = "   ", value = "   ")

        coVerify(exactly = 0) { memoryRepository.set(any(), any(), any(), any()) }
    }

    @Test
    fun `aggregate keys user_facts and profile are rejected`() = runTest {
        writer.persist(sessionId = SESSION, rawKey = "user_facts", value = "नाम: अर्जुन, राशि: धनु")
        writer.persist(sessionId = SESSION, rawKey = "profile", value = "a bundled profile blob")

        coVerify(exactly = 0) { memoryRepository.set(any(), any(), any(), any()) }
    }

    @Test
    fun `durable identity key is written to USER_SCOPE`() = runTest {
        // `name` is an IDENTITY_KEY_STEMS / NAME_KEY_STEMS key — profile tier.
        writer.persist(sessionId = SESSION, rawKey = "name", value = "Arjun")

        coVerify(exactly = 1) {
            memoryRepository.set(
                sessionId = MemoryRepository.USER_SCOPE,
                key = "name",
                value = "Arjun",
                packSource = "USER",
            )
        }
    }

    @Test
    fun `non-scoped key is written to the chat sessionId`() = runTest {
        // `topic` is not an identity stem, not a list key, not a junk aggregate.
        writer.persist(sessionId = SESSION, rawKey = "topic", value = "wheat prices")

        coVerify(exactly = 1) {
            memoryRepository.set(
                sessionId = SESSION,
                key = "topic",
                value = "wheat prices",
                packSource = "USER",
            )
        }
    }

    @Test
    fun `name key with implausible value is not written`() = runTest {
        // Sentence-shaped — isPlausibleNameValue is false.
        writer.persist(
            sessionId = SESSION,
            rawKey = "name",
            value = "उपयोगकर्ता का नाम अर्जुन है",
        )

        coVerify(exactly = 0) { memoryRepository.set(any(), any(), any(), any()) }
    }

    @Test
    fun `name key skips set when existing value is more complete`() = runTest {
        coEvery {
            memoryRepository.get(sessionId = MemoryRepository.USER_SCOPE, key = "name")
        } returns MemoryEntry(
            sessionId = MemoryRepository.USER_SCOPE,
            key = "name",
            value = "Arjun",
            packSource = "USER",
            updatedAt = 0L,
        )

        writer.persist(sessionId = SESSION, rawKey = "name", value = "Raj")

        coVerify(exactly = 0) { memoryRepository.set(any(), any(), any(), any()) }
    }

    @Test
    fun `list key merges existing value before set`() = runTest {
        // `likes` is both a USER_SCOPE identity stem and a list key.
        val existing = "apples"
        coEvery {
            memoryRepository.get(sessionId = MemoryRepository.USER_SCOPE, key = "likes")
        } returns MemoryEntry(
            sessionId = MemoryRepository.USER_SCOPE,
            key = "likes",
            value = existing,
            packSource = "USER",
            updatedAt = 0L,
        )

        writer.persist(sessionId = SESSION, rawKey = "likes", value = "oranges")

        val expected = MemoryRepository.mergeListValue(existing, "oranges")
        coVerify(exactly = 1) {
            memoryRepository.set(
                sessionId = MemoryRepository.USER_SCOPE,
                key = "likes",
                value = expected,
                packSource = "USER",
            )
        }
    }

    private companion object {
        const val SESSION = "chat-session-1"
    }
}
