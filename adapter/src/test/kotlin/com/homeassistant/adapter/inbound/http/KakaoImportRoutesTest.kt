package com.homeassistant.adapter.inbound.http

import com.homeassistant.domain.memory.CandidateStatus
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.topicanalysis.ClaimCertainty
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicCandidate
import com.homeassistant.domain.topicanalysis.TopicClaim
import com.homeassistant.domain.topicanalysis.TopicClaimCandidate
import com.homeassistant.application.topicanalysis.analyze.DuplicateKakaoMessagesException
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisRequest
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisResult
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSaveResult
import com.homeassistant.application.topicanalysis.save.TopicAnalysisPreviewNotFoundException
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSaveRequest
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
    fun `import analyze route returns conflict when all messages already exist`() = testApplication {
        FakeAnalyzer.reset()
        application {
            install(ContentNegotiation) {
                json()
            }
            configureRoutes(FakeAnalyzer, FakeAnalyzer)
        }

        val response = client.post("/api/kakao/import/analyze") {
            contentType(ContentType.Application.Json)
            setBody(scoped("""{"sourceName":"duplicate.txt","text":"duplicate"}"""))
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertContains(response.bodyAsText(), "already been analyzed")
    }

    @Test
    fun `import analyze route previews request text and returns preview id`() = testApplication {
        FakeAnalyzer.reset()
        application {
            install(ContentNegotiation) {
                json()
            }
            configureRoutes(FakeAnalyzer, FakeAnalyzer)
        }

        val response = client.post("/api/kakao/import/analyze") {
            contentType(ContentType.Application.Json)
            setBody(scoped("""{"sourceName":"2026-06-07.txt","text":"[동훈] [오후 4:49] 따랑해"}"""))
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
    fun `import analyze route rejects server file path input`() = testApplication {
        FakeAnalyzer.reset()
        application {
            install(ContentNegotiation) {
                json()
            }
            configureRoutes(FakeAnalyzer, FakeAnalyzer)
        }

        val response = client.post("/api/kakao/import/analyze") {
            contentType(ContentType.Application.Json)
            setBody("""{"filePath":"settings.gradle.kts"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, FakeAnalyzer.previewCalls)
    }

    @Test
    fun `import analyze route rejects legacy file name input`() = testApplication {
        FakeAnalyzer.reset()
        application {
            install(ContentNegotiation) {
                json()
            }
            configureRoutes(FakeAnalyzer, FakeAnalyzer)
        }

        val response = client.post("/api/kakao/import/analyze") {
            contentType(ContentType.Application.Json)
            setBody("""{"fileName":"2026-06-07.txt","text":"[동훈] [오후 4:49] 따랑해"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, FakeAnalyzer.previewCalls)
    }

    @Test
    fun `import save route persists a preview id`() = testApplication {
        FakeAnalyzer.reset()
        application {
            install(ContentNegotiation) {
                json()
            }
            configureRoutes(FakeAnalyzer, FakeAnalyzer)
        }

        val response = client.post("/api/kakao/import/save") {
            contentType(ContentType.Application.Json)
            setBody("""{"previewId":"preview-1","userId":"dad"}""")
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
            configureRoutes(FakeAnalyzer, FakeAnalyzer)
        }

        val response = client.post("/api/kakao/import/save") {
            contentType(ContentType.Application.Json)
            setBody("""{"previewId":"   ","userId":"dad"}""")
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
            configureRoutes(FakeAnalyzer, FakeAnalyzer)
        }

        val response = client.post("/api/kakao/import/save") {
            contentType(ContentType.Application.Json)
            setBody("""{"previewId":"missing","userId":"dad"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `import save route returns server error for unexpected failure`() = testApplication {
        FakeAnalyzer.reset()
        application {
            install(ContentNegotiation) {
                json()
            }
            configureRoutes(FakeAnalyzer, FakeAnalyzer)
        }

        val response = client.post("/api/kakao/import/save") {
            contentType(ContentType.Application.Json)
            setBody("""{"previewId":"broken","userId":"dad"}""")
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
    }

    @Test
    fun `test topic analysis route is not exposed`() = testApplication {
        FakeAnalyzer.reset()
        application {
            install(ContentNegotiation) {
                json()
            }
            configureRoutes(FakeAnalyzer, FakeAnalyzer)
        }

        val response = client.get("/api/test/topic-analysis/kakao-small-set")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(0, FakeAnalyzer.previewCalls)
    }

    @Test
    fun `model eval route is not exposed`() = testApplication {
        application {
            install(ContentNegotiation) {
                json()
            }
            configureRoutes(FakeAnalyzer, FakeAnalyzer)
        }

        val response = client.post("/api/test/topic-analysis/openrouter-model-eval") {
            contentType(ContentType.Application.Json)
            setBody("""{"models":["z-ai/glm-5.2","qwen/qwen3.7-max"]}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    private fun scoped(json: String): String =
        json.dropLast(1) + ""","userId":"dad"}"""
}
