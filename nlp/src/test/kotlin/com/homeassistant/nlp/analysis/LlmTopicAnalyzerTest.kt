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
import com.homeassistant.nlp.topicanalysis.impl.TopicAnalysisLlmResponse
import com.homeassistant.nlp.topicanalysis.impl.TopicAnalysisOutputContract
import com.homeassistant.nlp.topicanalysis.impl.TopicClaimLlmResponse
import com.homeassistant.nlp.topicanalysis.impl.TopicLlmResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
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
    fun `long documents are analyzed by chunk then merged`() = runBlocking {
        val backend = RecordingBackend(
            listOf(
                topicJson(title = "가족 병원 일정", evidenceRecordIds = """["r1"]""", claimEvidenceRecordIds = """["r1"]"""),
                topicJson(title = "가족 병원 일정", evidenceRecordIds = """["r201"]""", claimEvidenceRecordIds = """["r201"]"""),
                topicJson(
                    title = "가족 병원 일정",
                    evidenceRecordIds = """["r1", "r201"]""",
                    claimEvidenceRecordIds = """["r1", "r201"]""",
                ),
            ),
        )
        val service = LlmTopicAnalyzer(backend)

        val result = service.analyze(documentWithRecords(201))

        assertEquals(3, backend.calls.size)
        assertContains(backend.calls[0].messageContent, "r1")
        assertContains(backend.calls[0].messageContent, "r200")
        assertFalse(backend.calls[0].messageContent.contains("r201"))
        assertContains(backend.calls[1].messageContent, "r201")
        assertFalse(backend.calls[1].messageContent.contains("r200"))
        assertContains(backend.calls[2].messageContent, "가족 병원 일정")
        assertContains(backend.calls[2].messageContent, "r1")
        assertContains(backend.calls[2].messageContent, "r201")
        assertEquals(1, result.topics.size)
        assertEquals(listOf(1, 201), result.topics.single().evidence.map { it.ref })
    }

    @Test
    fun `long document chunks are analyzed concurrently before merge`() = runBlocking {
        val activeChunkCalls = AtomicInteger(0)
        val maxActiveChunkCalls = AtomicInteger(0)
        val backend = object : LlmBackend {
            override suspend fun complete(
                system: String,
                messages: List<Message>,
                tools: List<Tool>,
                outputSchema: String,
            ): LlmResponse {
                val message = messages.single().content
                if (message.contains("기록 ")) {
                    val active = activeChunkCalls.incrementAndGet()
                    maxActiveChunkCalls.updateAndGet { current -> maxOf(current, active) }
                    delay(200)
                    activeChunkCalls.decrementAndGet()
                    val firstRecordId = Regex("""r\d+""").find(message)?.value ?: "r1"
                    return LlmResponse.Text(
                        topicJson(
                            title = "chunk $firstRecordId",
                            evidenceRecordIds = """["$firstRecordId"]""",
                            claimEvidenceRecordIds = """["$firstRecordId"]""",
                        ),
                    )
                }

                return LlmResponse.Text(
                    topicJson(
                        title = "merged",
                        evidenceRecordIds = """["r1", "r201", "r401"]""",
                        claimEvidenceRecordIds = """["r1", "r201", "r401"]""",
                    ),
                )
            }
        }
        val service = LlmTopicAnalyzer(backend)

        service.analyze(documentWithRecords(401))

        assertTrue(maxActiveChunkCalls.get() > 1, "expected overlapping chunk analysis calls")
    }

    @Test
    fun `chunk prompt includes topic evidence and claim limits`() = runBlocking {
        val backend = RecordingBackend(listOf("""{"topics":[]}""", """{"topics":[]}""", """{"topics":[]}"""))
        val service = LlmTopicAnalyzer(backend)

        service.analyze(documentWithRecords(201))

        assertContains(backend.calls.first().system, "최대 5개")
        assertContains(backend.calls.first().system, "evidenceRecordIds는 topic당 최대 5개")
        assertContains(backend.calls.first().system, "claims는 topic당 최대 3개")
    }

    @Test
    fun `merge prompt asks to merge interrupted topics and limits final topics`() = runBlocking {
        val backend = RecordingBackend(listOf("""{"topics":[]}""", """{"topics":[]}""", """{"topics":[]}"""))
        val service = LlmTopicAnalyzer(backend)

        service.analyze(documentWithRecords(201))

        val mergeSystem = backend.calls.last().system
        assertContains(mergeSystem, "시간상 떨어져")
        assertContains(mergeSystem, "같은 주제")
        assertContains(mergeSystem, "병합")
        assertContains(mergeSystem, "최종 최대 20개")
    }

    @Test
    fun `merge evidence ids are validated against original source records`() = runBlocking {
        val backend = RecordingBackend(
            listOf(
                topicJson(title = "가족 병원 일정", evidenceRecordIds = """["r1"]""", claimEvidenceRecordIds = """["r1"]"""),
                topicJson(title = "가족 병원 일정", evidenceRecordIds = """["r201"]""", claimEvidenceRecordIds = """["r201"]"""),
                topicJson(
                    title = "가족 병원 일정",
                    evidenceRecordIds = """["missing"]""",
                    claimEvidenceRecordIds = """["missing"]""",
                ),
            ),
        )
        val service = LlmTopicAnalyzer(backend)

        assertFailsWith<TopicAnalysisException> {
            service.analyze(documentWithRecords(201))
        }
    }

    @Test
    fun `short documents use single analysis without merge`() = runBlocking {
        val backend = RecordingBackend(listOf("""{"topics":[]}"""))
        val service = LlmTopicAnalyzer(backend)

        service.analyze(documentWithRecords(200))

        assertEquals(1, backend.calls.size)
        assertContains(backend.calls.single().messageContent, "r1")
        assertContains(backend.calls.single().messageContent, "r200")
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
    fun `topic analysis output schema includes field descriptions`() {
        val schema = TopicAnalysisOutputContract.schema

        assertContains(schema, "Topic candidates extracted from the source document")
        assertContains(schema, "Short review-facing title for one grouped household memory topic")
        assertContains(schema, "Concise summary of why the grouped records belong together")
        assertContains(schema, "Allowed MemoryType enum values represented by this topic")
        assertContains(schema, "Free-form household domain tags such as housing, moving, travel, food, or finance")
        assertContains(schema, "Source record ids that support this topic")
        assertContains(schema, "Evidence-backed atomic claims under this topic")
        assertContains(schema, "Atomic memory statement supported by the cited evidence")
        assertContains(schema, "Person, place, object, family member, or household entity the claim is about")
        assertContains(schema, "Allowed MemoryType enum value for this single claim")
        assertContains(schema, "How directly the source evidence supports this claim")
        assertContains(schema, "Source record ids that support this claim")
    }

    @Test
    fun `topic analysis output schema inlines referenced definitions`() {
        val schema = TopicAnalysisOutputContract.schema

        assertFalse(schema.contains("\"${'$'}ref\""))
        assertFalse(schema.contains("\"${'$'}defs\""))
        assertContains(schema, "\"memoryType\"")
        assertContains(schema, "\"enum\"")
        assertContains(schema, "\"STATE\"")
        assertContains(schema, "Allowed MemoryType enum value for this single claim")
        assertContains(schema, "How directly the source evidence supports this claim")
    }

    @Test
    fun `prints topic analysis output schema`() {
        println(TopicAnalysisOutputContract.schema)
    }

    @Test
    fun `topic analysis dto names come from class names without serial name overrides`() {
        assertEquals(
            "com.homeassistant.nlp.topicanalysis.impl.TopicAnalysisLlmResponse",
            TopicAnalysisLlmResponse.serializer().descriptor.serialName,
        )
        assertEquals(
            "com.homeassistant.nlp.topicanalysis.impl.TopicLlmResponse",
            TopicLlmResponse.serializer().descriptor.serialName,
        )
        assertEquals(
            "com.homeassistant.nlp.topicanalysis.impl.TopicClaimLlmResponse",
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

    private fun documentWithRecords(count: Int): SourceDocument =
        SourceDocument(
            sourceType = "kakao",
            sourceName = "2026-06-07.txt",
            records = (1..count).map { index ->
                SourceRecord("r$index", index, "동훈 | 오후 4:49 | 기록 $index")
            },
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

private data class BackendCall(
    val system: String,
    val messageContent: String,
    val outputSchema: String,
)

private class RecordingBackend(responses: List<String>) : LlmBackend {
    private val responses = ArrayDeque(responses)
    val calls = mutableListOf<BackendCall>()

    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<Tool>,
        outputSchema: String,
    ): LlmResponse {
        calls += BackendCall(
            system = system,
            messageContent = messages.single().content,
            outputSchema = outputSchema,
        )
        return LlmResponse.Text(responses.removeFirst())
    }
}
