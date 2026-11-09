package com.homeassistant.adapter.inbound.mcp

import com.homeassistant.application.port.inbound.contexttool.SearchMemory
import com.homeassistant.application.usecase.contexttool.ContextGrants
import com.homeassistant.application.usecase.contexttool.SearchMemoryService
import com.homeassistant.configuration.AppConfig
import com.homeassistant.infrastructure.serialization.JsonSerializer
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextToolServerTest {
    @Test
    fun `search_memory is listed as a tool`() = testApplication {
        application { serve(RecordingSearch()) }

        val tools = rpc("tools/list", "{}", token = "turn-token").result()["tools"]!!.jsonArray

        assertEquals(listOf("search_memory"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun `search_memory searches with the bearer context token and returns the reference`() = testApplication {
        val search = RecordingSearch()
        application { serve(search) }

        val result = rpc(
            "tools/call",
            """{"name":"search_memory","arguments":{"query":"현관 비밀번호"}}""",
            token = "turn-token",
        ).result()

        assertEquals(listOf("turn-token" to "현관 비밀번호"), search.calls)
        assertEquals("reference for 현관 비밀번호", result.text())
        assertTrue(result["isError"]?.jsonPrimitive?.boolean != true)
    }

    @Test
    fun `a context token that grants nothing gets a tool error`() = testApplication {
        val noGrants = SearchMemoryService(ContextGrants(Duration.ofMinutes(10)), { _, _ -> error("unused") })
        application { serve(noGrants) }

        val result = rpc(
            "tools/call",
            """{"name":"search_memory","arguments":{"query":"현관"}}""",
            token = "revoked-token",
        ).result()

        assertEquals(true, result["isError"]!!.jsonPrimitive.boolean)
        assertEquals("context access expired", result.text())
    }

    /** Serves [searchMemory] the way the application module does, with its JSON content negotiation. */
    private fun Application.serve(searchMemory: SearchMemory) {
        install(ContentNegotiation) { json(JsonSerializer.json) }
        contextToolServer(searchMemory)
    }

    private suspend fun ApplicationTestBuilder.rpc(method: String, params: String, token: String): HttpResponse =
        client.post(AppConfig.ROUTE_CONTEXT_TOOLS) {
            header(HttpHeaders.Authorization, "Bearer $token")
            headers.set(HttpHeaders.Accept, "application/json, text/event-stream")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""")
        }

    private suspend fun HttpResponse.result(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject["result"]!!.jsonObject

    private fun JsonObject.text(): String =
        this["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content

    /** Answers every search with a reference naming the query. */
    private class RecordingSearch : SearchMemory {
        val calls = mutableListOf<Pair<String, String>>()

        override fun search(contextToken: String, query: String): String {
            calls += contextToken to query
            return "reference for $query"
        }
    }
}
