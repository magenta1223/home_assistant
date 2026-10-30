package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.identity.UserAuthentication
import com.homeassistant.application.port.input.identity.UserRegistry
import com.homeassistant.application.port.input.memory.conversation.MemoryConversation
import com.homeassistant.domain.identity.UserId
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing

fun Application.configureRoutes(
    memoryAnalysis: MemoryAnalysis,
    httpApiKeys: Map<String, UserId> = emptyMap(),
    users: UserRegistry = UserRegistry.NONE,
    authentication: UserAuthentication = UserAuthentication.NONE,
    memoryConversation: MemoryConversation? = null,
    readiness: () -> Boolean = { true },
) {
    validateHttpUsers(httpApiKeys.values, users)
    configureHttpAuthentication(httpApiKeys, authentication)
    routing {
        healthRoutes(readiness)
        knowledgePageRoute()
        memoryConversationPageRoute()
        httpAuthAssetRoutes()
        publicLoginRoutes(authentication)
        authenticate(*HTTP_AUTHENTICATION_NAMES) {
            authenticatedLoginRoutes(authentication)
            knowledgeInjectionRoutes(memoryAnalysis, users)
            memoryConversationRoutes(memoryConversation)
        }
    }
}

private fun validateHttpUsers(
    configuredUserIds: Collection<UserId>,
    users: UserRegistry,
) {
    if (configuredUserIds.isEmpty()) return
    val registeredUserIds = users.list().mapTo(mutableSetOf()) { it.userId }
    val unregisteredUserIds = configuredUserIds.toSet() - registeredUserIds
    require(unregisteredUserIds.isEmpty()) {
        "HTTP API users must be registered application users: " +
            unregisteredUserIds.map { it.value }.sorted().joinToString()
    }
}
