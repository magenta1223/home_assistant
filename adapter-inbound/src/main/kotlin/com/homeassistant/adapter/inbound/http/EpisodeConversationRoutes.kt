package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.inbound.chatservice.Chat
import com.homeassistant.application.port.inbound.chatservice.ChatRequest
import com.homeassistant.application.port.inbound.chatservice.ChatResult
import com.homeassistant.configuration.AppConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

internal fun Route.chatRoutes(
    episodeChat: Chat?,
) {
    post(AppConfig.ROUTE_EPISODE_CONVERSATION) {
        val principal = call.principal<HttpUserPrincipal>()
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "authentication required"))
            return@post
        }
        if (episodeChat == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "episode conversation unavailable"))
            return@post
        }
        val request = try {
            call.receive<HttpChatRequest>()
        } catch (_: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid request"))
            return@post
        }
        val question = request.question.trim()
        if (question.isEmpty() || question.length > MAX_QUESTION_LENGTH) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "question is required"))
            return@post
        }

        val userId = principal.userId
        val result = withContext(Dispatchers.IO) {
            episodeChat.answer(
                ChatRequest(
                    userId = userId,
                    question = question,
                ),
            )
        }
        when (result) {
            is ChatResult.AnswerReady -> call.respond(
                HttpStatusCode.OK,
                HttpChatResponse(answer = result.answer),
            )
            ChatResult.Failed -> call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "episode conversation unavailable"),
            )
        }
    }
}

@Serializable
internal data class HttpChatRequest(
    val question: String,
)

@Serializable
internal data class HttpChatResponse(
    val answer: String,
)

private const val MAX_QUESTION_LENGTH = 10_000
