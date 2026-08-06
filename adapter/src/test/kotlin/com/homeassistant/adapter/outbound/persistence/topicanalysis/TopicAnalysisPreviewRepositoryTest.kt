package com.homeassistant.adapter.outbound.persistence.topicanalysis

import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.topicanalysis.ProposedMemory
import com.homeassistant.domain.topicanalysis.ProposedTopic
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
    fun `stores and loads preview raw text and topics`() {
        val stored = repository.createPreview(
            sourceFileName = "2026-06-07.txt",
            text = "[동훈] [오후 4:49] 따랑해",
            topics = listOf(topic()),
        )

        val loaded = repository.findPreview(stored.previewId)

        assertNotNull(loaded)
        assertEquals("2026-06-07.txt", loaded.sourceName)
        assertEquals("[동훈] [오후 4:49] 따랑해", loaded.text)
        assertEquals("관계 표현", loaded.topics.single().title)
        assertEquals(listOf(1), loaded.topics.single().evidenceRefs)
    }

    private fun topic() =
        ProposedTopic(
            createdByUserId = "dad",
            sourceType = "kakao",
            sourceName = "2026-06-07.txt",
            title = "관계 표현",
            summary = "애정 표현을 주고받았다.",
            memoryTypes = listOf(MemoryType.STATE),
            categories = listOf("relationship"),
            evidenceRefs = listOf(1),
            memories = listOf(
                ProposedMemory(
                    text = "동훈은 애정 표현을 했다.",
                    subject = "동훈",
                    memoryType = MemoryType.STATE,
                    certainty = MemoryCertainty.OBSERVED,
                    evidenceRefs = listOf(1),
                ),
            ),
        )
}
