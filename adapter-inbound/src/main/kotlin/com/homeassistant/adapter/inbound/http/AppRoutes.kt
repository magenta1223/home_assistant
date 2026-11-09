package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysis
import com.homeassistant.application.port.inbound.identity.UserAuthentication
import com.homeassistant.application.port.inbound.identity.UserRegistry
import com.homeassistant.application.port.inbound.chatservice.Chat
import com.homeassistant.application.port.inbound.contexttool.ContextAccess
import com.homeassistant.application.port.inbound.contexttool.FindTransactions
import com.homeassistant.application.port.inbound.contexttool.SearchMemory
import com.homeassistant.adapter.inbound.mcp.contextToolServer
import com.homeassistant.application.port.inbound.finance.ImportFinanceTransactions
import com.homeassistant.domain.identity.UserId
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.routing

fun Application.configureRoutes(
    episodeAnalysis: EpisodeAnalysis,
    httpApiKeys: Map<String, UserId> = emptyMap(),
    users: UserRegistry = UserRegistry.NONE,
    authentication: UserAuthentication = UserAuthentication.NONE,
    chat: Chat? = null,
    importFinanceTransactions: ImportFinanceTransactions? = null,
    contextAccess: ContextAccess? = null,
    searchMemory: SearchMemory? = null,
    findTransactions: FindTransactions? = null,
    readiness: () -> Boolean = { true },
) {
    validateHttpUsers(httpApiKeys.values, users)
    configureHttpAuthentication(httpApiKeys, authentication)
    routing {
        healthRoutes(readiness)
        // The pages and their scripts under resources/static, served at their own paths without the .html suffix.
        staticResources("/", "static") { extensions("html") }
        publicLoginRoutes(authentication)
        authenticate(*HTTP_AUTHENTICATION_NAMES) {
            sessionStatusRoute()
            knowledgeInjectionRoutes(episodeAnalysis, users)
            chatRoutes(chat)
            financeImportRoutes(importFinanceTransactions)
        }
    }
    // Context tools authenticate with the per-turn context token instead of a user login.
    if (contextAccess != null && searchMemory != null && findTransactions != null) {
        contextToolServer(contextAccess, searchMemory, findTransactions)
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
