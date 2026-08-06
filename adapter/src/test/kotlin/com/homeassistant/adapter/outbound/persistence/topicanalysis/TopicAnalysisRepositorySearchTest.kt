package com.homeassistant.adapter.outbound.persistence.topicanalysis

import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import com.homeassistant.domain.topicanalysis.ClaimCertainty
import com.homeassistant.domain.topicanalysis.TopicCandidate
import com.homeassistant.domain.topicanalysis.TopicClaimCandidate
import com.homeassistant.adapter.outbound.persistence.db.tables.*
import com.homeassistant.adapter.outbound.persistence.repo.topicanalysis.TopicAnalysisRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TopicAnalysisRepositorySearchTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database
    private lateinit var repository: TopicAnalysisRepository

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(
                TopicTable,
                CategoryTable,
                TopicCategoryTable,
                MemoryTable,
                MemoryEvidenceTable,
                IndexingOutboxTable,
            )
        }
        repository = TopicAnalysisRepository(db)
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
    }

    @Test
    fun `search approved topics returns matching approved topic claims`() {
        val remote = repository.createTopic(topic("주차장 리모컨 위치", "주차장 차단기 리모컨은 벽장 제일 위칸에 있다.", 10))
        repository.createTopic(topic("점심 기록", "점심으로 쭈꾸미 덮밥을 먹었다.", 20))

        val results = repository.searchApprovedTopics(TEST_USER, "차단기 리모컨 어디", limit = 5)

        assertEquals(listOf(remote.id), results.map { it.id })
        assertEquals("주차장 리모컨 위치", results.single().title)
        assertEquals("주차장 차단기 리모컨은 벽장 제일 위칸에 있다.", results.single().claims.single().text)
    }

    @Test
    fun `search excludes another users private memories`() {
        repository.createTopic(
            topic(
                "세콤 경비 규칙",
                "개가 있으면 세콤 경비상태에서 움직임 감지가 될 수 있다.",
                30,
                MemoryVisibility.PRIVATE,
            ),
        )

        val results = repository.searchApprovedTopics(UserId("mom"), "세콤 경비", limit = 5)

        assertEquals(emptyList(), results)
    }

    @Test
    fun `search approved topics clamps limit to ten`() {
        repeat(12) { index ->
            repository.createTopic(topic("리모컨 후보 $index", "리모컨 관련 claim $index", index + 1))
        }

        val results = repository.searchApprovedTopics(TEST_USER, "리모컨", limit = 50)

        assertEquals(10, results.size)
    }

    private fun topic(
        title: String,
        claimText: String,
        evidenceRef: Int,
        visibility: MemoryVisibility = MemoryVisibility.FAMILY,
    ) =
        TopicCandidate(
            familyId = "household",
            createdByUserId = TEST_USER.value,
            sourceType = "kakao",
            sourceName = "family-kakao.txt",
            title = title,
            summary = "$title 요약",
            memoryTypes = listOf(MemoryType.REFERENCE),
            domains = listOf("home"),
            evidenceRefs = listOf(evidenceRef),
            claims = listOf(
                TopicClaimCandidate(
                    text = claimText,
                    subject = title,
                    memoryType = MemoryType.REFERENCE,
                    certainty = ClaimCertainty.SAID,
                    evidenceRefs = listOf(evidenceRef),
                    visibility = visibility,
                ),
            ),
        )

    private companion object {
        val TEST_USER = UserId("dad")
    }
}
