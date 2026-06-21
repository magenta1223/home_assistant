package com.homeassistant.app.routes

import com.homeassistant.core.memory.CandidateStatus
import com.homeassistant.core.memory.MemoryType
import com.homeassistant.domain.topicanalysis.TopicClaim
import com.homeassistant.domain.topicanalysis.TopicCandidate
import com.homeassistant.nlp.topicanalysis.ClaimCertainty
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.get
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
    fun `import analyze route reads request text and returns pending topic`() = testApplication {
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
        assertContains(response.bodyAsText(), "STATE")
        assertContains(response.bodyAsText(), "OBSERVED")
        assertContains(response.bodyAsText(), "PENDING")
        assertEquals("2026-06-07.txt", FakeAnalyzer.sourceFileName)
        assertEquals("[동훈] [오후 4:49] 따랑해", FakeAnalyzer.text)
    }

    @Test
    fun `test topic analysis route analyzes bundled small kakao conversation`() = testApplication {
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

    override suspend fun importAndAnalyze(
        sourceFileName: String,
        text: String,
    ): KakaoImportAnalyzeResult {
        this.sourceFileName = sourceFileName
        this.text = text
        return KakaoImportAnalyzeResult(
            importedMessageCount = 1,
            topics = listOf(
                TopicCandidate(
                    id = 7,
                    sourceType = "kakao",
                    sourceName = sourceFileName,
                    title = "관계 표현",
                    summary = "애정 표현을 주고받았다.",
                    memoryTypes = listOf(MemoryType.STATE),
                    domains = listOf("relationship"),
                    evidenceRefs = listOf(1),
                    claims = listOf(
                        TopicClaim(
                            id = 8,
                            text = "동훈은 애정 표현을 했다.",
                            subject = "동훈",
                            memoryType = MemoryType.STATE,
                            certainty = ClaimCertainty.OBSERVED,
                            evidenceRefs = listOf(1),
                        ),
                    ),
                    status = CandidateStatus.PENDING,
                ),
            ),
        )
    }

    override suspend fun previewAnalysis(
        sourceFileName: String,
        text: String,
    ): KakaoImportAnalyzeResult {
        this.sourceFileName = sourceFileName
        this.text = text
        return importAndAnalyze(sourceFileName, text)
    }
}
