package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.application.port.input.identity.RegisterUserRequest
import com.homeassistant.application.port.input.identity.UserRegistry
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisRequest
import com.homeassistant.application.port.input.memory.tree.VisibleMemoryTree
import com.homeassistant.application.port.input.memory.tree.VisibleMemoryTreeNode
import com.homeassistant.application.port.input.memory.tree.VisibleMemoryTreeRequest
import com.homeassistant.application.port.input.memory.tree.VisibleMemoryTreeResult
import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserAccessDeniedException
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryTreeRoutesTest {
    @Test
    fun `memory tree page and shared auth script are public without embedded credentials`() = testApplication {
        application { configureTestRoutes(RecordingVisibleMemoryTree()) }

        val page = client.get(AppConfig.ROUTE_MEMORY_TREE_PAGE)
        val html = page.bodyAsText()
        val script = client.get(HTTP_AUTH_SESSION_SCRIPT_ROUTE)

        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(html.contains("Memory Tree"))
        assertTrue(html.contains(AppConfig.ROUTE_MEMORY_TREE))
        assertTrue(html.contains(HTTP_AUTH_SESSION_SCRIPT_ROUTE))
        assertTrue(!html.contains("localStorage"))
        assertTrue(!html.contains("sessionStorage"))
        assertEquals(HttpStatusCode.OK, script.status)
        assertTrue(script.bodyAsText().contains(HTTP_SESSION_ROUTE))
        assertTrue(!script.bodyAsText().contains("localStorage"))
        assertTrue(!script.bodyAsText().contains("sessionStorage"))
    }

    @Test
    fun `authenticated principal owns the visible tree request and receives safe JSON`() = testApplication {
        val tree = RecordingVisibleMemoryTree(
            VisibleMemoryTreeResult(
                roots = listOf(node(1, children = listOf(node(2)))),
                totalCount = 2,
            ),
        )
        application { configureTestRoutes(tree) }

        val response = client.get("${AppConfig.ROUTE_MEMORY_TREE}?userId=intruder") {
            bearerAuth(API_TOKEN)
        }
        val body = response.bodyAsText()
        val compactBody = body.filterNot(Char::isWhitespace)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf(VisibleMemoryTreeRequest("member-1")), tree.requests)
        assertTrue(compactBody.contains("\"totalCount\":2"))
        assertTrue(compactBody.contains("\"memoryId\":1"))
        assertTrue(compactBody.contains("\"memoryId\":2"))
        assertTrue(compactBody.contains("\"memoryType\":\"REFERENCE\""))
        assertTrue(!body.contains("allowedUserIds"))
        assertTrue(!body.contains("evidenceRefs"))
        assertTrue(!body.contains("createdByUserId"))
        assertTrue(!body.contains("intruder"))
    }

    @Test
    fun `memory tree API requires authentication`() = testApplication {
        application { configureTestRoutes(RecordingVisibleMemoryTree()) }

        assertEquals(HttpStatusCode.Unauthorized, client.get(AppConfig.ROUTE_MEMORY_TREE).status)
    }

    @Test
    fun `unavailable tree returns service unavailable`() = testApplication {
        application { configureTestRoutes(visibleMemoryTree = null) }

        val response = client.get(AppConfig.ROUTE_MEMORY_TREE) { bearerAuth(API_TOKEN) }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `application access denial becomes forbidden`() = testApplication {
        application {
            configureTestRoutes(VisibleMemoryTree { throw UserAccessDeniedException() })
        }

        val response = client.get(AppConfig.ROUTE_MEMORY_TREE) { bearerAuth(API_TOKEN) }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    private fun io.ktor.server.application.Application.configureTestRoutes(
        visibleMemoryTree: VisibleMemoryTree?,
    ) {
        install(ContentNegotiation) { json(JsonSerializer.json) }
        configureRoutes(
            memoryAnalysis = unusedMemoryAnalysis(),
            httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_TOKEN) to UserId("member-1")),
            users = FixedUserRegistry(RegisteredUser(UserId("member-1"), "첫째")),
            visibleMemoryTree = visibleMemoryTree,
        )
    }

    private class RecordingVisibleMemoryTree(
        private val result: VisibleMemoryTreeResult = VisibleMemoryTreeResult(emptyList(), 0),
    ) : VisibleMemoryTree {
        val requests = mutableListOf<VisibleMemoryTreeRequest>()

        override fun get(request: VisibleMemoryTreeRequest): VisibleMemoryTreeResult {
            requests += request
            return result
        }
    }

    private class FixedUserRegistry(
        private vararg val users: RegisteredUser,
    ) : UserRegistry {
        override fun find(identity: ConversationIdentity): RegisteredUser? = null

        override fun register(request: RegisterUserRequest): RegisteredUser = error("unused")

        override fun list(): List<RegisteredUser> = users.toList()
    }

    private fun unusedMemoryAnalysis() = object : MemoryAnalysis {
        override suspend fun execute(request: MemoryAnalysisRequest) = error("unused")
    }

    private companion object {
        const val API_TOKEN = "test-token"

        fun node(
            id: Int,
            children: List<VisibleMemoryTreeNode> = emptyList(),
        ) = VisibleMemoryTreeNode(
            memoryId = id,
            subject = "subject-$id",
            content = "content-$id",
            memoryType = MemoryType.REFERENCE,
            certainty = MemoryCertainty.OBSERVED,
            createdAt = id.toLong(),
            children = children,
        )
    }
}
