package com.homeassistant.nlp.analysis

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.core.nlp.LlmRawResponse
import com.homeassistant.core.nlp.LlmResponse
import com.homeassistant.core.nlp.Message
import com.homeassistant.core.nlp.LlmOutputSchema
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
                    TopicClassificationTable,
                    TopicDomainTable,
                    TopicEvidenceTable,
                    TopicClaimTable,
                    TopicClaimEvidenceTable,
                )
            }
        }

    @AfterTest
    fun teardown() {
        keepAlive.close()
    }

    @Test
    fun `topic analysis tables store memory type in a single column`() {
        assertEquals(
            setOf("memory_type"),
            memoryColumnNames("topic_classifications").filter { it.startsWith("memory_") }.toSet(),
        )
        assertEquals(
            setOf("memory_type"),
            memoryColumnNames("topic_claims").filter { it.startsWith("memory_") }.toSet(),
        )
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
                      "memoryTypes": ["EVENT", "LOCATION"],
                      "domains": ["location", "home"],
                      "evidenceRecordIds": ["r2", "r3"],
                      "claims": [
                        {
                          "text": "홍승민은 카인드커피로 오라고 말했다.",
                          "subject": "홍승민",
                          "memoryType": "EVENT",
                          "certainty": "SAID",
                          "evidenceRecordIds": ["r2", "r3"]
                        }
                      ]
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
        assertEquals(
            setOf(
                MemoryType.EVENT,
                MemoryType.LOCATION,
            ),
            result.topics.single().memoryTypes.toSet(),
        )
        assertEquals(setOf(DomainTag("location"), DomainTag("home")), result.topics.single().domains.toSet())
        assertEquals(listOf(SourceRecordRef(2), SourceRecordRef(3)), result.topics.single().evidenceRefs)
        assertEquals("홍승민은 카인드커피로 오라고 말했다.", result.topics.single().claims.single().text.value)
        assertEquals(
            MemoryType.EVENT,
            result.topics.single().claims.single().memoryType,
        )
        assertEquals(ClaimCertainty.SAID, result.topics.single().claims.single().certainty)
        assertEquals(listOf(SourceRecordRef(2), SourceRecordRef(3)), result.topics.single().claims.single().evidenceRefs)
        assertEquals(CandidateStatus.PENDING, result.topics.single().status)
    }

    @Test
    fun `previews topic analysis without storing topics`() = runBlocking {
        val emptySchemaDb = Database.connect("jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared", driver = "org.sqlite.JDBC")
        val service = TopicAnalysisService(TopicAnalysisRepository(emptySchemaDb), StaticBackend(topicJson()))

        val result = service.preview(singleRecordDocument())

        assertEquals(1, result.topics.size)
        assertEquals("관계 표현", result.topics.single().title.value)
        assertEquals(listOf(SourceRecordRef(1)), result.topics.single().evidenceRefs)
        assertEquals("동훈은 애정 표현을 했다.", result.topics.single().claims.single().text.value)
        assertEquals(ClaimCertainty.OBSERVED, result.topics.single().claims.single().certainty)
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
        assertContains(backend.outputSchema!!.value, "claims")
        assertContains(backend.outputSchema!!.value, "memoryTypes")
        assertContains(backend.outputSchema!!.value, "memoryType")
        assertContains(backend.outputSchema!!.value, "certainty")
    }

    @Test
    fun `prompt is generated from topic output schema`() = runBlocking {
        val backend = CapturingBackend("""{"topics":[]}""")
        val service = TopicAnalysisService(TopicAnalysisRepository(db), backend)

        service.analyze(singleRecordDocument())

        assertContains(backend.system.value, TopicAnalysisOutputContract.schema.value)
        assertFalse(backend.system.value.contains("memoryKind"))
        assertFalse(backend.system.value.contains("memorySubtype"))
        assertFalse(backend.system.value.contains("JSON만 반환하세요"))
    }

    @Test
    fun `rejects invalid topic analysis responses`() = runBlocking {

        val document = singleRecordDocument()

        assertFailsWith<TopicAnalysisException> {
            serviceFor("""not json""").analyze(document)
        }
        assertFailsWith<TopicAnalysisException> {
            serviceFor(topicJson(memoryTypes = """["UNKNOWN"]""")).analyze(document)
        }
        assertFailsWith<TopicAnalysisException> {
            serviceFor(topicJson(evidenceRecordIds = """["missing"]""")).analyze(document)
        }
        assertFailsWith<TopicAnalysisException> {
            serviceFor(topicJson(title = "")).analyze(document)
        }
        assertFailsWith<TopicAnalysisException> {
            serviceFor(topicJson(summary = "")).analyze(document)
        }
    }

    @Test
    fun `analyzes topic response wrapped in json code fence`() = runBlocking {
        val result = serviceFor("```json\n${topicJson()}\n```").analyze(singleRecordDocument())

        assertEquals("관계 표현", result.topics.single().title.value)
    }

    @Test
    fun `topic output contract decodes concrete memory type values`() {
        val semantic = TopicAnalysisOutputContract.decode(
            topicJson(memoryTypes = """["STATE"]"""),
        )
        val episodic = TopicAnalysisOutputContract.decode(
            topicJson(memoryTypes = """["OBSERVATION"]"""),
        )
        val procedural = TopicAnalysisOutputContract.decode(
            topicJson(memoryTypes = """["CHECKLIST"]"""),
        )

        assertEquals(MemoryType.STATE, semantic.topics.single().memoryTypes.single())
        assertEquals(MemoryType.OBSERVATION, episodic.topics.single().memoryTypes.single())
        assertEquals(MemoryType.CHECKLIST, procedural.topics.single().memoryTypes.single())
    }

    @Test
    fun `analyzes topic response wrapped in json code fence with trailing text`() = runBlocking {
        val result = serviceFor("```json\n${topicJson()}\n```\n분석 완료").analyze(singleRecordDocument())

        assertEquals("관계 표현", result.topics.single().title.value)
    }

    @Test
    fun `analyzes topic response with trailing comma`() = runBlocking {
        val result = serviceFor(
            """
            {
              "topics": [
                {
                  "title": "관계 표현",
                  "summary": "애정 표현을 주고받았다.",
                  "memoryTypes": ["STATE"],
                  "domains": ["relationship"],
                  "evidenceRecordIds": ["r1"],
                  "claims": [
                    {
                      "text": "동훈은 애정 표현을 했다.",
                      "subject": "동훈",
                      "memoryType": "STATE",
                      "certainty": "OBSERVED",
                      "evidenceRecordIds": ["r1"],
                    }
                  ],
                }
              ],
            }
            """.trimIndent(),
        ).analyze(singleRecordDocument())

        assertEquals("관계 표현", result.topics.single().title.value)
    }

    @Test
    fun `rejects invalid topic claims`() = runBlocking {
        val document = singleRecordDocument()

        assertFailsWith<TopicAnalysisException> {
            serviceFor(topicJson(claims = "[]")).analyze(document)
        }
        assertFailsWith<TopicAnalysisException> {
            serviceFor(topicJson(claimText = "")).analyze(document)
        }
        assertFailsWith<TopicAnalysisException> {
            serviceFor(topicJson(claimSubject = "")).analyze(document)
        }
        assertFailsWith<TopicAnalysisException> {
            serviceFor(topicJson(claimMemoryType = "UNKNOWN")).analyze(document)
        }
        assertFailsWith<TopicAnalysisException> {
            serviceFor(topicJson(claimCertainty = "GUESSED")).analyze(document)
        }
        assertFailsWith<TopicAnalysisException> {
            serviceFor(topicJson(claimEvidenceRecordIds = """["missing"]""")).analyze(document)
        }
    }

    @Test
    fun `stores topic claims once for the same topic text and evidence set`() = runBlocking {
        val service = serviceFor(topicJson())
        val document = singleRecordDocument()

        val first = service.analyze(document).topics.single()
        val second = service.analyze(document).topics.single()

        assertEquals(first.id, second.id)
        assertEquals(1, second.claims.size)
        assertEquals(first.claims.single().id, second.claims.single().id)
    }

    @Test
    fun `generates topic analysis output schema from serializable dto`() {
        val schema = TopicAnalysisOutputContract.schema

        assertContains(schema.value, "topics")
        assertContains(schema.value, "claims")
        assertContains(schema.value, "memoryTypes")
        assertContains(schema.value, "memoryType")
        assertFalse(schema.value.contains("memoryKind"))
        assertFalse(schema.value.contains("memorySubtype"))
        assertContains(schema.value, "certainty")
        assertContains(schema.value, "evidenceRecordIds")
    }

    private fun serviceFor(response: String): TopicAnalysisService =
        TopicAnalysisService(TopicAnalysisRepository(db), StaticBackend(response))

    private fun singleRecordDocument(): SourceDocument =
        SourceDocument(
            sourceType = SourceType("kakao"),
            sourceName = SourceName("2026-06-07.txt"),
            records = listOf(SourceRecord(SourceRecordId("r1"), SourceRecordRef(1), "동훈 | 오후 4:49 | 따랑해")),
        )

    private fun memoryColumnNames(table: String): List<String> = transaction(db) {
        val names = mutableListOf<String>()
        exec("PRAGMA table_info($table)") { result ->
            while (result.next()) names += result.getString("name")
        }
        names
    }

    private fun topicJson(
        title: String = "관계 표현",
        summary: String = "애정 표현을 주고받았다.",
        memoryTypes: String = """["STATE"]""",
        domains: String = """["relationship"]""",
        evidenceRecordIds: String = """["r1"]""",
        claimText: String = "동훈은 애정 표현을 했다.",
        claimSubject: String = "동훈",
        claimMemoryType: String = "STATE",
        claimCertainty: String = "OBSERVED",
        claimEvidenceRecordIds: String = """["r1"]""",
        claims: String = """
            [
              {
                "text": "$claimText",
                "subject": "$claimSubject",
                "memoryType": "$claimMemoryType",
                "certainty": "$claimCertainty",
                "evidenceRecordIds": $claimEvidenceRecordIds
              }
            ]
        """.trimIndent(),
    ): String = """
        {
          "topics": [
            {
              "title": "$title",
              "summary": "$summary",
              "memoryTypes": $memoryTypes,
              "domains": $domains,
              "evidenceRecordIds": $evidenceRecordIds,
              "claims": $claims
            }
          ]
        }
    """.trimIndent()
}

private class StaticBackend(private val response: String) : LlmBackend {
    override suspend fun complete(
        system: SystemPrompt,
        messages: List<Message>,
        tools: List<Tool>,
        outputSchema: LlmOutputSchema?,
    ): LlmResponse =
        LlmResponse.Text(LlmRawResponse(response))
}

private class CapturingBackend(private val response: String) : LlmBackend {
    var system: SystemPrompt = SystemPrompt("")
    lateinit var messages: List<Message>
    var outputSchema: LlmOutputSchema? = null

    override suspend fun complete(
        system: SystemPrompt,
        messages: List<Message>,
        tools: List<Tool>,
        outputSchema: LlmOutputSchema?,
    ): LlmResponse {
        this.system = system
        this.messages = messages
        this.outputSchema = outputSchema
        return LlmResponse.Text(LlmRawResponse(response))
    }
}
