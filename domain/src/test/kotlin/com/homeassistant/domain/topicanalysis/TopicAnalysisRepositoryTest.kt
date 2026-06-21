package com.homeassistant.domain.topicanalysis

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.domain.db.tables.TopicCandidateTable
import com.homeassistant.nlp.topicanalysis.ClaimCertainty
import com.homeassistant.nlp.topicanalysis.NewTopicClaim
import com.homeassistant.nlp.topicanalysis.SourceDocument
import com.homeassistant.nlp.topicanalysis.SourceRecord
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TopicAnalysisRepositoryTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database
    private lateinit var repository: TopicAnalysisRepository

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(TopicCandidateTable)
        }
        repository = TopicAnalysisRepository(db)
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
            document = document(),
            title = "카인드커피에서 만나기",
            summary = "카인드커피 위치를 공유하고 그곳으로 오라고 말했다.",
            memoryTypes = listOf(MemoryType.EVENT, MemoryType.LOCATION, MemoryType.EVENT),
            domains = listOf("location", "home", "location"),
            evidence = listOf(record2(), record3(), record2()),
            claims = listOf(
                NewTopicClaim(
                    text = "홍승민은 카인드커피로 오라고 말했다.",
                    subject = "홍승민",
                    memoryType = MemoryType.EVENT,
                    certainty = ClaimCertainty.SAID,
                    evidence = listOf(record2(), record3(), record2()),
                ),
            ),
        )

        assertEquals(setOf(MemoryType.EVENT, MemoryType.LOCATION), topic.memoryTypes.toSet())
        assertEquals(setOf("location", "home"), topic.domains.toSet())
        assertEquals(listOf(2, 3), topic.evidenceRefs)
        assertEquals("홍승민은 카인드커피로 오라고 말했다.", topic.claims.single().text)
        assertEquals(listOf(2, 3), topic.claims.single().evidenceRefs)
        assertEquals(CandidateStatus.PENDING, topic.status)
    }

    @Test
    fun `reuses existing topic for same source title and evidence ids`() {
        val first = createSimpleTopic()
        val second = createSimpleTopic()

        assertEquals(first.id, second.id)
        assertEquals(first.claims.single().id, second.claims.single().id)
    }

    private fun createSimpleTopic() =
        repository.createTopic(
            document = document(),
            title = "관계 표현",
            summary = "애정 표현을 주고받았다.",
            memoryTypes = listOf(MemoryType.STATE),
            domains = listOf("relationship"),
            evidence = listOf(record1()),
            claims = listOf(
                NewTopicClaim(
                    text = "동훈은 애정 표현을 했다.",
                    subject = "동훈",
                    memoryType = MemoryType.STATE,
                    certainty = ClaimCertainty.OBSERVED,
                    evidence = listOf(record1()),
                ),
            ),
        )

    private fun document(): SourceDocument =
        SourceDocument(
            sourceType = "kakao",
            sourceName = "2026-06-07.txt",
            records = listOf(record1(), record2(), record3()),
        )

    private fun record1(): SourceRecord = SourceRecord("r1", 1, "동훈 | 오후 4:49 | 따랑해")

    private fun record2(): SourceRecord = SourceRecord("r2", 2, "홍승민 | 오후 5:38 | [네이버지도]\n카인드커피")

    private fun record3(): SourceRecord = SourceRecord("r3", 3, "홍승민 | 오후 5:38 | 여기루 와용 ㅎㅎ")

    private fun columnNames(table: String): List<String> = transaction(db) {
        val names = mutableListOf<String>()
        exec("PRAGMA table_info($table)") { result ->
            while (result.next()) names += result.getString("name")
        }
        names
    }
}
