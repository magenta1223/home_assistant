package com.homeassistant.adapter.outbound.persistence.memory

import com.homeassistant.adapter.outbound.persistence.db.tables.*
import com.homeassistant.adapter.outbound.persistence.repo.memory.MemoryRepository
import com.homeassistant.adapter.outbound.persistence.repo.topicanalysis.TopicRepository
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.topicanalysis.MemoryProposal
import com.homeassistant.domain.topicanalysis.TopicProposal
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class CanonicalMemoryRepositoryTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database
    private lateinit var topics: TopicRepository
    private lateinit var memories: MemoryRepository

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(TopicTable, CategoryTable, TopicCategoryTable, MemoryTable, MemoryEvidenceTable, IndexingOutboxTable)
        }
        topics = TopicRepository(db)
        memories = MemoryRepository(db)
    }

    @AfterTest
    fun teardown() = keepAlive.close()

    @Test
    fun `loads private memory for internal consumers`() {
        val topic = topics.create(proposal(MemoryVisibility.PRIVATE), UserId("dad"), SOURCE)
        val memoryId = topic.memories.single().id

        assertEquals(listOf(memoryId), memories.findByIds(listOf(memoryId)).map { it.memory.id })
        assertEquals(emptyList(), memories.findVisibleByIds(UserId("mom"), listOf(memoryId)))
    }

    @Test
    fun `loads only requested memories with topic context for indexing`() {
        val topic = topics.create(proposal(), UserId("dad"), SOURCE)
        val memoryId = topic.memories.single().id

        val context = memories.findVisibleByIds(UserId("mom"), listOf(memoryId)).single()

        assertEquals(memoryId, context.memory.id)
        assertEquals(topic.id, context.topic?.id)
        assertEquals(topic.title, context.topic?.title)
    }

    @Test
    fun `loads standalone memory without topic context`() {
        val memoryId = transaction(db) {
            val id = MemoryTable.insert {
                it[topicId] = null
                it[createdByUserId] = "dad"
                it[content] = "독립 기억"
                it[subject] = "대상"
                it[memoryType] = MemoryType.REFERENCE.name
                it[certainty] = MemoryCertainty.SAID.name
                it[visibility] = MemoryVisibility.FAMILY.name
                it[createdAt] = 1L
                it[updatedAt] = 1L
            }[MemoryTable.id]
            MemoryEvidenceTable.insert {
                it[MemoryEvidenceTable.memoryId] = id
                it[sourceRecordId] = 10
            }
            id
        }

        val context = memories.findByIds(listOf(memoryId)).single()

        assertNull(context.memory.topicId)
        assertNull(context.topic)
        assertEquals("독립 기억", context.memory.content)
    }

    private fun proposal(visibility: MemoryVisibility = MemoryVisibility.FAMILY) = TopicProposal(
        title = "관계 표현",
        summary = "애정 표현을 주고받았다.",
        categories = listOf("relationship"),
        memories = listOf(
            MemoryProposal(
                content = "동훈은 애정 표현을 했다.",
                subject = "동훈",
                memoryType = MemoryType.STATE,
                certainty = MemoryCertainty.OBSERVED,
                evidenceIds = listOf(1),
                visibility = visibility,
            ),
        ),
    )

    private companion object {
        val SOURCE = SourceDescriptor("kakao", "2026-06-07.txt")
    }
}
