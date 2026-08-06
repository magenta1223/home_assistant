package com.homeassistant.adapter.outbound.persistence.topicanalysis

import com.homeassistant.adapter.outbound.persistence.db.tables.*
import com.homeassistant.adapter.outbound.persistence.repo.indexing.IndexingOutboxRepository
import com.homeassistant.adapter.outbound.persistence.repo.topicanalysis.TopicRepository
import com.homeassistant.application.topicanalysis.save.IndexTargetType
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.topicanalysis.MemoryProposal
import com.homeassistant.domain.topicanalysis.TopicProposal
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class TopicRepositoryTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database
    private lateinit var repository: TopicRepository
    private lateinit var outbox: IndexingOutboxRepository

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(TopicTable, CategoryTable, TopicCategoryTable, MemoryTable, MemoryEvidenceTable, IndexingOutboxTable)
        }
        repository = TopicRepository(db)
        outbox = IndexingOutboxRepository(db)
    }

    @AfterTest
    fun teardown() = keepAlive.close()

    @Test
    fun `stores topic and canonical memories in normalized rows`() {
        val topic = repository.create(proposal(), TEST_USER, SOURCE)

        assertEquals(setOf(MemoryType.EVENT), topic.memoryTypes.toSet())
        assertEquals(setOf("location", "home"), topic.categories.toSet())
        assertEquals(listOf(2, 3), topic.evidenceRefs)
        assertEquals(listOf(topic.memories.single().id), outbox.pending(IndexTargetType.MEMORY))
    }

    @Test
    fun `reuses existing topic for same source title and evidence ids`() {
        val first = repository.create(proposal(), TEST_USER, SOURCE)
        val second = repository.create(proposal(), TEST_USER, SOURCE)

        assertEquals(first.id, second.id)
        assertEquals(first.memories.single().id, second.memories.single().id)
    }

    private fun proposal() = TopicProposal(
        title = "카인드커피에서 만나기",
        summary = "카인드커피 위치를 공유했다.",
        categories = listOf("location", "home", "location"),
        memories = listOf(
            MemoryProposal(
                content = "홍승민은 카인드커피로 오라고 말했다.",
                subject = "홍승민",
                memoryType = MemoryType.EVENT,
                certainty = MemoryCertainty.SAID,
                evidenceIds = listOf(2, 3, 2),
            ),
        ),
    )

    private companion object {
        val TEST_USER = UserId("dad")
        val SOURCE = SourceDescriptor("kakao", "2026-06-07.txt")
    }
}
