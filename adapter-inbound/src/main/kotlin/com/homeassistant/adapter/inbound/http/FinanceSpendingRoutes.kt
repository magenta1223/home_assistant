package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.port.inbound.finance.FindMonthlySpending
import com.homeassistant.application.port.inbound.finance.MonthlySpending
import com.homeassistant.application.port.inbound.finance.SpendingGroup
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.finance.FinanceTransaction
import com.homeassistant.domain.finance.SpendingGrouping
import com.homeassistant.domain.finance.SpendingScope
import com.homeassistant.domain.identity.UserId
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
 * Answers the expenses of the `month` query parameter (`yyyy-MM`) of the authenticated user (`scope=mine`) or of
 * their household (`scope=household`), grouped by the `by` query parameter: `category` or `paymentMethod`. In the
 * household scope each expense names its owner.
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
        val scope = SCOPES[call.request.queryParameters["scope"]]
        if (scope == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "scope must be one of ${SCOPES.keys.joinToString()}"))
            return@get
        }
        val spending = withContext(Dispatchers.IO) {
            findMonthlySpending.find(principal.userId, month, grouping, scope)
        }
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

/** The `scope` query parameter value of each scope. */
private val SCOPES = mapOf(
    "mine" to SpendingScope.MINE,
    "household" to SpendingScope.HOUSEHOLD,
)

private fun MonthlySpending.toResponse(): MonthlySpendingResponse {
    val memberNames = members.associate { it.userId to it.displayName }
    return MonthlySpendingResponse(
        month = month.toString(),
        by = GROUPINGS.entries.single { it.value == grouping }.key,
        scope = SCOPES.entries.single { it.value == scope }.key,
        members = members.map { it.displayName },
        groups = groups.map { it.toResponse(memberNames) },
    )
}

private fun SpendingGroup.toResponse(memberNames: Map<UserId, String>) = SpendingGroupResponse(
    name = name,
    currency = currency,
    total = total,
    count = count,
    transactions = transactions.map { it.toResponse(memberNames[it.ownerId]) },
)

private fun FinanceTransaction.toResponse(owner: String?) = SpendingTransactionResponse(
    owner = owner,
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
    val scope: String,
    val members: List<String>,
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
    val owner: String?,
    val occurredAt: String,
    val majorCategory: String,
    val minorCategory: String,
    val description: String,
    val amount: Long,
    val currency: String,
    val paymentMethod: String,
    val memo: String?,
)
