package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.input.memory.conversation.MemoryConversation
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationParticipant
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequest
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationRequestKey
import com.homeassistant.application.port.input.memory.conversation.MemoryConversationResult
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

internal fun Route.memoryConversationPageRoute() {
    get(AppConfig.ROUTE_MEMORY_CONVERSATION_PAGE) {
        val html = requireNotNull(javaClass.getResource("/conversation.html")) {
            "conversation.html is missing"
        }.readText()
        call.respondText(html, ContentType.Text.Html)
    }
}

internal fun Route.memoryConversationRoutes(
    memoryConversation: MemoryConversation?,
) {
    post(AppConfig.ROUTE_MEMORY_CONVERSATION) {
        val principal = call.principal<HttpUserPrincipal>()
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "authentication required"))
            return@post
        }
        val conversation = memoryConversation
        if (conversation == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "memory conversation unavailable"))
            return@post
        }
        val request = try {
            call.receive<HttpMemoryConversationRequest>()
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
                MemoryConversationRequest(
                    participant = MemoryConversationParticipant(
                        scopeId = HTTP_CONVERSATION_SCOPE,
                        participantId = userId.value,
                        userId = userId,
                    ),
                    key = MemoryConversationRequestKey(
                        streamId = "$HTTP_CONVERSATION_SCOPE:${userId.value}",
                        requestId = requestId,
                    ),
                    question = question,
                ),
            )
        }
        when (result) {
            is MemoryConversationResult.AnswerReady -> call.respond(
                HttpStatusCode.OK,
                HttpMemoryConversationResponse(answer = result.answer),
            )
            MemoryConversationResult.AlreadyHandled -> call.respond(
                HttpStatusCode.Conflict,
                mapOf("error" to "request already handled"),
            )
            MemoryConversationResult.Failed -> call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "memory conversation unavailable"),
            )
        }
    }
}

private fun isUuid(value: String): Boolean =
    runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)

@Serializable
internal data class HttpMemoryConversationRequest(
    val requestId: String,
    val question: String,
)

@Serializable
internal data class HttpMemoryConversationResponse(
    val answer: String,
)

private const val HTTP_CONVERSATION_SCOPE = "http"
private const val MAX_QUESTION_LENGTH = 10_000
