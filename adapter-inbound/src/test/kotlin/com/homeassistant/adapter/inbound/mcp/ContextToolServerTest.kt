package com.homeassistant.adapter.inbound.mcp

import com.homeassistant.application.port.inbound.contexttool.ContextAccess
import com.homeassistant.application.port.inbound.contexttool.FindTransactions
import com.homeassistant.application.port.inbound.contexttool.SearchMemory
import com.homeassistant.application.port.inbound.contexttool.TransactionSearch
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.identity.UserId
import com.homeassistant.infrastructure.serialization.JsonSerializer
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextToolServerTest {
    @Test
    fun `search_memory and find_transactions are listed as tools`() = testApplication {
        application { serve(RecordingSearch()) }

        val tools = rpc("tools/list", "{}", token = TURN_TOKEN).result()["tools"]!!.jsonArray

        assertEquals(
            listOf("search_memory", "find_transactions"),
            tools.map { it.jsonObject["name"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `search_memory searches as the owner of the bearer context token`() = testApplication {
        val search = RecordingSearch()
        application { serve(search) }

        val result = rpc(
            "tools/call",
            """{"name":"search_memory","arguments":{"query":"현관 비밀번호"}}""",
            token = TURN_TOKEN,
        ).result()

        assertEquals(listOf(OWNER to "현관 비밀번호"), search.calls)
        assertEquals("reference for 현관 비밀번호", result.text())
        assertTrue(result["isError"]?.jsonPrimitive?.boolean != true)
    }

    @Test
    fun `find_transactions searches the given days as the owner of the bearer context token`() = testApplication {
        val transactions = RecordingTransactions()
        application { serve(RecordingSearch(), transactions) }

        val result = rpc(
            "tools/call",
            """{"name":"find_transactions","arguments":{"from":"2026-11-01","to":"2026-11-30","category":"식비"}}""",
            token = TURN_TOKEN,
        ).result()

        assertEquals(
            listOf(OWNER to TransactionSearch(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), "식비")),
            transactions.calls,
        )
        assertEquals("2 found", result.text())
    }

    @Test
    fun `find_transactions with a malformed date gets a tool error`() = testApplication {
        val transactions = RecordingTransactions()
        application { serve(RecordingSearch(), transactions) }

        val result = rpc(
            "tools/call",
            """{"name":"find_transactions","arguments":{"from":"11월 1일","to":"2026-11-30"}}""",
            token = TURN_TOKEN,
        ).result()

        assertEquals(true, result["isError"]!!.jsonPrimitive.boolean)
        assertEquals("from must be a date as YYYY-MM-DD", result.text())
        assertTrue(transactions.calls.isEmpty())
    }

    @Test
    fun `a request with a context token that grants nothing is rejected with 401`() = testApplication {
        val search = RecordingSearch()
        application { serve(search) }

        val response = rpc("tools/call", """{"name":"search_memory","arguments":{"query":"현관"}}""", "revoked")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(search.calls.isEmpty())
    }

    @Test
    fun `a request without a context token is rejected with 401`() = testApplication {
        val search = RecordingSearch()
        application { serve(search) }

        val response = client.post(AppConfig.ROUTE_CONTEXT_TOOLS) {
            jsonRpc("tools/call", """{"name":"search_memory","arguments":{"query":"현관"}}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(search.calls.isEmpty())
    }

    /** Serves the context tools the way the application module does, with its JSON content negotiation. */
    private fun Application.serve(
        searchMemory: SearchMemory,
        findTransactions: FindTransactions = RecordingTransactions(),
    ) {
        install(ContentNegotiation) { json(JsonSerializer.json) }
        contextToolServer(ContextAccess { token -> OWNER.takeIf { token == TURN_TOKEN } }, searchMemory, findTransactions)
    }

    private suspend fun ApplicationTestBuilder.rpc(method: String, params: String, token: String): HttpResponse =
        client.post(AppConfig.ROUTE_CONTEXT_TOOLS) {
            header(HttpHeaders.Authorization, "Bearer $token")
            jsonRpc(method, params)
        }

    private fun HttpRequestBuilder.jsonRpc(method: String, params: String) {
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
        val calls = mutableListOf<Pair<UserId, String>>()

        override fun search(userId: UserId, query: String): String {
            calls += userId to query
            return "reference for $query"
        }
    }

    /** Answers every search with how many transactions it found. */
    private class RecordingTransactions : FindTransactions {
        val calls = mutableListOf<Pair<UserId, TransactionSearch>>()

        override fun find(userId: UserId, search: TransactionSearch): String {
            calls += userId to search
            return "2 found"
        }
    }

    private companion object {
        const val TURN_TOKEN = "turn-token"
        val OWNER = UserId("member-1")
    }
}
