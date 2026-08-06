package com.homeassistant.adapter.outbound.persistence.topicanalysis

import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.topicanalysis.MemoryProposal
import com.homeassistant.domain.topicanalysis.TopicProposal
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicAnalysisPreviewTable
import com.homeassistant.adapter.outbound.persistence.repo.topicanalysis.TopicAnalysisPreviewRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class TopicAnalysisPreviewRepositoryTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database
    private lateinit var repository: TopicAnalysisPreviewRepository

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(TopicAnalysisPreviewTable) }
        repository = TopicAnalysisPreviewRepository(db)
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
    }

    @Test
    fun `stores and loads preview context and topics`() {
        val stored = repository.createPreview(
            requestedByUserId = "dad",
            sourceType = "kakao",
            sourceName = "2026-06-07.txt",
            topics = listOf(topic()),
        )

        val loaded = repository.findPreview(stored.previewId)

        assertNotNull(loaded)
        assertEquals("dad", loaded.requestedByUserId)
        assertEquals("kakao", loaded.sourceType)
        assertEquals("2026-06-07.txt", loaded.sourceName)
        assertEquals("관계 표현", loaded.topics.single().title)
        assertEquals(listOf(1), loaded.topics.single().evidenceIds)
    }

    private fun topic() =
        TopicProposal(
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
                ),
            ),
        )
}
