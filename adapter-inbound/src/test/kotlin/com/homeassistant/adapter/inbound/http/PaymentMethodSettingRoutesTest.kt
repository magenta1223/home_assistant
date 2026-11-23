package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.inbound.finance.PaymentMethodSettings
import com.homeassistant.application.port.inbound.finance.UnknownPaymentMethodException
import com.homeassistant.application.port.inbound.identity.UserRegistry
import com.homeassistant.application.port.inbound.sourceimport.SourceImport
import com.homeassistant.application.port.inbound.sourceimport.SourceImportJobReport
import com.homeassistant.application.port.inbound.sourceimport.SourceImportRequest
import com.homeassistant.application.port.inbound.sourceimport.SourceImportSubmissionResult
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.finance.PaymentMethodSetting
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import com.homeassistant.infrastructure.serialization.JsonSerializer
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaymentMethodSettingRoutesTest {
    @Test
    fun `payment method settings need an authenticated user`() = testApplication {
        configure(RecordingSettings())

        assertEquals(HttpStatusCode.Unauthorized, client.get(AppConfig.ROUTE_FINANCE_PAYMENT_METHODS).status)
    }

    @Test
    fun `the caller's payment methods are listed with their settings`() = testApplication {
        configure(RecordingSettings())

        val response = client.get(AppConfig.ROUTE_FINANCE_PAYMENT_METHODS) { bearerAuth(API_TOKEN) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertJson(
            """{"paymentMethods":[{"paymentMethod":"신용카드","shared":true,"alias":"식비"},
            {"paymentMethod":"체크카드","shared":false}]}""",
            response,
        )
    }

    @Test
    fun `the caller's choices are saved as theirs, with a blank alias meaning none`() = testApplication {
        val settings = RecordingSettings()
        configure(settings)

        val response = save("""{"paymentMethods":[{"paymentMethod":"신용카드","shared":true,"alias":"  "}]}""")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf(PaymentMethodSetting(OPERATOR, "신용카드", shared = true, alias = null)), settings.saved)
    }

    @Test
    fun `an unknown payment method or a too long alias is a bad request`() = testApplication {
        val settings = RecordingSettings()
        configure(settings)

        assertEquals(
            HttpStatusCode.BadRequest,
            save("""{"paymentMethods":[{"paymentMethod":"$UNKNOWN","shared":true}]}""").status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            save("""{"paymentMethods":[{"paymentMethod":"신용카드","shared":true,"alias":"${"가".repeat(31)}"}]}""").status,
        )
        assertTrue(settings.saved.isEmpty())
    }

    private suspend fun ApplicationTestBuilder.save(body: String): HttpResponse =
        client.put(AppConfig.ROUTE_FINANCE_PAYMENT_METHODS) {
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

    private fun ApplicationTestBuilder.configure(settings: PaymentMethodSettings) {
        application {
            install(ContentNegotiation) { json(JsonSerializer.json) }
            configureRoutes(
                sourceImport = UnusedSourceImport,
                httpApiKeys = mapOf(HttpApiKeyConfig.hash(API_TOKEN) to OPERATOR),
                users = OperatorRegistry,
                paymentMethodSettings = settings,
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

    /** Lists two fixed payment methods, refuses [UNKNOWN], and keeps what it saved. */
    private class RecordingSettings : PaymentMethodSettings {
        val saved = mutableListOf<PaymentMethodSetting>()

        override fun listOf(userId: UserId): List<PaymentMethodSetting> = listOf(
            PaymentMethodSetting(userId, "신용카드", shared = true, alias = "식비"),
            PaymentMethodSetting(userId, "체크카드", shared = false, alias = null),
        )

        override fun save(settings: List<PaymentMethodSetting>): List<PaymentMethodSetting> {
            settings.firstOrNull { it.paymentMethod == UNKNOWN }?.let { throw UnknownPaymentMethodException(it.paymentMethod) }
            saved += settings
            return settings
        }
    }

    private companion object {
        const val API_TOKEN = "test-token"
        const val UNKNOWN = "남의카드"
        val OPERATOR = UserId("operator")
    }
}
