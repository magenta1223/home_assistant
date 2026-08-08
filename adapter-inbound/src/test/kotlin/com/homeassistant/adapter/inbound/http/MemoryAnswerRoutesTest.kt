package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisRequest
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisResult
import com.homeassistant.application.usecase.memory.answer.MemoryGroundedChatbot
import com.homeassistant.application.usecase.memory.answer.MemoryAnswerContextProvider
import com.homeassistant.application.port.output.memory.search.MemoryIndex
import com.homeassistant.application.port.output.memory.search.MemoryIndexSearchScope
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.application.usecase.memory.search.MemorySearcher
import com.homeassistant.application.port.output.memory.search.SemanticMemoryIndexSearcher
import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class MemoryAnswerRoutesTest {
    @Test
    fun `answer keeps public matches within limit while serializing direct diagnostics`() = testApplication {
        val parent = memory(1, childrenIds = listOf(2), createdAt = 1_000L)
        val child = memory(2, createdAt = 2_000L)
        application {
            install(ContentNegotiation) {
                json(JsonSerializer.json)
            }
            configureRoutes(
                memoryAnalysis = UnusedMemoryAnalysis,
                memoryGroundedChatbot = memoryGroundedChatbot(
                    memories = listOf(parent, child),
                    directResults = listOf(MemoryIndex(parent.id, 0.9)),
                    childResults = listOf(MemoryIndex(child.id, 0.85)),
                ),
                httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_TOKEN) to USER_ID),
            )
        }

        val response = client.post(AppConfig.ROUTE_MEMORY_ANSWER) {
            bearerAuth(API_TOKEN)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"question":"where is it?","limit":1}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val matches = JsonSerializer.json.parseToJsonElement(response.bodyAsText())
            .jsonObject.getValue("matches").jsonArray
        assertEquals(1, matches.size)
        val match = matches.single().jsonObject
        assertEquals(parent.id, match.getValue("memoryId").jsonPrimitive.int)
        assertEquals(parent.createdAt, match.getValue("createdAt").jsonPrimitive.long)
        assertEquals(0.9, match.getValue("score").jsonPrimitive.double)
        assertEquals("DIRECT", match.getValue("source").jsonPrimitive.content)
        assertEquals(0, match.getValue("depth").jsonPrimitive.int)
        assertTrue("parentMemoryId" !in match)
    }

    @Test
    fun `rejects an out-of-range limit as a bad request`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(JsonSerializer.json)
            }
            configureRoutes(
                memoryAnalysis = UnusedMemoryAnalysis,
                memoryGroundedChatbot = memoryGroundedChatbot(),
                httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_TOKEN) to USER_ID),
            )
        }

        listOf(0, 11).forEach { invalidLimit ->
            val response = client.post(AppConfig.ROUTE_MEMORY_ANSWER) {
                bearerAuth(API_TOKEN)
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"question":"where is it?","limit":$invalidLimit}""")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("limit must be between 1 and 10"))
        }
    }

    @Test
    fun `maps answer usecase failures to service unavailable`() = testApplication {
        application {
            install(ContentNegotiation) {
                json(JsonSerializer.json)
            }
            configureRoutes(
                memoryAnalysis = UnusedMemoryAnalysis,
                memoryGroundedChatbot = memoryGroundedChatbot(
                    memories = listOf(memory(1, createdAt = 1_000L)),
                    semanticFailure = IllegalStateException("vector connection failed"),
                ),
                httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_TOKEN) to USER_ID),
            )
        }

        val response = client.post(AppConfig.ROUTE_MEMORY_ANSWER) {
            bearerAuth(API_TOKEN)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"question":"where is it?"}""")
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("memory answer is unavailable"))
    }

    private fun memoryGroundedChatbot(
        memories: List<Memory> = emptyList(),
        directResults: List<MemoryIndex> = emptyList(),
        childResults: List<MemoryIndex> = emptyList(),
        semanticFailure: RuntimeException? = null,
    ): MemoryGroundedChatbot {
        val reader = FixedMemoryReader(memories)
        val directScope = memories.mapTo(mutableSetOf()) { it.id }
        val semanticSearcher = object : SemanticMemoryIndexSearcher {
            override fun search(query: String, limit: Int): List<MemoryIndex> = directResults.take(limit)

            override fun search(
                query: String,
                limit: Int,
                scope: MemoryIndexSearchScope,
            ): List<MemoryIndex> {
                semanticFailure?.let { throw it }
                val allowedIds = scope.allowedMemoryIds.orEmpty()
                val results = if (allowedIds == directScope) directResults else childResults
                return results.filter { it.memoryId in allowedIds }.take(limit)
            }
        }
        val memorySearcher = MemorySearcher(
            memories = reader,
            searcher = semanticSearcher,
            accessPolicy = HouseholdAccessPolicy { it == USER_ID },
        )
        return MemoryGroundedChatbot(
            MemoryAnswerContextProvider(memorySearcher, reader, semanticSearcher),
        )
    }

    private fun memory(
        id: Int,
        childrenIds: List<Int> = emptyList(),
        createdAt: Long,
    ) = Memory(
        id = id,
        childrenIds = childrenIds,
        createdByUserId = USER_ID.value,
        content = "memory-$id",
        subject = "subject-$id",
        memoryType = MemoryType.REFERENCE,
        certainty = MemoryCertainty.OBSERVED,
        visibility = MemoryVisibility.PUBLIC,
        evidenceRefs = listOf(id),
        createdAt = createdAt,
    )

    private class FixedMemoryReader(
        private val memories: List<Memory>,
    ) : MemoryReader {
        override fun getMemories(userId: UserId) = memories
    }

    private object UnusedMemoryAnalysis : MemoryAnalysis {
        override suspend fun execute(request: MemoryAnalysisRequest): MemoryAnalysisResult =
            error("memory analysis is not used by this test")
    }

    private companion object {
        val USER_ID = UserId("member-1")
        const val API_TOKEN = "test-token"
    }
}
