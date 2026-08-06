package com.homeassistant.adapter.outbound.persistence.topicanalysis

import com.homeassistant.adapter.outbound.persistence.db.tables.TopicAnalysisReviewTable
import com.homeassistant.adapter.outbound.persistence.repo.topicanalysis.TopicAnalysisReviewRepository
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TopicAnalysisReviewRepositoryTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database
    private lateinit var repository: TopicAnalysisReviewRepository

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(TopicAnalysisReviewTable) }
        repository = TopicAnalysisReviewRepository(db)
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
    }

    @Test
    fun `stores and loads review context and proposals`() {
        val stored = repository.create(
            requestedBy = UserId("dad"),
            source = SourceDescriptor("kakao", "2026-06-07.txt"),
            proposals = listOf(topic()),
        )

        val loaded = repository.find(stored.id)

        assertNotNull(loaded)
        assertEquals(UserId("dad"), loaded.requestedBy)
        assertEquals("kakao", loaded.source.type)
        assertEquals("2026-06-07.txt", loaded.source.name)
        assertEquals("관계 표현", loaded.proposals.single().title)
        assertEquals(listOf(1), loaded.proposals.single().evidenceIds)
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
