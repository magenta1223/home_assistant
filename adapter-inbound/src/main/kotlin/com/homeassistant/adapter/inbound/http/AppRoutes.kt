package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.answer.MemoryAnswer
import com.homeassistant.domain.identity.UserId
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing

fun Application.configureRoutes(
    memoryAnalysis: MemoryAnalysis,
    memoryGroundedChatbot: MemoryAnswer? = null,
    httpApiKeys: Map<String, UserId> = emptyMap(),
    readiness: () -> Boolean = { true },
) {
    configureHttpAuthentication(httpApiKeys)
    routing {
        healthRoutes(readiness)
        authenticate(HTTP_AUTHENTICATION_NAME) {
            kakaoMemoryAnalysisRoutes(memoryAnalysis)
            memoryAnswerRoutes(memoryGroundedChatbot)
        }
    }
}
