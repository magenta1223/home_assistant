package com.homeassistant.domain

import com.homeassistant.core.identity.UserId
import com.homeassistant.core.tools.ToolCallSpec
import com.homeassistant.domain.db.tables.*
import com.homeassistant.domain.memory.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.*
import kotlin.test.*

class DomainToolRegistryTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var registry: DomainToolRegistry

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
        registry = DomainToolRegistry(db, DeterministicEmbeddingService("test-model"), RecordingVectorStore())
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
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
    fun `execute memory_candidate_create succeeds`() = runBlocking {
        val result = registry.execute(
            spec(
                "memory_candidate_create",
                """{"conversation_id":"conv-1","domain":"SCHOOL","memory_type":"STATE","content":"Min has piano","summary":"Min piano","confidence":0.8}""",
            ),
            userId,
        )
        assertContains(result.value, "candidate_id=")
        assertFalse(result.value.startsWith("ERROR"))
    }

    @Test
    fun `execute unknown tool throws`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            registry.execute(spec("unknown_tool", "{}"), userId)
        }
    }

    private class RecordingVectorStore : VectorStore {
        override fun upsert(point: VectorPoint) = Unit
        override fun search(vector: List<Float>, filter: MemorySearchFilter, limit: Int): List<VectorSearchResult> = emptyList()
    }
}
