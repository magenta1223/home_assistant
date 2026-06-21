package com.homeassistant.app.routes

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.domain.topicanalysis.ClaimCertainty
import com.homeassistant.domain.topicanalysis.NewTopicCandidate
import com.homeassistant.domain.topicanalysis.NewTopicCandidateClaim
import com.homeassistant.domain.topicanalysis.TopicClaim
import com.homeassistant.domain.topicanalysis.TopicCandidate
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
        assertContains(response.bodyAsText(), "PENDING")
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
        assertContains(response.bodyAsText(), "importedMessageCount")
        assertContains(response.bodyAsText(), "PENDING")
    }
}

private object FakeAnalyzer : KakaoImportAnalyzeUseCase {
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

    override suspend fun previewAnalysis(
        sourceFileName: String,
        text: String,
    ): KakaoImportAnalyzeResult {
        this.sourceFileName = sourceFileName
        this.text = text
        previewCalls += 1
        return KakaoImportAnalyzeResult(
            previewId = "preview-1",
            importedMessageCount = 1,
            topics = listOf(newTopic(sourceFileName, 1)),
        )
    }

    override suspend fun savePreview(previewId: String): KakaoImportSaveResult {
        this.previewId = previewId
        saveCalls += 1
        if (previewId == "missing") throw KakaoAnalysisPreviewNotFoundException(previewId)
        return KakaoImportSaveResult(topics = listOf(topic("2026-06-07.txt", 11)))
    }

    private fun newTopic(sourceFileName: String, evidenceRef: Int) =
        NewTopicCandidate(
            sourceType = "kakao",
            sourceName = sourceFileName,
            title = "관계 표현",
            summary = "애정 표현을 주고받았다.",
            memoryTypes = listOf(MemoryType.STATE),
            domains = listOf("relationship"),
            evidenceRefs = listOf(evidenceRef),
            claims = listOf(
                NewTopicCandidateClaim(
                    text = "동훈은 애정 표현을 했다.",
                    subject = "동훈",
                    memoryType = MemoryType.STATE,
                    certainty = ClaimCertainty.OBSERVED,
                    evidenceRefs = listOf(evidenceRef),
                ),
            ),
        )

    private fun topic(sourceFileName: String, evidenceRef: Int) =
        TopicCandidate(
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
