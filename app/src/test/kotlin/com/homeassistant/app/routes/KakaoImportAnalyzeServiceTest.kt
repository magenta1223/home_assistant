package com.homeassistant.app.routes

import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.core.nlp.LlmResponse
import com.homeassistant.core.nlp.Message
import com.homeassistant.core.tools.Tool
import com.homeassistant.domain.db.tables.TopicAnalysisPreviewTable
import com.homeassistant.domain.db.tables.KakaoImportedMessageTable
import com.homeassistant.domain.db.tables.TopicCandidateTable
import com.homeassistant.domain.kakao.KakaoAnalysisPreviewRepository
import com.homeassistant.domain.kakao.KakaoImportService
import com.homeassistant.domain.kakao.KakaoMessageRepository
import com.homeassistant.domain.topicanalysis.NewTopicCandidate
import com.homeassistant.domain.topicanalysis.TopicAnalysisRepository
import com.homeassistant.nlp.topicanalysis.TopicAnalysisService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class KakaoImportAnalyzeServiceTest {
    private val dbUrl = "jdbc:sqlite:file:${UUID.randomUUID()}?mode=memory&cache=shared"
    private lateinit var keepAlive: java.sql.Connection
    private lateinit var db: Database
    private lateinit var backend: StaticBackend
    private lateinit var service: KakaoImportAnalyzeService

    @BeforeTest
    fun setup() {
        keepAlive = DriverManager.getConnection(dbUrl)
        db = Database.connect(dbUrl, driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(KakaoImportedMessageTable, TopicCandidateTable, TopicAnalysisPreviewTable)
        }
        backend = StaticBackend(topicResponse)
        service = KakaoImportAnalyzeService(
            KakaoImportService(KakaoMessageRepository(db)),
            TopicAnalysisService(backend),
            TopicAnalysisRepository(db),
            KakaoAnalysisPreviewRepository(db),
        )
    }

    @AfterTest
    fun teardown() {
        keepAlive.close()
    }

    @Test
    fun `preview stores draft with temporary refs and does not import messages`() = runBlocking {
        val preview = service.previewAnalysis("2026-06-07.txt", kakaoText)

        assertNotNull(preview.previewId)
        assertEquals(2, preview.importedMessageCount)
        val previewTopic: NewTopicCandidate = preview.topics.single()
        assertEquals(listOf(1), preview.topics.single().evidenceRefs)
        assertEquals(listOf(1), previewTopic.claims.single().evidenceRefs)
        assertEquals(1, backend.calls)
        assertEquals(0, countRows("kakao_imported_messages"))
        assertEquals(0, countRows("topic_candidates"))
        assertEquals(1, countRows("kakao_analysis_previews"))
    }

    @Test
    fun `save imports messages and persists topic candidates with stored message ids`() = runBlocking {
        KakaoImportService(KakaoMessageRepository(db)).import("existing.txt", "[시스템] [오전 1:00] existing")
        val preview = service.previewAnalysis("2026-06-07.txt", kakaoText)

        val saved = service.savePreview(preview.previewId)

        val savedTopic = saved.topics.single()
        assertEquals(1, backend.calls)
        assertEquals(3, countRows("kakao_imported_messages"))
        assertEquals(1, countRows("topic_candidates"))
        assertNotEquals(listOf(1), savedTopic.evidenceRefs)
        assertEquals(savedTopic.evidenceRefs, savedTopic.claims.single().evidenceRefs)
    }

    @Test
    fun `repeated save reuses existing topic`() = runBlocking {
        val preview = service.previewAnalysis("2026-06-07.txt", kakaoText)

        val first = service.savePreview(preview.previewId).topics.single()
        val second = service.savePreview(preview.previewId).topics.single()

        assertEquals(first.id, second.id)
        assertEquals(1, countRows("topic_candidates"))
    }

    private fun countRows(table: String): Int = transaction(db) {
        var count = 0
        exec("SELECT COUNT(*) FROM $table") { result ->
            result.next()
            count = result.getInt(1)
        }
        count
    }
}

private val kakaoText = """
    [동훈] [오후 4:49] 따랑해
    [홍승민] [오후 5:38] 여기루 와용 ㅎㅎ
""".trimIndent()

private val topicResponse = """
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
              "evidenceRecordIds": ["r1"]
            }
          ]
        }
      ]
    }
""".trimIndent()

private class StaticBackend(private val response: String) : LlmBackend {
    var calls = 0

    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<Tool>,
        outputSchema: String,
    ): LlmResponse {
        calls += 1
        return LlmResponse.Text(response)
    }
}
