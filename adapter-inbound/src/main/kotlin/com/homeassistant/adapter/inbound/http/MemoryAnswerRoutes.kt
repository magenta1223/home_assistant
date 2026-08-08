package com.homeassistant.adapter.inbound.http

import com.homeassistant.configuration.AppConfig
import com.homeassistant.application.port.input.memory.answer.MemoryAnswerRequest
import com.homeassistant.application.port.input.memory.answer.MemoryAnswer
import com.homeassistant.application.port.input.memory.search.MemorySearchUnavailableException
import com.homeassistant.application.port.input.memory.search.SearchMemoriesRequest
import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

internal fun Route.memoryAnswerRoutes(memoryGroundedChatbot: MemoryAnswer?) {
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
        if (request.limit !in SearchMemoriesRequest.MIN_LIMIT..SearchMemoriesRequest.MAX_LIMIT) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to
                        "limit must be between ${SearchMemoriesRequest.MIN_LIMIT} and ${SearchMemoriesRequest.MAX_LIMIT}",
                ),
            )
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
