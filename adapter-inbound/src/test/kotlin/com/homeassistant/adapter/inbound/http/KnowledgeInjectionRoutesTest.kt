package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysis
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisJobReport
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisRequest
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisSubmissionResult
import com.homeassistant.application.port.inbound.identity.UserRegistry
import com.homeassistant.infrastructure.serialization.JsonSerializer
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.access.Visibility
import com.homeassistant.domain.episode.EpisodeAnalysisJobStatus
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

class KnowledgeInjectionRoutesTest {
    @Test
    fun `episode answers are not exposed over HTTP`() = testApplication {
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(RecordingEpisodeAnalysis())
        }

        assertEquals(HttpStatusCode.NotFound, client.post("/api/episodes/answer").status)
    }

    @Test
    fun `knowledge page is hosted without exposing data`() = testApplication {
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(RecordingEpisodeAnalysis())
        }

        val response = client.get("/knowledge")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("지식 주입"))
        assertTrue(response.bodyAsText().contains("/conversation"))
        assertTrue(response.bodyAsText().contains("/assets/http-auth-session.js"))
        assertTrue(!response.bodyAsText().contains("localStorage"))
        assertTrue(!response.bodyAsText().contains("sessionStorage"))
    }

    @Test
    fun `authenticated import passes selected viewers as restricted source access`() = testApplication {
        val analysis = RecordingEpisodeAnalysis()
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(
                episodeAnalysis = analysis,
                httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_TOKEN) to UserId("operator")),
                users = FixedUserRegistry(
                    RegisteredUser(UserId("operator"), "운영자"),
                    RegisteredUser(UserId("member-1"), "첫째"),
                    RegisteredUser(UserId("member-2"), "둘째"),
                ),
            )
        }

        val users = client.get(AppConfig.ROUTE_KNOWLEDGE_USERS) { bearerAuth(API_TOKEN) }
        val response = client.post(AppConfig.ROUTE_KNOWLEDGE_IMPORT_ANALYZE) {
            bearerAuth(API_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(
                """{
                  "sourceType":"TEXT",
                  "sourceName":"직접 입력",
                  "isPublic":false,
                  "allowedUserIds":["member-1","member-2"],
                  "text":"현관 비밀번호는 매달 바뀐다"
                }""".trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.OK, users.status)
        assertTrue(users.bodyAsText().contains("member-2"))
        assertTrue(users.bodyAsText().contains("둘째"))
        assertEquals(HttpStatusCode.Accepted, response.status)
        val captured = analysis.requests.single()
        assertEquals("operator", captured.userId)
        assertEquals(Visibility.RESTRICTED, captured.access.visibility)
        assertEquals(setOf("member-1", "member-2"), captured.access.allowedUserIds)
        assertEquals("text", captured.source.source.type)
    }

    @Test
    fun `import response returns the queued job and record counts`() = testApplication {
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(
                episodeAnalysis = RecordingEpisodeAnalysis(),
                httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_TOKEN) to UserId("operator")),
                users = operatorRegistry(),
            )
        }

        val response = client.post(AppConfig.ROUTE_KNOWLEDGE_IMPORT_ANALYZE) {
            bearerAuth(API_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(
                """{
                  "sourceType":"TEXT",
                  "sourceName":"직접 입력",
                  "isPublic":true,
                  "text":"현관 비밀번호는 매달 바뀐다"
                }""".trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals(
            Json.parseToJsonElement(
                """{
                  "jobId":7,
                  "importedRecordCount":1,
                  "retriedRecordCount":0,
                  "alreadyAnalyzedRecordCount":0,
                  "visibility":"PUBLIC",
                  "allowedUserIds":[]
                }""",
            ),
            Json.parseToJsonElement(response.bodyAsText()),
        )
    }

    @Test
    fun `public import cannot smuggle a restricted allow list`() = testApplication {
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(
                episodeAnalysis = RecordingEpisodeAnalysis(),
                httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_TOKEN) to UserId("operator")),
                users = operatorRegistry(),
            )
        }

        val response = client.post(AppConfig.ROUTE_KNOWLEDGE_IMPORT_ANALYZE) {
            bearerAuth(API_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(
                """{
                  "sourceType":"TEXT",
                  "sourceName":"invalid",
                  "isPublic":true,
                  "allowedUserIds":["member-1"],
                  "text":"data"
                }""".trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `PDF reference can be submitted with direct text and keeps original bytes`() = testApplication {
        val analysis = RecordingEpisodeAnalysis()
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(
                episodeAnalysis = analysis,
                httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_TOKEN) to UserId("operator")),
                users = operatorRegistry(),
            )
        }
        val original = "%PDF-reference".toByteArray()
        val encoded = java.util.Base64.getEncoder().encodeToString(original)

        val response = client.post(AppConfig.ROUTE_KNOWLEDGE_IMPORT_ANALYZE) {
            bearerAuth(API_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(
                """{
                  "sourceType":"TEXT",
                  "sourceName":"설명서",
                  "isPublic":true,
                  "text":"설치 시 주의사항",
                  "reference":{
                    "fileName":"manual.pdf",
                    "mediaType":"application/pdf",
                    "contentBase64":"$encoded"
                  }
                }""".trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        val reference = analysis.requests.single().source.reference
        assertEquals("manual.pdf", reference?.fileName)
        assertContentEquals(original, reference?.bytes())
    }

    @Test
    fun `the submitter reads how the analysis job went`() = testApplication {
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(
                episodeAnalysis = RecordingEpisodeAnalysis(),
                httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_TOKEN) to UserId("operator")),
                users = operatorRegistry(),
            )
        }

        val response = client.get(jobRoute("7")) { bearerAuth(API_TOKEN) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            Json.parseToJsonElement("""{"jobId":7,"status":"COMPLETED","episodeCount":2}"""),
            Json.parseToJsonElement(response.bodyAsText()),
        )
    }

    @Test
    fun `an analysis job the user did not submit is not found`() = testApplication {
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(
                episodeAnalysis = RecordingEpisodeAnalysis(),
                httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_TOKEN) to UserId("operator")),
                users = operatorRegistry(),
            )
        }

        assertEquals(HttpStatusCode.NotFound, client.get(jobRoute("8")) { bearerAuth(API_TOKEN) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get(jobRoute("latest")) { bearerAuth(API_TOKEN) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get(jobRoute("7")).status)
    }

    @Test
    fun `configured HTTP user must be a registered application user`() {
        assertFailsWith<IllegalArgumentException> {
            testApplication {
                application {
                    install(ContentNegotiation) { json(JsonSerializer.json) }
                    configureRoutes(
                        episodeAnalysis = RecordingEpisodeAnalysis(),
                        httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_TOKEN) to UserId("unknown")),
                        users = operatorRegistry(),
                    )
                }
                client.get(AppConfig.ROUTE_HEALTH)
            }
        }
    }

    private class RecordingEpisodeAnalysis : EpisodeAnalysis {
        val requests = mutableListOf<EpisodeAnalysisRequest>()

        override fun submit(request: EpisodeAnalysisRequest): EpisodeAnalysisSubmissionResult {
            requests += request
            return EpisodeAnalysisSubmissionResult(
                jobId = 7,
                importedRecordCount = request.source.records.size,
                retriedRecordCount = 0,
                alreadyAnalyzedRecordCount = 0,
                visibility = request.access.visibility,
                allowedUserIds = request.access.allowedUserIds,
            )
        }

        override fun findJob(userId: String, jobId: Int): EpisodeAnalysisJobReport? =
            EpisodeAnalysisJobReport(jobId, EpisodeAnalysisJobStatus.COMPLETED, episodeCount = 2, lastError = null)
                .takeIf { userId == "operator" && jobId == 7 }
    }

    private class FixedUserRegistry(
        private vararg val users: RegisteredUser,
    ) : UserRegistry {
        override fun list(): List<RegisteredUser> = users.toList()
    }

    private fun jobRoute(jobId: String) = AppConfig.ROUTE_KNOWLEDGE_IMPORT_JOB.replace("{jobId}", jobId)

    private fun operatorRegistry(): UserRegistry =
        FixedUserRegistry(RegisteredUser(UserId("operator"), "운영자"))

    private companion object {
        const val API_TOKEN = "test-token"
    }
}
