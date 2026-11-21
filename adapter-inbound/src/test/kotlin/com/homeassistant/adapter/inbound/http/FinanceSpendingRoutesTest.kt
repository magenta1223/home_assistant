package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.inbound.finance.FindMonthlySpending
import com.homeassistant.application.port.inbound.finance.MonthlySpending
import com.homeassistant.application.port.inbound.finance.SpendingGroup
import com.homeassistant.application.port.inbound.identity.UserRegistry
import com.homeassistant.application.port.inbound.sourceimport.SourceImport
import com.homeassistant.application.port.inbound.sourceimport.SourceImportJobReport
import com.homeassistant.application.port.inbound.sourceimport.SourceImportRequest
import com.homeassistant.application.port.inbound.sourceimport.SourceImportSubmissionResult
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.finance.FinanceTransaction
import com.homeassistant.domain.finance.SpendingGrouping
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import com.homeassistant.infrastructure.serialization.JsonSerializer
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.LocalDateTime
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FinanceSpendingRoutesTest {
    @Test
    fun `an unauthenticated request is rejected`() = testApplication {
        val spending = RecordingSpending()
        configure(spending)

        val response = client.get("${AppConfig.ROUTE_FINANCE_SPENDING}?month=2026-11&by=category")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(spending.requests.isEmpty())
    }

    @Test
    fun `the authenticated user's spending of the month is answered in the asked grouping`() = testApplication {
        val spending = RecordingSpending()
        configure(spending)

        val response = request("month=2026-11&by=paymentMethod")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            listOf(Triple(UserId("operator"), YearMonth.of(2026, 11), SpendingGrouping.PAYMENT_METHOD)),
            spending.requests,
        )
        assertEquals(
            JsonSerializer.json.parseToJsonElement(
                """
                {"month":"2026-11","by":"paymentMethod","groups":[{"name":"신용카드","currency":"KRW","total":-8000,
                "count":1,"transactions":[{"occurredAt":"2026-11-03T12:30:15","majorCategory":"식비",
                "minorCategory":"한식","description":"김밥천국","amount":-8000,"currency":"KRW",
                "paymentMethod":"신용카드"}]}]}
                """.trimIndent(),
            ),
            JsonSerializer.json.parseToJsonElement(response.bodyAsText()),
        )
    }

    @Test
    fun `a month or grouping that cannot be read is a bad request`() = testApplication {
        val spending = RecordingSpending()
        configure(spending)

        for (query in listOf("by=category", "month=2026-13&by=category", "month=2026-11", "month=2026-11&by=merchant")) {
            assertEquals(HttpStatusCode.BadRequest, request(query).status, query)
        }
        assertTrue(spending.requests.isEmpty())
    }

    private fun ApplicationTestBuilder.configure(spending: FindMonthlySpending) {
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(
                sourceImport = UnusedSourceImport,
                httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_TOKEN) to UserId("operator")),
                users = OperatorRegistry,
                findMonthlySpending = spending,
            )
        }
    }

    private suspend fun ApplicationTestBuilder.request(query: String): HttpResponse =
        client.get("${AppConfig.ROUTE_FINANCE_SPENDING}?$query") { bearerAuth(API_TOKEN) }

    private object OperatorRegistry : UserRegistry {
        override fun list(): List<RegisteredUser> = listOf(RegisteredUser(UserId("operator"), "운영자"))
    }

    private object UnusedSourceImport : SourceImport {
        override fun submit(request: SourceImportRequest): SourceImportSubmissionResult = error("unused")
        override fun findJob(userId: String, jobId: Int): SourceImportJobReport? = error("unused")
    }

    /** Answers one group holding [LUNCH] and keeps what it was asked for. */
    private class RecordingSpending : FindMonthlySpending {
        val requests = mutableListOf<Triple<UserId, YearMonth, SpendingGrouping>>()

        override fun find(userId: UserId, month: YearMonth, grouping: SpendingGrouping): MonthlySpending {
            requests += Triple(userId, month, grouping)
            return MonthlySpending(month, grouping, listOf(SpendingGroup("신용카드", "KRW", -8_000, 1, listOf(LUNCH))))
        }
    }

    private companion object {
        const val API_TOKEN = "test-token"
        val LUNCH = FinanceTransaction(
            ownerId = UserId("operator"),
            occurredAt = LocalDateTime.of(2026, 11, 3, 12, 30, 15),
            type = "지출",
            majorCategory = "식비",
            minorCategory = "한식",
            description = "김밥천국",
            amount = -8_000,
            currency = "KRW",
            paymentMethod = "신용카드",
            memo = null,
        )
    }
}
