package com.homeassistant.app.routes

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.datamodel.topicanalysis.ClaimCertainty
import com.homeassistant.datamodel.topicanalysis.Topic
import com.homeassistant.datamodel.topicanalysis.TopicCandidate
import com.homeassistant.datamodel.topicanalysis.TopicClaim
import com.homeassistant.datamodel.topicanalysis.TopicClaimCandidate
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisRequest
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisResult
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisSaveResult
import com.homeassistant.nlp.topicanalysis.api.TopicAnalysisUseCase
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.ktor.server.application.install
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class KakaoImportRoutesTest {
    @Test
    fun `import analyze route previews request text and returns preview id`() = testApplication {
        FakeAnalyzer.reset()
        application {
            install(ContentNegotiation) {
                json()
            }
            configureRoutes(FakeAnalyzer)
        }

        val response = client.post("/api/kakao/import/analyze") {
            contentType(ContentType.Application.Json)
            setBody("""{"fileName":"2026-06-07.txt","text":"[동훈] [오후 4:49] 따랑해"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "관계 표현")
        assertContains(response.bodyAsText(), "동훈은 애정 표현을 했다.")
        assertContains(response.bodyAsText(), "memoryType")
        assertContains(response.bodyAsText(), "previewId")
        assertContains(response.bodyAsText(), "preview-1")
        assertContains(response.bodyAsText(), "evidenceRefs")
        assertContains(response.bodyAsText(), "STATE")
        assertContains(response.bodyAsText(), "OBSERVED")
        assertEquals("2026-06-07.txt", FakeAnalyzer.sourceFileName)
        assertEquals("[동훈] [오후 4:49] 따랑해", FakeAnalyzer.text)
        assertEquals(1, FakeAnalyzer.previewCalls)
        assertEquals(0, FakeAnalyzer.saveCalls)
    }

    @Test
    fun `import save route persists a preview id`() = testApplication {
        FakeAnalyzer.reset()
        application {
            install(ContentNegotiation) {
                json()
            }
            configureRoutes(FakeAnalyzer)
        }

        val response = client.post("/api/kakao/import/save") {
            contentType(ContentType.Application.Json)
            setBody("""{"previewId":"preview-1"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "관계 표현")
        assertContains(response.bodyAsText(), "evidenceRefs")
        assertEquals("preview-1", FakeAnalyzer.previewId)
        assertEquals(1, FakeAnalyzer.saveCalls)
    }

    @Test
    fun `import save route returns bad request for blank preview id`() = testApplication {
        FakeAnalyzer.reset()
        application {
            install(ContentNegotiation) {
                json()
            }
            configureRoutes(FakeAnalyzer)
        }

        val response = client.post("/api/kakao/import/save") {
            contentType(ContentType.Application.Json)
            setBody("""{"previewId":"   "}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `import save route returns not found for unknown preview id`() = testApplication {
        FakeAnalyzer.reset()
        application {
            install(ContentNegotiation) {
                json()
            }
            configureRoutes(FakeAnalyzer)
        }

        val response = client.post("/api/kakao/import/save") {
            contentType(ContentType.Application.Json)
            setBody("""{"previewId":"missing"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `test topic analysis route analyzes bundled small kakao conversation`() = testApplication {
        FakeAnalyzer.reset()
        application {
            install(ContentNegotiation) {
                json()
            }
            configureRoutes(FakeAnalyzer)
        }

        val response = client.get("/api/test/topic-analysis/kakao-small-set")

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(FakeAnalyzer.sourceFileName, "topic-analysis-small-kakao")
        assertContains(FakeAnalyzer.text, "2026년 3월 15일 오후 1:58, 동훈 : 우리은행 1002266102280")
        assertContains(FakeAnalyzer.text, "관리사무소 질문 리스트")
        assertContains(response.bodyAsText(), "관계 표현")
        assertContains(response.bodyAsText(), "importedRecordCount")
    }
}

private object FakeAnalyzer : TopicAnalysisUseCase {
    var sourceFileName = ""
    var text = ""
    var previewId = ""
    var previewCalls = 0
    var saveCalls = 0

    fun reset() {
        sourceFileName = ""
        text = ""
        previewId = ""
        previewCalls = 0
        saveCalls = 0
    }

    override suspend fun analyze(request: TopicAnalysisRequest): TopicAnalysisResult {
        this.sourceFileName = request.sourceName
        this.text = request.text
        previewCalls += 1
        return TopicAnalysisResult(
            previewId = "preview-1",
            sourceType = request.sourceType,
            sourceName = request.sourceName,
            importedRecordCount = 1,
            topics = listOf(newTopic(request.sourceName, 1)),
        )
    }

    override suspend fun saveAnalysis(previewId: String): TopicAnalysisSaveResult {
        this.previewId = previewId
        saveCalls += 1
        if (previewId == "missing") throw IllegalArgumentException(previewId)
        return TopicAnalysisSaveResult(previewId = previewId, topics = listOf(topic("2026-06-07.txt", 11)))
    }

    private fun newTopic(sourceFileName: String, evidenceRef: Int) =
        TopicCandidate(
            sourceType = "kakao",
            sourceName = sourceFileName,
            title = "관계 표현",
            summary = "애정 표현을 주고받았다.",
            memoryTypes = listOf(MemoryType.STATE),
            domains = listOf("relationship"),
            evidenceRefs = listOf(evidenceRef),
            claims = listOf(
                TopicClaimCandidate(
                    text = "동훈은 애정 표현을 했다.",
                    subject = "동훈",
                    memoryType = MemoryType.STATE,
                    certainty = ClaimCertainty.OBSERVED,
                    evidenceRefs = listOf(evidenceRef),
                ),
            ),
        )

    private fun topic(sourceFileName: String, evidenceRef: Int) =
        Topic(
            id = 7,
            sourceType = "kakao",
            sourceName = sourceFileName,
            title = "관계 표현",
            summary = "애정 표현을 주고받았다.",
            memoryTypes = listOf(MemoryType.STATE),
            domains = listOf("relationship"),
            evidenceRefs = listOf(evidenceRef),
            claims = listOf(
                TopicClaim(
                    id = 8,
                    text = "동훈은 애정 표현을 했다.",
                    subject = "동훈",
                    memoryType = MemoryType.STATE,
                    certainty = ClaimCertainty.OBSERVED,
                    evidenceRefs = listOf(evidenceRef),
                ),
            ),
            status = CandidateStatus.PENDING,
        )
}
