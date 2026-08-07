package com.homeassistant.app.routes

import com.homeassistant.adapter.inbound.http.HttpApiKeyConfig
import com.homeassistant.adapter.inbound.http.configureRoutes
import com.homeassistant.adapter.outbound.memoryanalysis.MemoryExtractorFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.application.memory.analysis.MemoryAnalysis
import com.homeassistant.application.memory.analysis.MemoryAnalysisService
import com.homeassistant.application.memory.index.SemanticMemoryIndexWriter
import com.homeassistant.application.memory.save.SaveMemoryProposals
import com.homeassistant.domain.identity.HouseholdAccessPolicies
import com.homeassistant.domain.identity.UserId
import com.homeassistant.common.json.JsonSerializer
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveMemoryAnalysisServiceApiTest {
    @Test
    fun `real Codex backend analyzes memories through HTTP API`() = runBlocking {
        if (System.getenv(LIVE_TEST_ENV) != "true") return@runBlocking

        val databasePath = Files.createDirectories(
            java.nio.file.Path.of("build", "tmp", "live-memory-analysis"),
        ).resolve("${UUID.randomUUID()}.db")
        databasePath.toFile().deleteOnExit()

        val repositories = RepositoryFactory.create(databasePath.toString())
        val accessPolicy = HouseholdAccessPolicies.fixed(listOf(UserId(USER_ID)))
        val memoryAnalysisService: MemoryAnalysis = MemoryAnalysisService(
            memoryExtractor = MemoryExtractorFactory.create(),
            sourceRecords = repositories.sourceRecords,
            memorySaver = SaveMemoryProposals(
                memoryCreator = repositories.memoryCreator,
                memoryIndexWriter = NoOpSemanticMemoryIndexWriter,
                memoryReader = repositories.canonicalMemories,
                indexingOutbox = repositories.indexingOutbox,
            ),
            accessPolicy = accessPolicy,
        )

        testApplication {
            application {
                install(ContentNegotiation) { json(JsonSerializer.json) }
                configureRoutes(
                    memoryAnalysisService,
                    httpApiKeys = HttpApiKeyConfig.fromJson(
                        """[{"userId":"$USER_ID","token":"test-token"}]""",
                    ),
                )
            }

            val response = client.post("/api/kakao/import/analyze") {
                header("Authorization", "Bearer test-token")
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "sourceName": "live-api-smoke.txt",
                      "text": "[동훈] [오후 5:30] 오늘 저녁 7시에 카인드커피에서 만나자\n[승민] [오후 5:31] 좋아, 카인드커피는 서울역 2번 출구 앞이야\n[동훈] [오후 5:32] 도착하면 전화할게"
                    }
                    """.trimIndent(),
                )
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = JsonSerializer.json.parseToJsonElement(response.bodyAsText()).jsonObject
            val memories = payload.getValue("memories").jsonArray
            assertTrue(memories.isNotEmpty(), "Codex returned no memories through the analysis API")
            println("LIVE_MEMORY_ANALYSIS_API_MEMORIES=${memories.size}")
        }
    }

    private companion object {
        const val LIVE_TEST_ENV = "RUN_LIVE_CODEX_API_TEST"
        const val USER_ID = "api-smoke-user"
    }
}

private object NoOpSemanticMemoryIndexWriter : SemanticMemoryIndexWriter {
    override fun upsert(memory: com.homeassistant.domain.memory.Memory) = Unit
}
