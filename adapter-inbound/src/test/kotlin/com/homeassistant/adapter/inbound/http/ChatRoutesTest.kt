package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.inbound.identity.UserRegistry
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysis
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisRequest
import com.homeassistant.application.port.inbound.chatservice.Chat
import com.homeassistant.application.port.inbound.chatservice.ChatRequest
import com.homeassistant.application.port.inbound.chatservice.ChatResult
import com.homeassistant.infrastructure.serialization.JsonSerializer
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import io.ktor.client.request.bearerAuth
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatRoutesTest {
    @Test
    fun `conversation page is hosted without authentication or embedded credentials`() = testApplication {
        application {
            configureTestRoutes(RecordingChat(ChatResult.AnswerReady("unused")))
        }

        val response = client.get("/conversation")
        val html = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(html.contains("Episode Conversation"))
        assertTrue(html.contains(AppConfig.ROUTE_EPISODE_CONVERSATION))
        assertTrue(html.contains("/assets/http-auth-session.js"))
        assertTrue(!html.contains("localStorage"))
        assertTrue(!html.contains("sessionStorage"))
    }

    @Test
    fun `authenticated user owns the HTTP conversation identity`() = testApplication {
        val conversation = RecordingChat(ChatResult.AnswerReady("기억 기반 답변"))
        application {
            configureTestRoutes(conversation)
        }

        val response = client.post(AppConfig.ROUTE_EPISODE_CONVERSATION) {
            bearerAuth(API_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(
                """{
                  "question":" 무엇을 기억하고 있어? ",
                  "userId":"intruder"
                }""".trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("기억 기반 답변"))
        val captured = conversation.requests.single()
        assertEquals(UserId("member-1"), captured.userId)
        assertEquals("무엇을 기억하고 있어?", captured.question)
    }

    @Test
    fun `different bearer tokens create different chat users`() = testApplication {
        val conversation = RecordingChat(ChatResult.AnswerReady("answer"))
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(
                episodeAnalysis = unusedEpisodeAnalysis(),
                httpApiKeys = mapOf(
                    HttpApiKeyConfig.hash(API_TOKEN) to UserId("member-1"),
                    HttpApiKeyConfig.hash(SECOND_API_TOKEN) to UserId("member-2"),
                ),
                users = FixedUserRegistry(
                    RegisteredUser(UserId("member-1"), "첫째"),
                    RegisteredUser(UserId("member-2"), "둘째"),
                ),
                chat = conversation,
            )
        }

        postConversation(API_TOKEN)
        postConversation(SECOND_API_TOKEN)

        assertEquals(listOf(UserId("member-1"), UserId("member-2")), conversation.requests.map { it.userId })
    }

    @Test
    fun `episode conversation requires authentication`() = testApplication {
        application {
            configureTestRoutes(RecordingChat(ChatResult.AnswerReady("unused")))
        }

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post(AppConfig.ROUTE_EPISODE_CONVERSATION) {
                contentType(ContentType.Application.Json)
                setBody("""{"question":"질문"}""")
            }.status,
        )
    }

    @Test
    fun `unavailable conversation runtime returns service unavailable`() = testApplication {
        application {
            configureTestRoutes(chat = null)
        }

        assertEquals(
            HttpStatusCode.ServiceUnavailable,
            postConversation(API_TOKEN).status,
        )
    }

    @Test
    fun `failed conversation returns service unavailable`() = testApplication {
        application {
            configureTestRoutes(RecordingChat(ChatResult.Failed))
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, postConversation(API_TOKEN).status)
    }

    @Test
    fun `blank question is rejected before conversation`() = testApplication {
        val conversation = RecordingChat(ChatResult.AnswerReady("unused"))
        application {
            configureTestRoutes(conversation)
        }

        val response = client.post(AppConfig.ROUTE_EPISODE_CONVERSATION) {
            bearerAuth(API_TOKEN)
            contentType(ContentType.Application.Json)
            setBody("""{"question":"  "}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(conversation.requests.isEmpty())
    }

    private fun io.ktor.server.application.Application.configureTestRoutes(
        chat: Chat?,
    ) {
        install(ContentNegotiation) { json(JsonSerializer.json) }
        configureRoutes(
            episodeAnalysis = unusedEpisodeAnalysis(),
            httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_TOKEN) to UserId("member-1")),
            users = FixedUserRegistry(RegisteredUser(UserId("member-1"), "첫째")),
            chat = chat,
        )
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.postConversation(
        token: String,
    ) =
        client.post(AppConfig.ROUTE_EPISODE_CONVERSATION) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"question":"질문"}""")
        }

    private class RecordingChat(
        private val result: ChatResult,
    ) : Chat {
        val requests = mutableListOf<ChatRequest>()

        override fun answer(request: ChatRequest): ChatResult {
            requests += request
            return result
        }

    }

    private class FixedUserRegistry(
        private vararg val users: RegisteredUser,
    ) : UserRegistry {
        override fun list(): List<RegisteredUser> = users.toList()
    }

    private fun unusedEpisodeAnalysis() = object : EpisodeAnalysis {
        override suspend fun execute(request: EpisodeAnalysisRequest) = error("unused")
    }

    private companion object {
        const val API_TOKEN = "test-token"
        const val SECOND_API_TOKEN = "second-test-token"
    }
}
