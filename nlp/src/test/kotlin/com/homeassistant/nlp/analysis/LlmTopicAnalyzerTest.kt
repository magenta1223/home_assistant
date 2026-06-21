package com.homeassistant.nlp.analysis

import com.homeassistant.core.memory.MemoryType
import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.core.nlp.LlmResponse
import com.homeassistant.core.nlp.Message
import com.homeassistant.core.source.SourceDocument
import com.homeassistant.core.source.SourceRecord
import com.homeassistant.core.tools.Tool
import com.homeassistant.datamodel.topicanalysis.ClaimCertainty
import com.homeassistant.domain.topicanalysis.TopicAnalysisException
import com.homeassistant.nlp.topicanalysis.impl.LlmTopicAnalyzer
import com.homeassistant.nlp.topicanalysis.TopicAnalysisLlmResponse
import com.homeassistant.nlp.topicanalysis.TopicAnalysisOutputContract
import com.homeassistant.nlp.topicanalysis.TopicClaimLlmResponse
import com.homeassistant.nlp.topicanalysis.TopicLlmResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LlmTopicAnalyzerTest {
    @Test
    fun `analyzes datasource agnostic records into pending topics with evidence`() = runBlocking {
        val service = LlmTopicAnalyzer(
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
                sourceType = "kakao",
                sourceName = "2026-06-07.txt",
                records = listOf(
                    SourceRecord("r1", 1, "동훈 | 오후 4:49 | 따랑해"),
                    SourceRecord("r2", 2, "홍승민 | 오후 5:38 | [네이버지도]\n카인드커피"),
                    SourceRecord("r3", 3, "홍승민 | 오후 5:38 | 여기루 와용 ㅎㅎ"),
                ),
            ),
        )

        assertEquals(1, result.topics.size)
        assertEquals("카인드커피에서 만나기", result.topics.single().title)
        assertEquals(
            setOf(
                MemoryType.EVENT,
                MemoryType.LOCATION,
            ),
            result.topics.single().memoryTypes.toSet(),
        )
        assertEquals(setOf("location", "home"), result.topics.single().domains.toSet())
        assertEquals(listOf(2, 3), result.topics.single().evidence.map { it.ref })
        assertEquals("홍승민은 카인드커피로 오라고 말했다.", result.topics.single().claims.single().text)
        assertEquals(
            MemoryType.EVENT,
            result.topics.single().claims.single().memoryType,
        )
        assertEquals(ClaimCertainty.SAID, result.topics.single().claims.single().certainty)
        assertEquals(listOf(2, 3), result.topics.single().claims.single().evidence.map { it.ref })
    }

    @Test
    fun `prompt tells model not to split topics by time or interrupted order`() = runBlocking {
        val backend = CapturingBackend("""{"topics":[]}""")
        val service = LlmTopicAnalyzer(backend)

        service.analyze(
            SourceDocument(
                sourceType = "kakao",
                sourceName = "2026-06-07.txt",
                records = listOf(
                    SourceRecord("r1", 1, "병원 예약 내일이지?"),
                    SourceRecord("r2", 2, "카페도 들르자"),
                    SourceRecord("r3", 3, "병원 갈 때 아기수첩 챙길게"),
                ),
            ),
        )

        assertContains(backend.system, "시간 간격으로 나누지 마세요")
        assertContains(backend.system, "A-B-A")
        assertContains(backend.messages.single().content, "r1")
        assertContains(backend.messages.single().content, "r3")
        assertContains(backend.outputSchema, "claims")
        assertContains(backend.outputSchema, "memoryTypes")
        assertContains(backend.outputSchema, "memoryType")
        assertContains(backend.outputSchema, "certainty")
    }

    @Test
    fun `prompt is generated from topic output schema`() = runBlocking {
        val backend = CapturingBackend("""{"topics":[]}""")
        val service = LlmTopicAnalyzer(backend)

        service.analyze(singleRecordDocument())

        assertContains(backend.system, TopicAnalysisOutputContract.schema)
        assertFalse(backend.system.contains("memoryKind"))
        assertFalse(backend.system.contains("memorySubtype"))
        assertFalse(backend.system.contains("JSON만 반환하세요"))
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

        assertEquals("관계 표현", result.topics.single().title)
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

        assertEquals("관계 표현", result.topics.single().title)
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

        assertEquals("관계 표현", result.topics.single().title)
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
    fun `generates topic analysis output schema from serializable dto`() {
        val schema = TopicAnalysisOutputContract.schema

        assertContains(schema, "topics")
        assertContains(schema, "claims")
        assertContains(schema, "memoryTypes")
        assertContains(schema, "memoryType")
        assertFalse(schema.contains("memoryKind"))
        assertFalse(schema.contains("memorySubtype"))
        assertContains(schema, "certainty")
        assertContains(schema, "evidenceRecordIds")
    }

    @Test
    fun `topic analysis dto names come from class names without serial name overrides`() {
        assertEquals(
            "com.homeassistant.nlp.topicanalysis.TopicAnalysisLlmResponse",
            TopicAnalysisLlmResponse.serializer().descriptor.serialName,
        )
        assertEquals(
            "com.homeassistant.nlp.topicanalysis.TopicLlmResponse",
            TopicLlmResponse.serializer().descriptor.serialName,
        )
        assertEquals(
            "com.homeassistant.nlp.topicanalysis.TopicClaimLlmResponse",
            TopicClaimLlmResponse.serializer().descriptor.serialName,
        )
    }

    private fun serviceFor(response: String): LlmTopicAnalyzer =
        LlmTopicAnalyzer(StaticBackend(response))

    private fun singleRecordDocument(): SourceDocument =
        SourceDocument(
            sourceType = "kakao",
            sourceName = "2026-06-07.txt",
            records = listOf(SourceRecord("r1", 1, "동훈 | 오후 4:49 | 따랑해")),
        )

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
        system: String,
        messages: List<Message>,
        tools: List<Tool>,
        outputSchema: String,
    ): LlmResponse =
        LlmResponse.Text(response)
}

private class CapturingBackend(private val response: String) : LlmBackend {
    var system = ""
    lateinit var messages: List<Message>
    var outputSchema: String = ""

    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<Tool>,
        outputSchema: String,
    ): LlmResponse {
        this.system = system
        this.messages = messages
        this.outputSchema = outputSchema
        return LlmResponse.Text(response)
    }
}
