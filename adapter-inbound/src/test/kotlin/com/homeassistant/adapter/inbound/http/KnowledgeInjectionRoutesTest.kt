package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.inbound.sourceimport.SourceImport
import com.homeassistant.application.port.inbound.sourceimport.SourceImportJobReport
import com.homeassistant.application.port.inbound.sourceimport.SourceImportRequest
import com.homeassistant.application.port.inbound.sourceimport.SourceImportSubmissionResult
import com.homeassistant.application.port.inbound.identity.UserRegistry
import com.homeassistant.feature.sourceimport.port.outbound.SourceImportJob
import com.homeassistant.feature.sourceimport.port.outbound.SourceImportJobQueue
import com.homeassistant.feature.sourceimport.port.outbound.SourceImportSubmission
import com.homeassistant.application.port.outbound.source.SourceRecordRepository
import com.homeassistant.application.port.outbound.source.SourceRecordSaveResult
import com.homeassistant.feature.sourceimport.usecase.SourceImportService
import com.homeassistant.infrastructure.serialization.JsonSerializer
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.access.Visibility
import com.homeassistant.domain.access.AccessControl
import com.homeassistant.domain.source.SourceImportJobStatus
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.source.SourceRecord
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceReference
import com.homeassistant.domain.source.SourceReferenceDraft
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
            configureRoutes(RecordingSourceImport())
        }

        assertEquals(HttpStatusCode.NotFound, client.post("/api/episodes/answer").status)
    }

    @Test
    fun `knowledge page is hosted without exposing data`() = testApplication {
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(RecordingSourceImport())
        }

        val response = client.get("/knowledge")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("지식 주입"))
        assertTrue(response.bodyAsText().contains("/assets/app-menu.js"))
        assertTrue(response.bodyAsText().contains("/assets/http-auth-session.js"))
        assertTrue(!response.bodyAsText().contains("localStorage"))
        assertTrue(!response.bodyAsText().contains("sessionStorage"))
    }

    @Test
    fun `authenticated import passes selected viewers as restricted source access`() = testApplication {
        val analysis = RecordingSourceImport()
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(
                sourceImport = analysis,
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
                sourceImport = RecordingSourceImport(),
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
                  "alreadyStoredRecordCount":0,
                  "visibility":"PUBLIC",
                  "allowedUserIds":[]
                }""",
            ),
            Json.parseToJsonElement(response.bodyAsText()),
        )
    }

    @Test
    fun `importing records that were all stored before answers conflict with the stored count`() = testApplication {
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(
                sourceImport = SourceImportService(AlreadyStoredSourceRecords(), UnusedJobQueue(), { true }),
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

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(
            Json.parseToJsonElement(
                """{
                  "error":"all source records were stored before",
                  "alreadyStoredRecordCount":1
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
                sourceImport = RecordingSourceImport(),
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
        val analysis = RecordingSourceImport()
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(
                sourceImport = analysis,
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
                sourceImport = RecordingSourceImport(),
                httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_TOKEN) to UserId("operator")),
                users = operatorRegistry(),
            )
        }

        val response = client.get(jobRoute("7")) { bearerAuth(API_TOKEN) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            Json.parseToJsonElement("""{"jobId":7,"status":"COMPLETED","recordCount":2}"""),
            Json.parseToJsonElement(response.bodyAsText()),
        )
    }

    @Test
    fun `an analysis job the user did not submit is not found`() = testApplication {
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(
                sourceImport = RecordingSourceImport(),
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
                        sourceImport = RecordingSourceImport(),
                        httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_TOKEN) to UserId("unknown")),
                        users = operatorRegistry(),
                    )
                }
                client.get(AppConfig.ROUTE_HEALTH)
            }
        }
    }

    private class RecordingSourceImport : SourceImport {
        val requests = mutableListOf<SourceImportRequest>()

        override fun submit(request: SourceImportRequest): SourceImportSubmissionResult {
            requests += request
            return SourceImportSubmissionResult(
                jobId = 7,
                importedRecordCount = request.source.records.size,
                alreadyStoredRecordCount = 0,
                visibility = request.access.visibility,
                allowedUserIds = request.access.allowedUserIds,
            )
        }

        override fun findJob(userId: String, jobId: Int): SourceImportJobReport? =
            SourceImportJobReport(jobId, SourceImportJobStatus.COMPLETED, recordCount = 2, lastError = null)
                .takeIf { userId == "operator" && jobId == 7 }
    }

    /** Source records that were all stored before, so the submission brings nothing new. */
    private class AlreadyStoredSourceRecords : SourceRecordRepository {
        override fun save(source: SourceDescriptor, records: List<SourceRecordDraft>, submittedBy: UserId, access: AccessControl) =
            SourceRecordSaveResult.Saved(
                newRecords = emptyList(),
                alreadyStoredRecords = records.mapIndexed { index, draft -> SourceRecord(index + 1, draft.deduplicationKey, draft.content, access) },
            )

        override fun saveReference(reference: SourceReferenceDraft): SourceReference = error("no reference is submitted")

        override fun findReferenceContent(referenceId: Int): SourceReferenceDraft? = null
    }

    /** A job queue that a submission with nothing new never reaches. */
    private class UnusedJobQueue : SourceImportJobQueue {
        override fun enqueue(submission: SourceImportSubmission, now: Long): SourceImportJob = error("nothing is queued")

        override fun claimNext(now: Long, retryBefore: Long, staleProcessingBefore: Long, maxAttempts: Int): SourceImportJob? = null

        override fun markCompleted(jobId: Int, expectedAttempt: Int, recordCount: Int, now: Long) = false

        override fun markFailed(jobId: Int, expectedAttempt: Int, error: String, now: Long) = false

        override fun findRequestedBy(jobId: Int, userId: UserId): SourceImportJob? = null
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
