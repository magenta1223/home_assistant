package com.homeassistant.domain

import com.homeassistant.core.identity.UserId
import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.core.tools.ToolCallSpec
import com.homeassistant.datamodel.memory.DEFAULT_FAMILY_ID
import com.homeassistant.datamodel.memory.MemoryCandidateRow
import com.homeassistant.datamodel.memory.MemoryRow
import com.homeassistant.domain.memory.*
import com.homeassistant.domain.indexing.IndexingOutboxes
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.*

class DomainToolRegistryTest {
    private lateinit var registry: DomainTools

    @BeforeTest
    fun setup() {
        registry = DomainToolsFactory.create(
            FakeMemoryStore(),
            DeterministicEmbeddingService("test-model"),
            RecordingVectorStore(),
            IndexingOutboxes.noOp(),
        )
    }

    private fun spec(name: String, args: String) = ToolCallSpec(name, args)
    private val userId = UserId("test-user")

    @Test
    fun `tools() returns memory tools only`() {
        assertEquals(
            listOf(
                "memory_candidate_create",
                "memory_candidate_list_pending",
                "memory_candidate_approve",
                "memory_candidate_reject",
                "memory_search",
            ),
            registry.tools().map { it.name },
        )
    }

    @Test
    fun `execute memory_candidate_create succeeds`() = runSuspend {
        val result = registry.execute(
            spec(
                "memory_candidate_create",
                """{"conversationId":"conv-1","domain":"SCHOOL","memoryType":"STATE","content":"Min has piano","summary":"Min piano","confidence":0.8}""",
            ),
            userId,
        )
        assertContains(result.value, "candidate_id=")
        assertFalse(result.value.startsWith("ERROR"))
    }

    @Test
    fun `execute unknown tool throws`() = runSuspend {
        assertFailsWith<IllegalStateException> {
            registry.execute(spec("unknown_tool", "{}"), userId)
        }
    }

    private class RecordingVectorStore : VectorStore {
        override fun upsert(point: VectorPoint) = Unit
        override fun search(vector: List<Float>, filter: MemorySearchFilter, limit: Int): List<VectorSearchResult> = emptyList()
    }

    private class FakeMemoryStore : MemoryStore {
        private var nextId = 1

        override fun createCandidate(
            userId: UserId,
            conversationId: String,
            domainName: String,
            memoryType: MemoryType,
            content: String,
            summary: String,
            confidence: Double,
            sourceConversationMessageId: Int?,
            subjectMemberId: String?,
            visibility: String,
        ): Int = nextId++

        override fun listPending(userId: UserId, conversationId: String): List<MemoryCandidateRow> = emptyList()
        override fun getCandidate(id: Int): MemoryCandidateRow? = null
        override fun approveCandidate(userId: UserId, candidateId: Int): MemoryRow =
            MemoryRow(candidateId, DEFAULT_FAMILY_ID, 1, "GENERAL", MemoryType.STATE, "", "", null, userId.value, "FAMILY", 1.0, null, candidateId, 0, 0)

        override fun rejectCandidate(userId: UserId, candidateId: Int) = Unit
        override fun getMemory(id: Int): MemoryRow? = null
        override fun listMemories(ids: List<Int>?): List<MemoryRow> = emptyList()
    }
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var value: T? = null
    var failure: Throwable? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                result.fold(
                    onSuccess = { value = it },
                    onFailure = { failure = it },
                )
            }
        },
    )
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return value as T
}
