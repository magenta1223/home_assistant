package com.homeassistant.adapter.outbound.persistence.topicanalysis

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.domain.topicanalysis.ClaimCertainty
import com.homeassistant.domain.topicanalysis.TopicCandidate
import com.homeassistant.domain.topicanalysis.TopicClaimCandidate
import com.homeassistant.adapter.outbound.persistence.db.tables.TopicCandidateTable
import com.homeassistant.adapter.outbound.persistence.db.tables.IndexingOutboxTable
import com.homeassistant.domain.indexing.IndexTargetType
import com.homeassistant.adapter.outbound.persistence.repo.indexing.IndexingOutboxRepository
import com.homeassistant.adapter.outbound.persistence.repo.topicanalysis.TopicAnalysisRepository
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class TopicAnalysisRepositoryTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database
    private lateinit var repository: TopicAnalysisRepository
    private lateinit var outbox: IndexingOutboxRepository

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(TopicCandidateTable, IndexingOutboxTable)
        }
        repository = TopicAnalysisRepository(db)
        outbox = IndexingOutboxRepository(db)
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
    }

    @Test
    fun `topic candidates store list fields as json columns`() {
        assertEquals(
            setOf("memory_types_json", "domains_json", "evidence_json", "claims_json"),
            columnNames("topic_candidates").filter { it.endsWith("_json") }.toSet(),
        )
    }

    @Test
    fun `stores topic candidate lists in one topic candidate row`() {
        val topic = repository.createTopic(
            TopicCandidate(
                familyId = "family-1",
                createdByUserId = "dad",
                sourceType = "kakao",
                sourceName = "2026-06-07.txt",
                title = "카인드커피에서 만나기",
                summary = "카인드커피 위치를 공유하고 그곳으로 오라고 말했다.",
                memoryTypes = listOf(MemoryType.EVENT, MemoryType.LOCATION, MemoryType.EVENT),
                domains = listOf("location", "home", "location"),
                evidenceRefs = listOf(2, 3, 2),
                claims = listOf(
                    TopicClaimCandidate(
                        text = "홍승민은 카인드커피로 오라고 말했다.",
                        subject = "홍승민",
                        memoryType = MemoryType.EVENT,
                        certainty = ClaimCertainty.SAID,
                        evidenceRefs = listOf(2, 3, 2),
                    ),
                ),
            ),
        )

        assertEquals(setOf(MemoryType.EVENT, MemoryType.LOCATION), topic.memoryTypes.toSet())
        assertEquals(setOf("location", "home"), topic.domains.toSet())
        assertEquals(listOf(2, 3), topic.evidenceRefs)
        assertEquals("홍승민은 카인드커피로 오라고 말했다.", topic.claims.single().text)
        assertEquals(listOf(2, 3), topic.claims.single().evidenceRefs)
        assertEquals(CandidateStatus.APPROVED, topic.status)
        assertEquals(listOf(topic.id), outbox.pending(IndexTargetType.TOPIC))
    }

    @Test
    fun `reuses existing topic for same source title and evidence ids`() {
        val first = createSimpleTopic()
        val second = createSimpleTopic()

        assertEquals(first.id, second.id)
        assertEquals(first.claims.single().id, second.claims.single().id)
    }

    @Test
    fun `approves existing pending topic when saving same approved topic again`() {
        val pending = createSimpleTopic()
        transaction(db) {
            TopicCandidateTable.update({ TopicCandidateTable.id eq pending.id }) {
                it[status] = CandidateStatus.PENDING.name
            }
        }

        val approved = createSimpleTopic()

        assertEquals(pending.id, approved.id)
        assertEquals(CandidateStatus.APPROVED, approved.status)
        assertEquals(CandidateStatus.APPROVED.name, topicStatus(pending.id))
    }

    private fun createSimpleTopic() =
        repository.createTopic(
            TopicCandidate(
                familyId = "family-1",
                createdByUserId = "dad",
                sourceType = "kakao",
                sourceName = "2026-06-07.txt",
                title = "관계 표현",
                summary = "애정 표현을 주고받았다.",
                memoryTypes = listOf(MemoryType.STATE),
                domains = listOf("relationship"),
                evidenceRefs = listOf(1),
                claims = listOf(
                    TopicClaimCandidate(
                        text = "동훈은 애정 표현을 했다.",
                        subject = "동훈",
                        memoryType = MemoryType.STATE,
                        certainty = ClaimCertainty.OBSERVED,
                        evidenceRefs = listOf(1),
                    ),
                ),
            ),
        )

    private fun columnNames(table: String): List<String> = transaction(db) {
        val names = mutableListOf<String>()
        exec("PRAGMA table_info($table)") { result ->
            while (result.next()) names += result.getString("name")
        }
        names
    }

    private fun topicStatus(topicId: Int): String = transaction(db) {
        var status = ""
        exec("SELECT status FROM topic_candidates WHERE id = $topicId") { result ->
            if (result.next()) status = result.getString("status")
        }
        status
    }
}
