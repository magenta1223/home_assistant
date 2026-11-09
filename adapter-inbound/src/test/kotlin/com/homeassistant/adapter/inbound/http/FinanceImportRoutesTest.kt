package com.homeassistant.adapter.inbound.http

import com.homeassistant.adapter.inbound.banksalad.BanksaladExportFixture
import com.homeassistant.adapter.inbound.banksalad.BanksaladExportFixture.LedgerRow
import com.homeassistant.application.port.inbound.finance.ImportFinanceTransactions
import com.homeassistant.application.port.inbound.identity.UserRegistry
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysis
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisJobReport
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisRequest
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisSubmissionResult
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.finance.FinanceTransaction
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import com.homeassistant.infrastructure.serialization.JsonSerializer
import io.ktor.client.request.bearerAuth
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FinanceImportRoutesTest {
    @Test
    fun `an unauthenticated import is rejected`() = testApplication {
        val ledger = InMemoryLedgerImport()
        configure(ledger)

        val response = client.post(AppConfig.ROUTE_FINANCE_IMPORT) {
            contentType(ContentType.Application.Json)
            setBody(body(EXPORT))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(ledger.stored.isEmpty())
    }

    @Test
    fun `an export is saved as the authenticated user's ledger and re-importing it keeps the count`() =
        testApplication {
            val ledger = InMemoryLedgerImport()
            configure(ledger)

            val first = upload(EXPORT)
            val second = upload(EXPORT)

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(2, first.savedTransactionCount())
            assertEquals(2, second.savedTransactionCount())
            assertEquals(2, ledger.stored.size)
            assertEquals(setOf(UserId("operator")), ledger.stored.values.mapTo(mutableSetOf()) { it.ownerId })
        }

    @Test
    fun `an export without the ledger sheet is a bad request`() = testApplication {
        val ledger = InMemoryLedgerImport()
        configure(ledger)

        val response = upload(BanksaladExportFixture.export(listOf(ROW), ledgerSheet = "다른 시트"))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("가계부 내역"))
        assertTrue(ledger.stored.isEmpty())
    }

    private fun ApplicationTestBuilder.configure(ledger: ImportFinanceTransactions) {
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(
                episodeAnalysis = UnusedEpisodeAnalysis,
                httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_TOKEN) to UserId("operator")),
                users = OperatorRegistry,
                importFinanceTransactions = ledger,
            )
        }
    }

    private suspend fun ApplicationTestBuilder.upload(export: ByteArray): HttpResponse =
        client.post(AppConfig.ROUTE_FINANCE_IMPORT) {
            bearerAuth(API_TOKEN)
            contentType(ContentType.Application.Json)
            setBody(body(export))
        }

    private suspend fun HttpResponse.savedTransactionCount(): Int =
        JsonSerializer.json.parseToJsonElement(bodyAsText()).jsonObject.getValue("savedTransactionCount").jsonPrimitive.int

    private fun body(export: ByteArray) =
        """{"contentBase64":"${Base64.getEncoder().encodeToString(export)}"}"""

    private object OperatorRegistry : UserRegistry {
        override fun list(): List<RegisteredUser> = listOf(RegisteredUser(UserId("operator"), "운영자"))
    }

    private object UnusedEpisodeAnalysis : EpisodeAnalysis {
        override fun submit(request: EpisodeAnalysisRequest): EpisodeAnalysisSubmissionResult = error("unused")
        override fun findJob(userId: String, jobId: Int): EpisodeAnalysisJobReport? = error("unused")
    }

    /** Keeps one transaction per owner and key, as the ledger storage does. */
    private class InMemoryLedgerImport : ImportFinanceTransactions {
        val stored = linkedMapOf<Pair<UserId, String>, FinanceTransaction>()

        override fun import(transactions: List<FinanceTransaction>): Int {
            val saved = transactions.associateBy { it.ownerId to it.transactionKey }
            stored.putAll(saved)
            return saved.size
        }
    }

    private companion object {
        const val API_TOKEN = "test-token"
        val ROW = LedgerRow(
            date = LocalDate.of(2026, 11, 3),
            time = LocalTime.of(12, 30, 15),
            type = "지출",
            majorCategory = "식비",
            minorCategory = "한식",
            description = "테스트 식당",
            amount = -8_000,
        )
        val EXPORT = BanksaladExportFixture.export(
            listOf(ROW, ROW.copy(time = LocalTime.of(19, 5, 0), description = "테스트 치킨", amount = -21_000)),
        )
    }
}
