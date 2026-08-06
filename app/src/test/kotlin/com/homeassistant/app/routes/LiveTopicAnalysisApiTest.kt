package com.homeassistant.app.routes

import com.homeassistant.adapter.outbound.codex.CodexTopicExtractorFactory
import com.homeassistant.adapter.inbound.kakao.KakaoExportParser
import com.homeassistant.adapter.inbound.http.configureRoutes
import com.homeassistant.domain.identity.HouseholdAccessPolicies
import com.homeassistant.domain.identity.UserId
import com.homeassistant.adapter.shared.json.JsonSerializer
import com.homeassistant.application.memory.answer.MemorySearchIndexes
import com.homeassistant.application.topicanalysis.analyze.AnalyzeSource
import com.homeassistant.application.topicanalysis.save.SaveTopicCandidates
import com.homeassistant.domain.kakao.KakaoImporterFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
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

class LiveTopicAnalysisApiTest {
    @Test
    fun `real Codex backend analyzes topics through HTTP API`() = runBlocking {
        if (System.getenv(LIVE_TEST_ENV) != "true") return@runBlocking

        val databasePath = Files.createDirectories(
            java.nio.file.Path.of("build", "tmp", "live-topic-analysis"),
        ).resolve("${UUID.randomUUID()}.db")
        databasePath.toFile().deleteOnExit()

        val repositories = RepositoryFactory.create(databasePath.toString())
        val importer = KakaoImporterFactory.create(repositories.kakaoMessages)
        val accessPolicy = HouseholdAccessPolicies.fixed(listOf(UserId(USER_ID)))
        val analyzeSource = AnalyzeSource(
            topicExtractor = CodexTopicExtractorFactory.create(),
            sourceTextParser = KakaoExportParser,
            importService = importer,
            previewRepository = repositories.kakaoAnalysisPreviews,
            accessPolicy = accessPolicy,
        )
        val saveTopicCandidates = SaveTopicCandidates(
            importService = importer,
            sourceTextParser = KakaoExportParser,
            topicRepository = repositories.topicAnalysis,
            previewRepository = repositories.kakaoAnalysisPreviews,
            memorySearchIndex = MemorySearchIndexes.unavailable(),
            indexingOutbox = repositories.indexingOutbox,
            accessPolicy = accessPolicy,
        )

        testApplication {
            application {
                install(ContentNegotiation) { json(JsonSerializer.json) }
                configureRoutes(analyzeSource, saveTopicCandidates)
            }

            val response = client.post("/api/kakao/import/analyze") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "userId": "$USER_ID",
                      "sourceName": "live-api-smoke.txt",
                      "text": "[동훈] [오후 5:30] 오늘 저녁 7시에 카인드커피에서 만나자\n[승민] [오후 5:31] 좋아, 카인드커피는 서울역 2번 출구 앞이야\n[동훈] [오후 5:32] 도착하면 전화할게"
                    }
                    """.trimIndent(),
                )
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = JsonSerializer.json.parseToJsonElement(response.bodyAsText()).jsonObject
            val topics = payload.getValue("topics").jsonArray
            assertTrue(topics.isNotEmpty(), "Codex returned no topics through the analysis API")
            println("LIVE_TOPIC_ANALYSIS_API_TOPICS=${topics.size}")
        }
    }

    private companion object {
        const val LIVE_TEST_ENV = "RUN_LIVE_CODEX_API_TEST"
        const val USER_ID = "api-smoke-user"
    }
}
