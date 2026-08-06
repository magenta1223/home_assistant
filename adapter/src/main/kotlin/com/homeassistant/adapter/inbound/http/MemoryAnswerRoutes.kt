package com.homeassistant.adapter.inbound.http

import com.homeassistant.adapter.shared.config.AppConfig
import com.homeassistant.application.memory.answer.MemoryAnswerRequest
import com.homeassistant.application.memory.answer.MemoryAnswerUseCase
import com.homeassistant.application.memory.answer.MemorySearchIndexUnavailableException
import com.homeassistant.domain.identity.HouseholdAccessDeniedException
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

internal fun Route.memoryAnswerRoutes(memoryAnswer: MemoryAnswerUseCase?) {
    post(AppConfig.ROUTE_MEMORY_ANSWER) {
        if (memoryAnswer == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "memory answer is not configured"))
            return@post
        }
        val request = call.receive<MemoryAnswerRequest>()
        if (request.userId.isBlank() || request.question.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "userId and question are required"))
            return@post
        }

        try {
            call.respond(HttpStatusCode.OK, memoryAnswer.answer(request))
        } catch (error: MemorySearchIndexUnavailableException) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to error.message))
        } catch (_: HouseholdAccessDeniedException) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "household access denied"))
        }
    }
}
