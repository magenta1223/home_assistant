package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisRequest
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisResult
import com.homeassistant.application.port.input.identity.ConversationIdentity
import com.homeassistant.application.port.input.identity.HouseholdMembers
import com.homeassistant.application.port.input.identity.RegisterHouseholdMemberRequest
import com.homeassistant.common.json.JsonSerializer
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.identity.HouseholdMember
import com.homeassistant.domain.memory.MemoryCertainty
import com.homeassistant.domain.memory.MemoryProposal
import com.homeassistant.domain.memory.MemoryType
import com.homeassistant.domain.memory.MemoryVisibility
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KnowledgeInjectionRoutesTest {
    @Test
    fun `memory answers are not exposed over HTTP`() = testApplication {
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(RecordingMemoryAnalysis())
        }

        assertEquals(HttpStatusCode.NotFound, client.post("/api/memories/answer").status)
    }

    @Test
    fun `knowledge page is hosted without exposing data`() = testApplication {
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(RecordingMemoryAnalysis())
        }

        val response = client.get(AppConfig.ROUTE_KNOWLEDGE_PAGE)

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("지식 주입"))
    }

    @Test
    fun `authenticated import passes selected viewers as restricted source access`() = testApplication {
        val analysis = RecordingMemoryAnalysis()
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(
                memoryAnalysis = analysis,
                httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_TOKEN) to UserId("operator")),
                householdMembers = FixedHouseholdMembers(
                    HouseholdMember(UserId("member-1"), "첫째"),
                    HouseholdMember(UserId("member-2"), "둘째"),
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
        assertEquals(HttpStatusCode.OK, response.status)
        val captured = analysis.requests.single()
        assertEquals("operator", captured.userId)
        assertEquals(MemoryVisibility.RESTRICTED, captured.access.visibility)
        assertEquals(setOf("member-1", "member-2"), captured.access.allowedUserIds)
        assertEquals("text", captured.source.source.type)
    }

    @Test
    fun `public import cannot smuggle a restricted allow list`() = testApplication {
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(
                memoryAnalysis = RecordingMemoryAnalysis(),
                httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_TOKEN) to UserId("operator")),
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

    private class RecordingMemoryAnalysis : MemoryAnalysis {
        val requests = mutableListOf<MemoryAnalysisRequest>()

        override suspend fun execute(request: MemoryAnalysisRequest): MemoryAnalysisResult {
            requests += request
            val proposal = MemoryProposal(
                content = "memory",
                subject = "subject",
                memoryType = MemoryType.REFERENCE,
                certainty = MemoryCertainty.OBSERVED,
                evidenceIds = listOf(1),
            )
            return MemoryAnalysisResult(
                sourceType = request.source.source.type,
                sourceName = request.source.source.name,
                importedRecordCount = request.source.records.size,
                retriedRecordCount = 0,
                alreadyAnalyzedRecordCount = 0,
                visibility = request.access.visibility,
                allowedUserIds = request.access.allowedUserIds,
                memoryCount = 1,
                memories = listOf(proposal),
            )
        }
    }

    private class FixedHouseholdMembers(
        private vararg val members: HouseholdMember,
    ) : HouseholdMembers {
        override fun find(identity: ConversationIdentity): HouseholdMember? = null

        override fun register(request: RegisterHouseholdMemberRequest): HouseholdMember =
            error("not used")

        override fun list(): List<HouseholdMember> = members.toList()
    }

    private companion object {
        const val API_TOKEN = "test-token"
    }
}
