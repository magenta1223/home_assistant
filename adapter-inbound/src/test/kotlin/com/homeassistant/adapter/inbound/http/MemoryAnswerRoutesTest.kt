package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.memory.search.MemorySearchMatch
import com.homeassistant.application.memory.answer.MemoryAnswerRequest
import com.homeassistant.application.memory.answer.MemoryAnswerResult
import com.homeassistant.application.memory.answer.AnswerFromMemoriesUseCase
import com.homeassistant.application.memory.search.MemorySearchUnavailableException
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisRequest
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisResult
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysis
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSaveResult
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSaveRequest
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSelectionSaveRequest
import com.homeassistant.application.topicanalysis.save.SaveAnalyzedTopicsUseCase
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class MemoryAnswerRoutesTest {
    @Test
    fun `memory answer route returns answer from approved memories`() = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            configureRoutes(
                topicAnalysis = UnusedTopicAnalysis,
                saveAnalyzedTopics = UnusedTopicAnalysis,
                memoryAnswer = FakeMemoryAnswer,
                httpApiKeys = TEST_HTTP_API_KEYS,
            )
        }

        val response = client.post("/api/memories/answer") {
            authenticateAsTestUser()
            contentType(ContentType.Application.Json)
            setBody("""{"question":"리모컨 어디 있어?","limit":5}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "저장된 기억 기준으로는 리모컨은 벽장 제일 위칸에 있다.")
        assertContains(body, "\"matches\"")
    }

    @Test
    fun `memory answer route rejects blank question`() = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            configureRoutes(
                topicAnalysis = UnusedTopicAnalysis,
                saveAnalyzedTopics = UnusedTopicAnalysis,
                memoryAnswer = FakeMemoryAnswer,
                httpApiKeys = TEST_HTTP_API_KEYS,
            )
        }

        val response = client.post("/api/memories/answer") {
            authenticateAsTestUser()
            contentType(ContentType.Application.Json)
            setBody("""{"question":"   ","limit":5}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `memory answer route returns service unavailable when vector index is not configured`() = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            configureRoutes(
                topicAnalysis = UnusedTopicAnalysis,
                saveAnalyzedTopics = UnusedTopicAnalysis,
                memoryAnswer = UnavailableMemoryAnswer,
                httpApiKeys = TEST_HTTP_API_KEYS,
            )
        }

        val response = client.post("/api/memories/answer") {
            authenticateAsTestUser()
            contentType(ContentType.Application.Json)
            setBody("""{"question":"리모컨 어디 있어?","limit":5}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertContains(response.bodyAsText(), "memory vector index is not configured")
    }
}

private object FakeMemoryAnswer : AnswerFromMemoriesUseCase {
    override fun answer(request: MemoryAnswerRequest): MemoryAnswerResult =
        MemoryAnswerResult(
            question = request.question.trim(),
            answer = "저장된 기억 기준으로는 리모컨은 벽장 제일 위칸에 있다.",
            matches = listOf(
                MemorySearchMatch(
                    memoryId = 11,
                    topicId = 1,
                    topicTitle = "집 물건 위치",
                    topicSummary = "리모컨 위치",
                    content = "리모컨은 벽장 제일 위칸에 있다.",
                    evidenceRefs = listOf(10),
                ),
            ),
        )
}

private object UnavailableMemoryAnswer : AnswerFromMemoriesUseCase {
    override fun answer(request: MemoryAnswerRequest): MemoryAnswerResult =
        throw MemorySearchUnavailableException("memory vector index is not configured")
}

private object UnusedTopicAnalysis : TopicAnalysis, SaveAnalyzedTopicsUseCase {
    override suspend fun execute(request: TopicAnalysisRequest): TopicAnalysisResult =
        error("not used")

    override fun saveAll(request: TopicAnalysisSaveRequest): TopicAnalysisSaveResult =
        error("not used")

    override fun saveSelected(request: TopicAnalysisSelectionSaveRequest): TopicAnalysisSaveResult =
        error("not used")
}
