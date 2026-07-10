package com.homeassistant.domain.memory

import com.homeassistant.core.identity.UserId
import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.core.tools.ToolCallSpec
import com.homeassistant.datamodel.memory.DEFAULT_FAMILY_ID
import com.homeassistant.datamodel.memory.MemoryCandidateRow
import com.homeassistant.datamodel.memory.MemoryRow
import com.homeassistant.domain.indexing.IndexTargetType
import com.homeassistant.domain.indexing.IndexingOutboxStore
import kotlin.test.*

class MemoryToolsTest {
    private lateinit var repo: FakeMemoryStore
    private lateinit var vectorStore: RecordingVectorStore
    private lateinit var embeddingService: RecordingEmbeddingService
    private lateinit var tools: MemoryTools
    private lateinit var outbox: FakeIndexingOutboxStore

    @BeforeTest
    fun setup() {
        repo = FakeMemoryStore()
        vectorStore = RecordingVectorStore()
        embeddingService = RecordingEmbeddingService()
        outbox = FakeIndexingOutboxStore()
        tools = MemoryTools(repo, embeddingService, vectorStore, outbox)
    }

    private fun spec(name: String, args: String) = ToolCallSpec(name, args)
    private val userId = UserId("dad")

    @Test
    fun `memory_candidate_create returns pending candidate id`() {
        val result = tools.execute(
            spec(
                "memory_candidate_create",
                """{"conversationId":"conv-1","domain":"SCHOOL","memoryType":"STATE","content":"Min has piano Friday","summary":"Min piano","confidence":0.8}""",
            ),
            userId,
        )

        assertContains(result.value, "candidate_id=")
        assertEquals(1, repo.listPending(userId, "conv-1").size)
    }

    @Test
    fun `memory_candidate_create rejects unknown memory type`() {
        val result = tools.execute(
            spec(
                "memory_candidate_create",
                """{"conversationId":"conv-1","domain":"SCHOOL","memoryType":"FACT","content":"Min has piano Friday","summary":"Min piano","confidence":0.8}""",
            ),
            userId,
        )

        assertContains(result.value, "ERROR:")
        assertTrue(repo.listPending(userId, "conv-1").isEmpty())
    }

    @Test
    fun `memory_candidate_create rejects missing memory type`() {
        val result = tools.execute(
            spec(
                "memory_candidate_create",
                """{"conversationId":"conv-1","domain":"HOME","content":"Passport in drawer","summary":"Passport location","confidence":0.8}""",
            ),
            userId,
        )

        assertContains(result.value, "ERROR:")
        assertTrue(repo.listPending(userId, "conv-1").isEmpty())
    }

    @Test
    fun `memory_candidate_approve promotes candidate and upserts vector point`() {
        val created = tools.execute(
            spec(
                "memory_candidate_create",
                """{"conversationId":"conv-1","domain":"HOME","memoryType":"PREFERENCE","content":"Dad prefers decaf after dinner","summary":"Dad decaf","confidence":0.7}""",
            ),
            userId,
        )
        val candidateId = created.value.substringAfter("candidate_id=").substringBefore(" ").trim().toInt()

        val result = tools.execute(spec("memory_candidate_approve", """{"candidateId":$candidateId}"""), userId)

        assertContains(result.value, "memory_id=")
        assertEquals(1, vectorStore.upserts.size)
        assertEquals(listOf("passage: Dad decaf\nDad prefers decaf after dinner"), embeddingService.embeddedTexts)
        assertEquals("dad", vectorStore.upserts.single().payload["createdBy"])
        assertTrue(vectorStore.upserts.single().numericPayload.getValue("createdAt") > 0)
    }

    @Test
    fun `memory_candidate_approve keeps committed memory pending when vector indexing fails`() {
        val created = tools.execute(
            spec(
                "memory_candidate_create",
                """{"conversationId":"conv-1","domain":"HOME","memoryType":"STATE","content":"Stored first","summary":"Stored","confidence":0.9}""",
            ),
            userId,
        )
        val candidateId = created.value.substringAfter("candidate_id=").substringBefore(" ").trim().toInt()
        vectorStore.failUpserts = true

        val result = tools.execute(spec("memory_candidate_approve", """{"candidateId":$candidateId}"""), userId)

        assertContains(result.value, "memory_id=")
        assertContains(result.value, "index_status=INDEX_PENDING")
        assertEquals(listOf(1), outbox.pending(IndexTargetType.MEMORY))
        assertEquals(1, repo.listMemories().size)
    }

