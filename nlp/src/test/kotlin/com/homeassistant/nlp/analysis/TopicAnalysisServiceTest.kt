package com.homeassistant.nlp.analysis

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.core.nlp.LlmRawResponse
import com.homeassistant.core.nlp.LlmResponse
import com.homeassistant.core.nlp.Message
import com.homeassistant.core.nlp.SystemPrompt
import com.homeassistant.core.tools.Tool
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class TopicAnalysisServiceTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(
                TopicCandidateTable,
                TopicMemoryTypeTable,
                TopicDomainTable,
                TopicEvidenceTable,
            )
        }
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
    }

    @Test
    fun `analyzes datasource agnostic records into pending topics with evidence`() = runBlocking {
        val service = TopicAnalysisService(
            TopicAnalysisRepository(db),
            StaticBackend(
                """
                {
                  "topics": [
                    {
                      "title": "카인드커피에서 만나기",
                      "summary": "카인드커피 위치를 공유하고 그곳으로 오라고 말했다.",
                      "memoryTypes": ["EVENT", "FACT"],
                      "domains": ["location", "home"],
                      "evidenceRecordIds": ["r2", "r3"]
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val result = service.analyze(
            SourceDocument(
                sourceType = SourceType("kakao"),
                sourceName = SourceName("2026-06-07.txt"),
                records = listOf(
                    SourceRecord(SourceRecordId("r1"), SourceRecordRef(1), "동훈 | 오후 4:49 | 따랑해"),
                    SourceRecord(SourceRecordId("r2"), SourceRecordRef(2), "홍승민 | 오후 5:38 | [네이버지도]\n카인드커피"),
                    SourceRecord(SourceRecordId("r3"), SourceRecordRef(3), "홍승민 | 오후 5:38 | 여기루 와용 ㅎㅎ"),
                ),
            ),
        )

        assertEquals(1, result.topics.size)
        assertEquals("카인드커피에서 만나기", result.topics.single().title.value)
        assertEquals(setOf(MemoryType.EVENT, MemoryType.FACT), result.topics.single().memoryTypes.toSet())
        assertEquals(setOf(DomainTag("location"), DomainTag("home")), result.topics.single().domains.toSet())
        assertEquals(listOf(SourceRecordRef(2), SourceRecordRef(3)), result.topics.single().evidenceRefs)
        assertEquals(CandidateStatus.PENDING, result.topics.single().status)
    }

    @Test
    fun `prompt tells model not to split topics by time or interrupted order`() = runBlocking {
        val backend = CapturingBackend("""{"topics":[]}""")
        val service = TopicAnalysisService(TopicAnalysisRepository(db), backend)

        service.analyze(
            SourceDocument(
                sourceType = SourceType("kakao"),
                sourceName = SourceName("2026-06-07.txt"),
                records = listOf(
                    SourceRecord(SourceRecordId("r1"), SourceRecordRef(1), "병원 예약 내일이지?"),
                    SourceRecord(SourceRecordId("r2"), SourceRecordRef(2), "카페도 들르자"),
                    SourceRecord(SourceRecordId("r3"), SourceRecordRef(3), "병원 갈 때 아기수첩 챙길게"),
                ),
            ),
        )

        assertContains(backend.system.value, "시간 간격으로 나누지 마세요")
        assertContains(backend.system.value, "A-B-A")
        assertContains(backend.messages.single().content, "r1")
        assertContains(backend.messages.single().content, "r3")
    }
}

private class StaticBackend(private val response: String) : LlmBackend {
    override suspend fun complete(system: SystemPrompt, messages: List<Message>, tools: List<Tool>): LlmResponse =
        LlmResponse.Text(LlmRawResponse(response))
}

private class CapturingBackend(private val response: String) : LlmBackend {
    var system: SystemPrompt = SystemPrompt("")
    lateinit var messages: List<Message>

    override suspend fun complete(system: SystemPrompt, messages: List<Message>, tools: List<Tool>): LlmResponse {
        this.system = system
        this.messages = messages
        return LlmResponse.Text(LlmRawResponse(response))
    }
}
