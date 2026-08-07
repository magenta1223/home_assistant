package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.memory.analysis.MemoryAnalysis
import com.homeassistant.application.memory.memorygroundedchat.MemoryGroundedChatbot
import com.homeassistant.domain.identity.UserId
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing

fun Application.configureRoutes(
    memoryAnalysis: MemoryAnalysis,
    memoryGroundedChatbot: MemoryGroundedChatbot? = null,
    httpApiKeys: Map<String, UserId> = emptyMap(),
) {
    configureHttpAuthentication(httpApiKeys)
    routing {
        healthRoutes()
        authenticate(HTTP_AUTHENTICATION_NAME) {
            kakaoMemoryAnalysisRoutes(memoryAnalysis)
            memoryAnswerRoutes(memoryGroundedChatbot)
        }
    }
}
