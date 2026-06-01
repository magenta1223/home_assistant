package com.homeassistant.domain.memory

import com.homeassistant.core.commands.UserId
import com.homeassistant.core.tools.ToolArguments
import com.homeassistant.core.tools.ToolCallSpec
import com.homeassistant.core.tools.ToolName
import com.homeassistant.domain.db.tables.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class MemoryToolsTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var repo: MemoryRepository
    private lateinit var vectorStore: RecordingVectorStore
    private lateinit var tools: MemoryTools

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        val db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(
                FamilyTable,
                FamilyMemberTable,
                DomainTable,
                ConversationMessageTable,
                MemoryCandidateTable,
                MemoryTable,
                AuditLogTable,
            )
        }
        repo = MemoryRepository(db)
        vectorStore = RecordingVectorStore()
        tools = MemoryTools(repo, DeterministicEmbeddingService("test-model"), vectorStore)
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
    }

    private fun spec(name: String, args: String) = ToolCallSpec(ToolName(name), ToolArguments(args))
    private val userId = UserId("dad")

    @Test
    fun `memory_candidate_create returns pending candidate id`() {
        val result = tools.execute(
            spec(
                "memory_candidate_create",
                """{"conversation_id":"conv-1","domain":"SCHOOL","memory_type":"FACT","content":"Min has piano Friday","summary":"Min piano","confidence":0.8}""",
            ),
            userId,
        )

        assertContains(result.value, "candidate_id=")
        assertEquals(1, repo.listPending(userId, "conv-1").size)
    }

    @Test
    fun `memory_candidate_approve promotes candidate and upserts vector point`() {
        val created = tools.execute(
            spec(
                "memory_candidate_create",
                """{"conversation_id":"conv-1","domain":"HOME","memory_type":"PREFERENCE","content":"Dad prefers decaf after dinner","summary":"Dad decaf","confidence":0.7}""",
            ),
            userId,
        )
        val candidateId = created.value.substringAfter("candidate_id=").substringBefore(" ").trim().toInt()

        val result = tools.execute(spec("memory_candidate_approve", """{"candidate_id":$candidateId}"""), userId)

        assertContains(result.value, "memory_id=")
        assertEquals(1, vectorStore.upserts.size)
    }

    @Test
    fun `memory_search combines vector ids with sqlite metadata`() {
        val memory = repo.approveCandidate(
            userId,
            repo.createCandidate(userId, "conv-1", "TRAVEL", MemoryType.EVENT, "Trip to Busan in July", "Busan July", 0.9, null),
        )
        vectorStore.results = listOf(VectorSearchResult(memory.id, 0.95))

        val result = tools.execute(spec("memory_search", """{"query":"summer trip","domain":"TRAVEL"}"""), userId)

        assertContains(result.value, "memory_id=${memory.id}")
        assertContains(result.value, "Busan July")
    }

    private class RecordingVectorStore : VectorStore {
        val upserts = mutableListOf<VectorPoint>()
        var results: List<VectorSearchResult> = emptyList()

        override fun upsert(point: VectorPoint) {
            upserts += point
        }

        override fun search(vector: List<Float>, filter: MemorySearchFilter, limit: Int): List<VectorSearchResult> = results
    }
}
