package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.inbound.household.AlreadyInHouseholdException
import com.homeassistant.application.port.inbound.household.HouseholdInvitation
import com.homeassistant.application.port.inbound.household.Households
import com.homeassistant.application.port.inbound.household.InvalidInvitationException
import com.homeassistant.application.port.inbound.identity.UserRegistry
import com.homeassistant.application.port.inbound.sourceimport.SourceImport
import com.homeassistant.application.port.inbound.sourceimport.SourceImportJobReport
import com.homeassistant.application.port.inbound.sourceimport.SourceImportRequest
import com.homeassistant.application.port.inbound.sourceimport.SourceImportSubmissionResult
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import com.homeassistant.infrastructure.serialization.JsonSerializer
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class HouseholdRoutesTest {
    @Test
    fun `household routes need an authenticated user`() = testApplication {
        configure(ScriptedHouseholds())

        assertEquals(HttpStatusCode.Unauthorized, client.get(AppConfig.ROUTE_HOUSEHOLD_MEMBERS).status)
        assertEquals(HttpStatusCode.Unauthorized, client.post(AppConfig.ROUTE_HOUSEHOLD_INVITATIONS).status)
    }

    @Test
    fun `the caller's household members are answered by name`() = testApplication {
        val households = ScriptedHouseholds()
        configure(households)

        val response = client.get(AppConfig.ROUTE_HOUSEHOLD_MEMBERS) { bearerAuth(API_TOKEN) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertJson("""{"members":["김동훈","홍승민"]}""", response)
        assertEquals(listOf("membersOf operator"), households.calls)
    }

    @Test
    fun `an invitation is issued for the caller`() = testApplication {
        val households = ScriptedHouseholds()
        configure(households)

        val response = client.post(AppConfig.ROUTE_HOUSEHOLD_INVITATIONS) { bearerAuth(API_TOKEN) }

        assertEquals(HttpStatusCode.Created, response.status)
        assertJson("""{"code":"ABCD2345","expiresAt":"2026-11-23T00:00:00Z"}""", response)
        assertEquals(listOf("invite operator"), households.calls)
    }

    @Test
    fun `joining answers the members, or why the code was refused`() = testApplication {
        val households = ScriptedHouseholds()
        configure(households)

        val joined = join("""{"code":"ABCD2345"}""")
        assertEquals(HttpStatusCode.OK, joined.status)
        assertJson("""{"members":["김동훈","홍승민"]}""", joined)
        assertEquals(listOf("join operator ABCD2345"), households.calls)

        assertEquals(HttpStatusCode.BadRequest, join("""{"code":"$INVALID"}""").status)
        assertEquals(HttpStatusCode.Conflict, join("""{"code":"$TAKEN"}""").status)
        assertEquals(HttpStatusCode.BadRequest, join("""{}""").status)
    }

    private suspend fun ApplicationTestBuilder.join(body: String): HttpResponse =
        client.post(AppConfig.ROUTE_HOUSEHOLD_JOIN) {
            bearerAuth(API_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun assertJson(expected: String, response: HttpResponse) {
        assertEquals(
            JsonSerializer.json.parseToJsonElement(expected),
            JsonSerializer.json.parseToJsonElement(response.bodyAsText()),
        )
    }

    private fun ApplicationTestBuilder.configure(households: Households) {
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(
                sourceImport = UnusedSourceImport,
                httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_TOKEN) to OPERATOR),
                users = OperatorRegistry,
                households = households,
            )
        }
    }

    private object OperatorRegistry : UserRegistry {
        override fun list(): List<RegisteredUser> = listOf(RegisteredUser(OPERATOR, "운영자"))
    }

    private object UnusedSourceImport : SourceImport {
        override fun submit(request: SourceImportRequest): SourceImportSubmissionResult = error("unused")
        override fun findJob(userId: String, jobId: Int): SourceImportJobReport? = error("unused")
    }

    /** Answers fixed members and invitation, refuses [INVALID] and [TAKEN], and keeps what it was asked. */
    private class ScriptedHouseholds : Households {
        val calls = mutableListOf<String>()

        override fun membersOf(userId: UserId): List<RegisteredUser> {
            calls += "membersOf ${userId.value}"
            return MEMBERS
        }

        override fun invite(userId: UserId): HouseholdInvitation {
            calls += "invite ${userId.value}"
            return HouseholdInvitation("ABCD2345", Instant.parse("2026-11-23T00:00:00Z"))
        }

        override fun join(userId: UserId, code: String): List<RegisteredUser> = when (code) {
            INVALID -> throw InvalidInvitationException()
            TAKEN -> throw AlreadyInHouseholdException()
            else -> MEMBERS.also { calls += "join ${userId.value} $code" }
        }
    }

    private companion object {
        const val API_TOKEN = "test-token"
        const val INVALID = "INVALID0"
        const val TAKEN = "TAKEN000"
        val OPERATOR = UserId("operator")
        val MEMBERS = listOf(RegisteredUser(UserId("member-1"), "김동훈"), RegisteredUser(UserId("member-2"), "홍승민"))
    }
}
