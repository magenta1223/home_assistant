package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.inbound.finance.FindMonthlySpending
import com.homeassistant.application.port.inbound.finance.MonthlySpending
import com.homeassistant.application.port.inbound.finance.SpendingGroup
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.finance.FinanceTransaction
import com.homeassistant.domain.finance.SpendingGrouping
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.YearMonth
import java.time.format.DateTimeParseException

/**
 * Answers the authenticated user's expenses of the `month` query parameter (`yyyy-MM`), grouped by the `by` query
 * parameter: `category` or `paymentMethod`.
 */
internal fun Route.financeSpendingRoutes(findMonthlySpending: FindMonthlySpending?) {
    get(AppConfig.ROUTE_FINANCE_SPENDING) {
        val principal = call.principal<HttpUserPrincipal>()
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "authentication required"))
            return@get
        }
        if (findMonthlySpending == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "finance spending unavailable"))
            return@get
        }
        val month = call.request.queryParameters["month"]?.let(::yearMonthOrNull)
        if (month == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "month must be yyyy-MM"))
            return@get
        }
        val grouping = GROUPINGS[call.request.queryParameters["by"]]
        if (grouping == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "by must be one of ${GROUPINGS.keys.joinToString()}"))
            return@get
        }
        val spending = withContext(Dispatchers.IO) { findMonthlySpending.find(principal.userId, month, grouping) }
        call.respond(HttpStatusCode.OK, spending.toResponse())
    }
}

private fun yearMonthOrNull(value: String): YearMonth? =
    try {
        YearMonth.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }

/** The `by` query parameter value of each grouping. */
private val GROUPINGS = mapOf(
    "category" to SpendingGrouping.CATEGORY,
    "paymentMethod" to SpendingGrouping.PAYMENT_METHOD,
)

private fun MonthlySpending.toResponse() = MonthlySpendingResponse(
    month = month.toString(),
    by = GROUPINGS.entries.single { it.value == grouping }.key,
    groups = groups.map { it.toResponse() },
)

private fun SpendingGroup.toResponse() = SpendingGroupResponse(
    name = name,
    currency = currency,
    total = total,
    count = count,
    transactions = transactions.map { it.toResponse() },
)

private fun FinanceTransaction.toResponse() = SpendingTransactionResponse(
    occurredAt = occurredAt.toString(),
    majorCategory = majorCategory,
    minorCategory = minorCategory,
    description = description,
    amount = amount,
    currency = currency,
    paymentMethod = paymentMethod,
    memo = memo,
)

@Serializable
private data class MonthlySpendingResponse(
    val month: String,
    val by: String,
    val groups: List<SpendingGroupResponse>,
)

@Serializable
private data class SpendingGroupResponse(
    val name: String,
    val currency: String,
    val total: Long,
    val count: Int,
    val transactions: List<SpendingTransactionResponse>,
)

@Serializable
private data class SpendingTransactionResponse(
    val occurredAt: String,
    val majorCategory: String,
    val minorCategory: String,
    val description: String,
    val amount: Long,
    val currency: String,
    val paymentMethod: String,
    val memo: String?,
)
