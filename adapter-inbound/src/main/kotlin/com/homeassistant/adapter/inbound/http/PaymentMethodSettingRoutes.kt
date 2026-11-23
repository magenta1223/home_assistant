package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.inbound.finance.PaymentMethodSettings
import com.homeassistant.application.port.inbound.finance.UnknownPaymentMethodException
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.finance.PaymentMethodSetting
import com.homeassistant.domain.identity.UserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Lists the authenticated user's payment methods with whether their household sees each and its alias, and stores
 * the user's choices.
 */
internal fun Route.paymentMethodSettingRoutes(paymentMethodSettings: PaymentMethodSettings?) {
    get(AppConfig.ROUTE_FINANCE_PAYMENT_METHODS) {
        val principal = call.principal<HttpUserPrincipal>()
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "authentication required"))
            return@get
        }
        if (paymentMethodSettings == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "payment method settings unavailable"))
            return@get
        }
        val settings = withContext(Dispatchers.IO) { paymentMethodSettings.listOf(principal.userId) }
        call.respond(HttpStatusCode.OK, PaymentMethodSettingsMessage(settings.map { it.toMessage() }))
    }
    put(AppConfig.ROUTE_FINANCE_PAYMENT_METHODS) {
        val principal = call.principal<HttpUserPrincipal>()
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "authentication required"))
            return@put
        }
        if (paymentMethodSettings == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "payment method settings unavailable"))
            return@put
        }
        val settings = try {
            call.receive<PaymentMethodSettingsMessage>().paymentMethods.map { it.toSetting(principal.userId) }
        } catch (error: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "invalid request")))
            return@put
        }
        try {
            val saved = withContext(Dispatchers.IO) { paymentMethodSettings.save(settings) }
            call.respond(HttpStatusCode.OK, PaymentMethodSettingsMessage(saved.map { it.toMessage() }))
        } catch (error: UnknownPaymentMethodException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to error.message))
        }
    }
}

private fun PaymentMethodSetting.toMessage() = PaymentMethodSettingMessage(paymentMethod, shared, alias)

@Serializable
private data class PaymentMethodSettingsMessage(val paymentMethods: List<PaymentMethodSettingMessage>)

@Serializable
private data class PaymentMethodSettingMessage(
    val paymentMethod: String,
    val shared: Boolean,
    val alias: String? = null,
) {
    /** The setting of [ownerId]; an alias of only spaces means no alias. */
    fun toSetting(ownerId: UserId) =
        PaymentMethodSetting(ownerId, paymentMethod, shared, alias?.trim()?.ifEmpty { null })
}
