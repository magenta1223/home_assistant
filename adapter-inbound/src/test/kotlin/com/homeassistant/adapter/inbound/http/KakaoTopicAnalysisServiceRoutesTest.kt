package com.homeassistant.adapter.inbound.http

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class KakaoTopicAnalysisServiceRoutesTest {
    @Test
    fun `import analyze route requires bearer authentication`() = testApplication {
        FakeAnalyzer.reset()
        application {
            install(ContentNegotiation) { json() }
            configureRoutes(FakeAnalyzer, httpApiKeys = TEST_HTTP_API_KEYS)
        }

        val response = client.post("/api/kakao/import/analyze") {
            contentType(ContentType.Application.Json)
            setBody("""{"sourceName":"2026-06-07.txt","text":"[동훈] [오후 4:49] 따랑해"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0, FakeAnalyzer.analysisCalls)
    }

    @Test
    fun `import analyze route derives user from bearer token and returns saved topics`() = testApplication {
        FakeAnalyzer.reset()
        application {
            install(ContentNegotiation) { json() }
            configureRoutes(FakeAnalyzer, httpApiKeys = TEST_HTTP_API_KEYS)
        }

        val response = client.post("/api/kakao/import/analyze") {
            authenticateAsTestUser()
            contentType(ContentType.Application.Json)
            setBody("""{"sourceName":"2026-06-07.txt","text":"[동훈] [오후 4:49] 따랑해"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "관계 표현")
        assertContains(response.bodyAsText(), "동훈은 애정 표현을 했다.")
        assertContains(response.bodyAsText(), "memoryType")
        assertContains(response.bodyAsText(), "evidenceRefs")
        assertContains(response.bodyAsText(), "STATE")
        assertContains(response.bodyAsText(), "OBSERVED")
        assertEquals("dad", FakeAnalyzer.lastUserId)
        assertEquals("2026-06-07.txt", FakeAnalyzer.sourceFileName)
        assertEquals("동훈 | 오후 4:49 | 따랑해", FakeAnalyzer.text)
        assertEquals(1, FakeAnalyzer.analysisCalls)
    }

    @Test
    fun `import analyze route returns conflict when all messages already exist`() = testApplication {
        FakeAnalyzer.reset()
        application {
            install(ContentNegotiation) { json() }
            configureRoutes(FakeAnalyzer, httpApiKeys = TEST_HTTP_API_KEYS)
        }

        val response = client.post("/api/kakao/import/analyze") {
            authenticateAsTestUser()
            contentType(ContentType.Application.Json)
            setBody("""{"sourceName":"duplicate.txt","text":"duplicate"}""")
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertContains(response.bodyAsText(), "already been analyzed")
    }

    @Test
    fun `import analyze route rejects server file path input`() = testApplication {
        FakeAnalyzer.reset()
        application {
            install(ContentNegotiation) { json() }
            configureRoutes(FakeAnalyzer, httpApiKeys = TEST_HTTP_API_KEYS)
        }

        val response = client.post("/api/kakao/import/analyze") {
            authenticateAsTestUser()
            contentType(ContentType.Application.Json)
            setBody("""{"filePath":"settings.gradle.kts"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, FakeAnalyzer.analysisCalls)
    }

    @Test
    fun `import analyze route rejects legacy file name input`() = testApplication {
        FakeAnalyzer.reset()
        application {
            install(ContentNegotiation) { json() }
            configureRoutes(FakeAnalyzer, httpApiKeys = TEST_HTTP_API_KEYS)
        }

        val response = client.post("/api/kakao/import/analyze") {
            authenticateAsTestUser()
            contentType(ContentType.Application.Json)
            setBody("""{"fileName":"2026-06-07.txt","text":"[동훈] [오후 4:49] 따랑해"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, FakeAnalyzer.analysisCalls)
    }

    @Test
    fun `import save route is no longer exposed`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            configureRoutes(FakeAnalyzer, httpApiKeys = TEST_HTTP_API_KEYS)
        }

        val response = client.post("/api/kakao/import/save") {
            authenticateAsTestUser()
            contentType(ContentType.Application.Json)
            setBody("""{"previewId":"preview-1"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `test topic analysis route is not exposed`() = testApplication {
        FakeAnalyzer.reset()
        application {
            install(ContentNegotiation) { json() }
            configureRoutes(FakeAnalyzer, httpApiKeys = TEST_HTTP_API_KEYS)
        }

        val response = client.get("/api/test/topic-analysis/kakao-small-set")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(0, FakeAnalyzer.analysisCalls)
    }

    @Test
    fun `model eval route is not exposed`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            configureRoutes(FakeAnalyzer, httpApiKeys = TEST_HTTP_API_KEYS)
        }

        val response = client.post("/api/test/topic-analysis/openrouter-model-eval") {
            authenticateAsTestUser()
            contentType(ContentType.Application.Json)
            setBody("""{"models":["z-ai/glm-5.2","qwen/qwen3.7-max"]}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