    @Test
    fun `memory_search combines vector ids with sqlite metadata`() {
        val memory = repo.approveCandidate(
            userId,
            repo.createCandidate(
                userId,
                "conv-1",
                "TRAVEL",
                MemoryType.EVENT,
                "Trip to Busan in July",
                "Busan July",
                0.9,
                null,
            ),
        )
        vectorStore.results = listOf(VectorSearchResult(memory.id, 0.95))

        val result = tools.execute(
            spec(
                "memory_search",
                """{"query":"summer trip","domain":"TRAVEL","createdAfter":100,"createdBefore":200}""",
            ),
            userId,
        )

        assertContains(result.value, "memory_id=${memory.id}")
        assertContains(result.value, "Busan July")
        assertEquals("query: summer trip", embeddingService.embeddedTexts.single())
        assertEquals("dad", vectorStore.lastFilter?.createdBy)
        assertEquals(100, vectorStore.lastFilter?.createdAfter)
        assertEquals(200, vectorStore.lastFilter?.createdBefore)
    }

    private class RecordingEmbeddingService : EmbeddingService {
        val embeddedTexts = mutableListOf<String>()

        override fun embed(text: String): List<Float> {
            embeddedTexts += text
            return List(384) { index -> index / 384.0f }
        }
    }

    private class RecordingVectorStore : VectorStore {
        val upserts = mutableListOf<VectorPoint>()
        var results: List<VectorSearchResult> = emptyList()
        var failUpserts = false
        var lastFilter: MemorySearchFilter? = null

        override fun upsert(point: VectorPoint) {
            if (failUpserts) error("qdrant unavailable")
            upserts += point
        }

        override fun search(vector: List<Float>, filter: MemorySearchFilter, limit: Int): List<VectorSearchResult> {
            lastFilter = filter
            return results
        }
    }

    private class FakeIndexingOutboxStore : IndexingOutboxStore {
        private val pending = mutableMapOf<IndexTargetType, MutableSet<Int>>()

        override fun pending(targetType: IndexTargetType, limit: Int): List<Int> =
            pending[targetType].orEmpty().take(limit)

        override fun markIndexed(targetType: IndexTargetType, targetId: Int) {
            pending[targetType]?.remove(targetId)
        }

        override fun markFailed(targetType: IndexTargetType, targetId: Int, error: String) {
            pending.getOrPut(targetType) { linkedSetOf() }.add(targetId)
        }
    }

    private class FakeMemoryStore : MemoryStore {
        private val candidates = mutableMapOf<Int, MemoryCandidateRow>()
        private val memories = mutableMapOf<Int, MemoryRow>()
        private var nextCandidateId = 1
        private var nextMemoryId = 1

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
        ): Int {
            val id = nextCandidateId++
            val now = System.currentTimeMillis()
            candidates[id] = MemoryCandidateRow(
                id = id,
                familyId = DEFAULT_FAMILY_ID,
                conversationId = conversationId,
                domainId = domainName.hashCode(),
                domainName = domainName.uppercase(),
                memoryType = memoryType,
                content = content,
                summary = summary,
                subjectMemberId = subjectMemberId,
                createdBy = userId.value,
                visibility = visibility,
                confidence = confidence,
                sourceConversationMessageId = sourceConversationMessageId,
                status = CandidateStatus.PENDING,
                createdAt = now,
                updatedAt = now,
            )
            return id
        }

        override fun listPending(userId: UserId, conversationId: String): List<MemoryCandidateRow> =
            candidates.values.filter {
                it.createdBy == userId.value &&
                    it.conversationId == conversationId &&
                    it.status == CandidateStatus.PENDING
            }

        override fun getCandidate(id: Int): MemoryCandidateRow? = candidates[id]

        override fun approveCandidate(userId: UserId, candidateId: Int): MemoryRow {
            val candidate = candidates.getValue(candidateId)
            val now = System.currentTimeMillis()
            candidates[candidateId] = candidate.copy(status = CandidateStatus.APPROVED, updatedAt = now)
            return MemoryRow(
                id = nextMemoryId++,
                familyId = candidate.familyId,
                domainId = candidate.domainId,
                domainName = candidate.domainName,
                memoryType = candidate.memoryType,
                content = candidate.content,
                summary = candidate.summary,
                subjectMemberId = candidate.subjectMemberId,
                createdBy = candidate.createdBy,
                visibility = candidate.visibility,
                confidence = candidate.confidence,
                sourceConversationMessageId = candidate.sourceConversationMessageId,
                sourceCandidateId = candidateId,
                createdAt = now,
                updatedAt = now,
            ).also { memories[it.id] = it }
        }

        override fun rejectCandidate(userId: UserId, candidateId: Int) {
            val candidate = candidates.getValue(candidateId)
            candidates[candidateId] = candidate.copy(status = CandidateStatus.REJECTED)
        }

        override fun getMemory(id: Int): MemoryRow? = memories[id]

        override fun listMemories(ids: List<Int>?): List<MemoryRow> =
            ids?.mapNotNull { memories[it] } ?: memories.values.toList()
    }
}
