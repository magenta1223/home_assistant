package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.inbound.chatservice.ChatService
import com.homeassistant.application.port.inbound.chatservice.ChatParticipant
import com.homeassistant.application.port.inbound.chatservice.ChatRequest
import com.homeassistant.application.port.inbound.chatservice.ChatRequestKey
import com.homeassistant.application.port.inbound.chatservice.ChatResult
import com.homeassistant.configuration.AppConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.UUID

internal fun Route.episodeConversationPageRoute() {
    get(AppConfig.ROUTE_EPISODE_CONVERSATION_PAGE) {
        val html = requireNotNull(javaClass.getResource("/conversation.html")) {
            "conversation.html is missing"
        }.readText()
        call.respondText(html, ContentType.Text.Html)
    }
}

internal fun Route.episodeConversationRoutes(
    episodeChatService: ChatService?,
) {
    post(AppConfig.ROUTE_EPISODE_CONVERSATION) {
        val principal = call.principal<HttpUserPrincipal>()
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "authentication required"))
            return@post
        }
        val conversation = episodeChatService
        if (conversation == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "episode conversation unavailable"))
            return@post
        }
        val request = try {
            call.receive<HttpEpisodeConversationRequest>()
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid request"))
            return@post
        }
        val requestId = request.requestId.trim()
        val question = request.question.trim()
        if (!isUuid(requestId) || question.isEmpty() || question.length > MAX_QUESTION_LENGTH) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "valid requestId and question are required"))
            return@post
        }

        val userId = principal.userId
        val result = withContext(Dispatchers.IO) {
            conversation.answer(
                ChatRequest(
                    participant = ChatParticipant(
                        scopeId = HTTP_CONVERSATION_SCOPE,
                        participantId = userId.value,
                        userId = userId,
                    ),
                    key = ChatRequestKey(
                        streamId = "$HTTP_CONVERSATION_SCOPE:${userId.value}",
                        requestId = requestId,
                    ),
                    question = question,
                ),
            )
        }
        when (result) {
            is ChatResult.AnswerReady -> call.respond(
                HttpStatusCode.OK,
                HttpEpisodeConversationResponse(answer = result.answer),
            )
            ChatResult.AlreadyHandled -> call.respond(
                HttpStatusCode.Conflict,
                mapOf("error" to "request already handled"),
            )
            ChatResult.Failed -> call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "episode conversation unavailable"),
            )
        }
    }
}

private fun isUuid(value: String): Boolean =
    runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)

@Serializable
internal data class HttpEpisodeConversationRequest(
    val requestId: String,
    val question: String,
)

@Serializable
internal data class HttpEpisodeConversationResponse(
    val answer: String,
)

private const val HTTP_CONVERSATION_SCOPE = "http"
private const val MAX_QUESTION_LENGTH = 10_000
