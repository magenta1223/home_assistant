package com.homeassistant.application.usecase.topic.placement

import com.homeassistant.application.port.input.topic.placement.*
import com.homeassistant.application.port.output.topic.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TopicPlacementServiceTest {
    @Test
    fun `idle does not call model or write`() = runBlocking {
        val store = FakeStore(emptyList())
        val service = TopicPlacementService(store, { error("must not call") })
        assertEquals(TopicPlacementRunResult.Idle, service.processAvailable())
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `bounded supplied context allows multiple memberships and stable scoped creation keys`() = runBlocking {
        val store = FakeStore()
        val proposal = validProposal().copy(memberships = validProposal().memberships +
            TopicMembershipAddition(TopicReference.Existing(9), "Other", listOf(1)))
        val seen = mutableListOf<TopicPlacementInput>()
        val service = TopicPlacementService(store, { seen += it; proposal }, memoryLimit = 2, candidateLimit = 3)

        assertIs<TopicPlacementRunResult.Placed>(service.processAvailable())
        assertIs<TopicPlacementRunResult.Placed>(service.processAvailable())
        assertEquals(2, store.memoryLimit)
        assertEquals(3, store.candidateLimit)
        assertEquals(2, seen.size)
        seen.forEach { assertEquals(listOf(TopicCandidate(9, "Existing")), it.candidates) }
        assertEquals(store.writes.first(), store.writes.last())
        assertNotEquals("new", store.writes.first().creations.single().creationKey)
        assertEquals(setOf(1, 2), store.expectedIds)
    }

    @Test
    fun `rejects omitted foreign and out of candidate references without writing`() = runBlocking {
        val invalid = listOf(
            validProposal().copy(memberships = listOf(addition(listOf(1)))),
            validProposal().copy(memberships = listOf(addition(listOf(1, 2, 3)))),
            validProposal().copy(memberships = listOf(
                TopicMembershipAddition(TopicReference.Existing(99), "Facts", listOf(1, 2)),
            )),
            validProposal().copy(memberships = listOf(
                TopicMembershipAddition(TopicReference.Created("undeclared"), "Facts", listOf(1, 2)),
            )),
            validProposal().copy(creations = validProposal().creations + TopicCreation("unused", "Unused")),
            validProposal().copy(relations = listOf(TopicRelationAddition(created(), created()))),
            validProposal().copy(relations = listOf(TopicRelationAddition(created(), TopicReference.Existing(99)))),
        )
        invalid.forEach { proposal ->
            val store = FakeStore()
            val result = TopicPlacementService(store, { proposal }).processAvailable()
            assertEquals(TopicPlacementFailureStage.VALIDATE, assertIs<TopicPlacementRunResult.Failed>(result).stage)
            assertTrue(store.writes.isEmpty())
        }
    }

    @Test
    fun `load model and save failures are observable and retry is callable`() = runBlocking {
        val loadStore = FakeStore().apply { failLoad = true }
        val loadResult = TopicPlacementService(loadStore, { validProposal() }).processAvailable()
        assertEquals(TopicPlacementFailureStage.LOAD, assertIs<TopicPlacementRunResult.Failed>(loadResult).stage)
        val modelResult = TopicPlacementService(FakeStore(), { error("model failed") }).processAvailable()
        assertEquals(TopicPlacementFailureStage.PROPOSE, assertIs<TopicPlacementRunResult.Failed>(modelResult).stage)
        val store = FakeStore().apply { failSave = true }
        val service = TopicPlacementService(store, { validProposal() })
        assertEquals(TopicPlacementFailureStage.SAVE, assertIs<TopicPlacementRunResult.Failed>(service.processAvailable()).stage)
        store.failSave = false
        assertIs<TopicPlacementRunResult.Placed>(service.processAvailable())
    }

    @Test
    fun `stale and already placed writes are distinct outcomes`() = runBlocking {
        val store = FakeStore().apply { outcome = TopicPlacementCommitResult.Stale }
        val service = TopicPlacementService(store, { validProposal() })
        assertEquals(TopicPlacementRunResult.Stale, service.processAvailable())
        store.outcome = TopicPlacementCommitResult.AlreadyPlaced
        assertEquals(TopicPlacementRunResult.AlreadyPlaced, service.processAvailable())
    }

    @Test
    fun `cancellation propagates and configured limits must be positive`() = runBlocking {
        assertFailsWith<CancellationException> {
            TopicPlacementService(FakeStore(), { throw CancellationException("stop") }).processAvailable()
        }
        assertFailsWith<IllegalArgumentException> { TopicPlacementService(FakeStore(), { validProposal() }, memoryLimit = 0) }
        assertFailsWith<IllegalArgumentException> { TopicPlacementService(FakeStore(), { validProposal() }, candidateLimit = 0) }
    }

    private fun validProposal() = TopicWriteBatch(
        creations = listOf(TopicCreation("new", "New topic")),
        memberships = listOf(addition(listOf(1, 2))),
        relations = listOf(TopicRelationAddition(created(), TopicReference.Existing(9))),
    )

    private fun created() = TopicReference.Created("new")
    private fun addition(ids: List<Int>) = TopicMembershipAddition(created(), "Facts", ids)

    private class FakeStore(
        val memories: List<TopicPlacementMemory> = listOf(TopicPlacementMemory(1, "One"), TopicPlacementMemory(2, "Two")),
    ) : TopicPlacementStore {
        var memoryLimit = 0
        var candidateLimit = 0
        var failLoad = false
        var failSave = false
        var expectedIds = emptySet<Int>()
        val writes = mutableListOf<TopicWriteBatch>()
        var outcome: TopicPlacementCommitResult = TopicPlacementCommitResult.Placed(TopicWriteResult(mapOf("key" to 10)))

        override fun loadUnplaced(limit: Int): List<TopicPlacementMemory> {
            check(!failLoad)
            memoryLimit = limit
            return memories.take(limit)
        }

        override fun loadCandidates(limit: Int): List<TopicCandidate> {
            candidateLimit = limit
            return listOf(TopicCandidate(9, "Existing"))
        }

        override fun commitIfUnplaced(memoryIds: Set<Int>, batch: TopicWriteBatch): TopicPlacementCommitResult {
            check(!failSave)
            expectedIds = memoryIds
            writes += batch
            return outcome
        }
    }
}
