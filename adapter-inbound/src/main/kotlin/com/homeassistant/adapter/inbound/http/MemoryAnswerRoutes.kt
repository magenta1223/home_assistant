package com.homeassistant.adapter.inbound.http

import com.homeassistant.configuration.AppConfig
import com.homeassistant.application.memory.memorygroundedchat.MemoryAnswerRequest
import com.homeassistant.application.memory.memorygroundedchat.MemoryGroundedChatbot
import com.homeassistant.application.memory.read.MemorySearchUnavailableException
import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

internal fun Route.memoryAnswerRoutes(memoryGroundedChatbot: MemoryGroundedChatbot?) {
    post(AppConfig.ROUTE_MEMORY_ANSWER) {
        val principal = call.principal<HttpUserPrincipal>()
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "authentication required"))
            return@post
        }
        if (memoryGroundedChatbot == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "memory answer is not configured"))
            return@post
        }
        val request = call.receive<MemoryAnswerHttpRequest>()
        if (request.question.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "question is required"))
            return@post
        }

        try {
            call.respond(
                HttpStatusCode.OK,
                memoryGroundedChatbot.answer(
                    MemoryAnswerRequest(
                        userId = principal.userId.value,
                        question = request.question,
                        limit = request.limit,
                    ),
                ),
            )
        } catch (error: MemorySearchUnavailableException) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to error.message))
        } catch (_: HouseholdAccessDeniedException) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "household access denied"))
        }
    }
}

@kotlinx.serialization.Serializable
private data class MemoryAnswerHttpRequest(
    val question: String,
    val limit: Int = 5,
)
