package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.memory.analysis.MemoryAnalysis
import com.homeassistant.application.memory.analysis.MemoryAnalysisRequest
import com.homeassistant.application.memory.analysis.MemoryAnalysisResult
import com.homeassistant.application.memory.memorygroundedchat.MemoryGroundedChatbot
import com.homeassistant.application.memory.read.MemoryIndex
import com.homeassistant.application.memory.read.MemoryReader
import com.homeassistant.application.memory.read.MemorySearcher
import com.homeassistant.application.memory.read.SemanticMemoryIndexSearcher
import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.identity.HouseholdAccessPolicy
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.memory.Memory
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

class MemoryAnswerRoutesTest {
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

    private fun memoryGroundedChatbot() = MemoryGroundedChatbot(
        MemorySearcher(
            memories = EmptyMemoryReader,
            searcher = SemanticMemoryIndexSearcher { _, _ -> emptyList<MemoryIndex>() },
            accessPolicy = HouseholdAccessPolicy { it == USER_ID },
        ),
    )

    private object EmptyMemoryReader : MemoryReader {
        override fun getMemories(userId: UserId) = emptyList<Memory>()
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
