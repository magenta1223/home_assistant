package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.input.memory.tree.VisibleMemoryTree
import com.homeassistant.application.port.input.memory.tree.VisibleMemoryTreeNode
import com.homeassistant.application.port.input.memory.tree.VisibleMemoryTreeRequest
import com.homeassistant.application.port.input.memory.tree.VisibleMemoryTreeResult
import com.homeassistant.application.port.input.memory.tree.VisibleMemoryTreeUnavailableException
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.identity.UserAccessDeniedException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

internal fun Route.memoryTreePageRoutes() {
    get(AppConfig.ROUTE_MEMORY_TREE_PAGE) {
        call.respondText(resourceText("/memory-tree.html"), ContentType.Text.Html)
    }
    get(HTTP_AUTH_SESSION_SCRIPT_ROUTE) {
        call.respondText(resourceText("/http-auth-session.js"), ContentType.Application.JavaScript)
    }
}

internal fun Route.memoryTreeRoutes(
    visibleMemoryTree: VisibleMemoryTree?,
) {
    get(AppConfig.ROUTE_MEMORY_TREE) {
        val principal = call.principal<HttpUserPrincipal>()
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "authentication required"))
            return@get
        }
        val tree = visibleMemoryTree
        if (tree == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "memory tree unavailable"))
            return@get
        }

        try {
            val result = withContext(Dispatchers.IO) {
                tree.get(VisibleMemoryTreeRequest(principal.userId.value))
            }
            call.respond(HttpStatusCode.OK, result.toHttpResponse())
        } catch (_: UserAccessDeniedException) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "user access denied"))
        } catch (_: VisibleMemoryTreeUnavailableException) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "memory tree unavailable"))
        }
    }
}

private fun resourceText(path: String): String = requireNotNull(HttpResources::class.java.getResource(path)) {
    "$path is missing"
}.readText()

private object HttpResources

private fun VisibleMemoryTreeResult.toHttpResponse() = HttpMemoryTreeResponse(
    roots = roots.map(VisibleMemoryTreeNode::toHttpResponse),
    totalCount = totalCount,
)

private fun VisibleMemoryTreeNode.toHttpResponse(): HttpMemoryTreeNode = HttpMemoryTreeNode(
    memoryId = memoryId,
    subject = subject,
    content = content,
    memoryType = memoryType.name,
    certainty = certainty.name,
    createdAt = createdAt,
    children = children.map(VisibleMemoryTreeNode::toHttpResponse),
)

@Serializable
private data class HttpMemoryTreeResponse(
    val roots: List<HttpMemoryTreeNode>,
    val totalCount: Int,
)

@Serializable
private data class HttpMemoryTreeNode(
    val memoryId: Int,
    val subject: String,
    val content: String,
    val memoryType: String,
    val certainty: String,
    val createdAt: Long,
    val children: List<HttpMemoryTreeNode>,
)

internal const val HTTP_AUTH_SESSION_SCRIPT_ROUTE = "/assets/http-auth-session.js"
