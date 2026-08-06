package com.homeassistant.adapter.outbound.codex

import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.domain.topicanalysis.TopicAnalysisException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class CodexTopicExtractorTest {
    @Test
    fun `analyzes datasource agnostic records into pending topics with evidence`() = runBlocking {
        val service = CodexTopicExtractor(
            StaticClient(
                """
                {
                  "topics": [
                    {
                      "title": "카인드커피에서 만나기",
                      "summary": "카인드커피 위치를 공유하고 그곳으로 오라고 말했다.",
                      "memoryTypes": ["EVENT", "LOCATION"],
                      "categories": ["location", "home"],
                      "evidenceRecordIds": ["r2", "r3"],
                      "memories": [
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
                    sourceRecord(1, "동훈 | 오후 4:49 | 따랑해"),
                    sourceRecord(2, "홍승민 | 오후 5:38 | [네이버지도]\n카인드커피"),
                    sourceRecord(3, "홍승민 | 오후 5:38 | 여기루 와용 ㅎㅎ"),
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
        assertEquals(setOf("location", "home"), result.topics.single().categories.toSet())
        assertEquals(listOf(2, 3), result.topics.single().evidence.map { it.id })
        assertEquals("홍승민은 카인드커피로 오라고 말했다.", result.topics.single().memories.single().text)
        assertEquals(
            MemoryType.EVENT,
            result.topics.single().memories.single().memoryType,
        )
        assertEquals(MemoryCertainty.SAID, result.topics.single().memories.single().certainty)
        assertEquals(listOf(2, 3), result.topics.single().memories.single().evidence.map { it.id })
    }

    @Test
    fun `prompt tells model not to split topics by time or interrupted order`() = runBlocking {
        val backend = CapturingClient("""{"topics":[]}""")
        val service = CodexTopicExtractor(backend)

        service.analyze(
            SourceDocument(
                sourceType = "kakao",
                sourceName = "2026-06-07.txt",
                records = listOf(
                    sourceRecord(1, "병원 예약 내일이지?"),
                    sourceRecord(2, "카페도 들르자"),
                    sourceRecord(3, "병원 갈 때 아기수첩 챙길게"),
                ),
            ),
        )

        assertContains(backend.system, "시간 간격으로 나누지 마세요")
        assertContains(backend.system, "A-B-A")
        assertContains(backend.userMessage, "r1")
        assertContains(backend.userMessage, "r3")
        assertContains(backend.outputSchema, "memories")
        assertContains(backend.outputSchema, "memoryTypes")
        assertContains(backend.outputSchema, "memoryType")
        assertContains(backend.outputSchema, "certainty")
    }

    @Test
    fun `prompt is generated from topic output schema`() = runBlocking {
        val backend = CapturingClient("""{"topics":[]}""")
        val service = CodexTopicExtractor(backend)

        service.analyze(singleRecordDocument())

        assertContains(backend.system, TopicAnalysisOutputContract.schema)
        assertFalse(backend.system.contains("memoryKind"))
        assertFalse(backend.system.contains("memorySubtype"))
        assertFalse(backend.system.contains("JSON만 반환하세요"))
    }

    @Test
    fun `long documents are analyzed by chunk then merged`() = runBlocking {
        val backend = RecordingClient(
            listOf(
                topicJson(title = "가족 병원 일정", evidenceRecordIds = """["r1"]""", memoryEvidenceRecordIds = """["r1"]"""),
                topicJson(title = "가족 병원 일정", evidenceRecordIds = """["r201"]""", memoryEvidenceRecordIds = """["r201"]"""),
                topicJson(
                    title = "가족 병원 일정",
                    evidenceRecordIds = """["r1", "r201"]""",
                    memoryEvidenceRecordIds = """["r1", "r201"]""",
                ),
            ),
        )
        val service = CodexTopicExtractor(backend)

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
        assertEquals(listOf(1, 201), result.topics.single().evidence.map { it.id })
    }

    @Test
    fun `long document chunks are analyzed concurrently before merge`() = runBlocking {
        val activeChunkCalls = AtomicInteger(0)
        val maxActiveChunkCalls = AtomicInteger(0)
        val backend = object : CodexCompletionClient {
            override suspend fun complete(
                system: String,
                userMessage: String,
                outputSchema: String,
            ): String {
                val message = userMessage
                if (message.contains("기록 ")) {
                    val active = activeChunkCalls.incrementAndGet()
                    maxActiveChunkCalls.updateAndGet { current -> maxOf(current, active) }
                    delay(200)
                    activeChunkCalls.decrementAndGet()
                    val firstRecordId = Regex("""r\d+""").find(message)?.value ?: "r1"
                    return topicJson(
                        title = "chunk $firstRecordId",
                        evidenceRecordIds = """["$firstRecordId"]""",
                        memoryEvidenceRecordIds = """["$firstRecordId"]""",
                    )
                }

                return topicJson(
                    title = "merged",
                    evidenceRecordIds = """["r1", "r201", "r401"]""",
                    memoryEvidenceRecordIds = """["r1", "r201", "r401"]""",
                )
            }
        }
        val service = CodexTopicExtractor(backend)

        service.analyze(documentWithRecords(401))

        assertTrue(maxActiveChunkCalls.get() > 1, "expected overlapping chunk analysis calls")
    }

    @Test
    fun `chunk prompt has no topic count limit and keeps evidence and claim limits`() = runBlocking {
        val backend = RecordingClient(listOf("""{"topics":[]}""", """{"topics":[]}""", """{"topics":[]}"""))
        val service = CodexTopicExtractor(backend)

        service.analyze(documentWithRecords(201))

        assertFalse(backend.calls.first().system.contains("후보 topic은 최대"))
        assertContains(backend.calls.first().system, "evidenceRecordIds는 topic당 최대 5개")
        assertContains(backend.calls.first().system, "memories는 topic당 최대 3개")
        assertContains(backend.calls.first().system, "한 번만 짧게 언급된 정보")
        assertContains(backend.calls.first().system, "누락되지 않았는지 다시 점검")
        assertContains(backend.calls.first().system, "최대 200 records씩 내부 검토 구간")
        assertContains(backend.calls.first().system, "독립 topic은 다음 조건을 모두 만족")
    }

    @Test
    fun `merge prompt preserves reusable topics without a final topic limit`() = runBlocking {
        val backend = RecordingClient(listOf("""{"topics":[]}""", """{"topics":[]}""", """{"topics":[]}"""))
        val service = CodexTopicExtractor(backend)

        service.analyze(documentWithRecords(201))

        val mergeSystem = backend.calls.last().system
        assertContains(mergeSystem, "시간상 떨어져")
        assertContains(mergeSystem, "같은 주제")
        assertContains(mergeSystem, "병합")
        assertFalse(mergeSystem.contains("최종 최대 20개"))
        assertContains(mergeSystem, "저빈도 topic")
        assertContains(mergeSystem, "일부 후보를 버리지 마세요")
    }

    @Test
    fun `merge evidence ids are validated against original source records`() = runBlocking {
        val backend = RecordingClient(
            listOf(
                topicJson(title = "가족 병원 일정", evidenceRecordIds = """["r1"]""", memoryEvidenceRecordIds = """["r1"]"""),
                topicJson(title = "가족 병원 일정", evidenceRecordIds = """["r201"]""", memoryEvidenceRecordIds = """["r201"]"""),
                topicJson(
                    title = "가족 병원 일정",
                    evidenceRecordIds = """["missing"]""",
                    memoryEvidenceRecordIds = """["missing"]""",
                ),
            ),
        )
        val service = CodexTopicExtractor(backend)

        assertFailsWith<TopicAnalysisException> {
            service.analyze(documentWithRecords(201))
        }
    }

    @Test
    fun `short documents use single analysis without merge`() = runBlocking {
        val backend = RecordingClient(listOf("""{"topics":[]}"""))
        val service = CodexTopicExtractor(backend)

        service.analyze(documentWithRecords(200))

        assertEquals(1, backend.calls.size)
        assertContains(backend.calls.single().messageContent, "r1")
        assertContains(backend.calls.single().messageContent, "r200")
    }

}
