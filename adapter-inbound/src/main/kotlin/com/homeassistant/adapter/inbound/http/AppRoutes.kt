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
    memberUserIds: Set<UserId> = httpApiKeys.values.toSet(),
    readiness: () -> Boolean = { true },
) {
    configureHttpAuthentication(httpApiKeys)
    routing {
        healthRoutes(readiness)
        knowledgePageRoute()
        authenticate(HTTP_AUTHENTICATION_NAME) {
            knowledgeInjectionRoutes(memoryAnalysis, memberUserIds)
            memoryAnswerRoutes(memoryGroundedChatbot)
        }
    }
}
