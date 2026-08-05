package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.topicanswer.answer.TopicAnswerMatch
import com.homeassistant.application.topicanswer.answer.TopicAnswerRequest
import com.homeassistant.application.topicanswer.answer.TopicAnswerResult
import com.homeassistant.application.topicanswer.answer.TopicAnswerUseCase
import com.homeassistant.application.topicanswer.answer.TopicClaimSearchIndexUnavailableException
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisRequest
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisResult
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSaveResult
import com.homeassistant.application.topicanalysis.TopicAnalysisUseCase
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSaveRequest
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

class TopicAnswerRoutesTest {
    @Test
    fun `topic answer route returns answer from approved topics`() = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            configureRoutes(
                kakaoImportAnalyze = UnusedTopicAnalysis,
                topicAnswer = FakeTopicAnswer,
            )
        }

        val response = client.post("/api/topics/answer") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"dad","familyId":"family-1","question":"리모컨 어디 있어?","limit":5}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "저장된 기억 기준으로는 리모컨은 벽장 제일 위칸에 있다.")
        assertContains(body, "\"matches\"")
    }

    @Test
    fun `topic answer route rejects blank question`() = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            configureRoutes(
                kakaoImportAnalyze = UnusedTopicAnalysis,
                topicAnswer = FakeTopicAnswer,
            )
        }

        val response = client.post("/api/topics/answer") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"dad","familyId":"family-1","question":"   ","limit":5}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `topic answer route returns service unavailable when vector index is not configured`() = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            configureRoutes(
                kakaoImportAnalyze = UnusedTopicAnalysis,
                topicAnswer = UnavailableTopicAnswer,
            )
        }

        val response = client.post("/api/topics/answer") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"dad","familyId":"family-1","question":"리모컨 어디 있어?","limit":5}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertContains(response.bodyAsText(), "topic claim vector index is not configured")
    }
}

private object FakeTopicAnswer : TopicAnswerUseCase {
    override fun answer(request: TopicAnswerRequest): TopicAnswerResult =
        TopicAnswerResult(
            question = request.question.trim(),
            answer = "저장된 기억 기준으로는 리모컨은 벽장 제일 위칸에 있다.",
            matches = listOf(
                TopicAnswerMatch(
                    topicId = 1,
                    title = "집 물건 위치",
                    summary = "리모컨 위치",
                    claims = listOf("리모컨은 벽장 제일 위칸에 있다."),
                    evidenceRefs = listOf(10),
                ),
            ),
        )
}

private object UnavailableTopicAnswer : TopicAnswerUseCase {
    override fun answer(request: TopicAnswerRequest): TopicAnswerResult =
        throw TopicClaimSearchIndexUnavailableException("topic claim vector index is not configured")
}

private object UnusedTopicAnalysis : TopicAnalysisUseCase {
    override suspend fun analyze(request: TopicAnalysisRequest): TopicAnalysisResult =
        error("not used")

    override suspend fun saveAnalysis(request: TopicAnalysisSaveRequest): TopicAnalysisSaveResult =
        error("not used")
}
