package com.homeassistant.application.usecase.contexttool

import com.homeassistant.application.port.inbound.contexttool.FindTransactions
import com.homeassistant.application.port.inbound.contexttool.TransactionSearch
import com.homeassistant.application.port.outbound.finance.FinanceTransactionCriteria
import com.homeassistant.application.port.outbound.finance.FinanceTransactionRepository
import com.homeassistant.domain.finance.FinanceTransaction
import com.homeassistant.domain.identity.UserId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Finds a user's transactions in [transactions] and reports them as text: the totals per currency over every match,
 * then the first [maxListed] matches.
 */
class FindTransactionsService(
    private val transactions: FinanceTransactionRepository,
    private val maxListed: Int = DEFAULT_MAX_LISTED,
) : FindTransactions {
    init {
        require(maxListed > 0) { "maxListed must be positive" }
    }

    override fun find(userId: UserId, search: TransactionSearch): String {
        val matches = transactions.findMatching(criteria(userId, search))
        return report(search, matches)
    }

    private fun criteria(userId: UserId, search: TransactionSearch) = FinanceTransactionCriteria(
        ownerId = userId,
        from = search.from.atStartOfDay(),
        to = search.to.plusDays(1).atStartOfDay(),
        category = search.category,
        descriptionContains = search.merchant,
    )

    private fun report(search: TransactionSearch, matches: List<FinanceTransaction>): String = buildString {
        appendLine("${describe(search)}: ${matches.size} found.")
        if (matches.isEmpty()) return@buildString
        appendLine("Total: ${totalsPerCurrency(matches)}")
        matches.take(maxListed).forEach { appendLine(line(it)) }
        if (matches.size > maxListed) appendLine("(${matches.size - maxListed} more not listed)")
    }.trimEnd()

    private fun describe(search: TransactionSearch): String =
        listOfNotNull(
            "Transactions from ${search.from} to ${search.to}",
            search.category?.let { "category $it" },
            search.merchant?.let { "description containing $it" },
        ).joinToString(", ")

    private fun totalsPerCurrency(matches: List<FinanceTransaction>): String =
        matches.groupBy { it.currency }
            .map { (currency, inCurrency) -> "${amount(inCurrency.sumOf { it.amount })} $currency" }
            .joinToString(", ")

    private fun line(transaction: FinanceTransaction): String =
        listOfNotNull(
            transaction.occurredAt.format(TIME),
            transaction.type,
            "${transaction.majorCategory} > ${transaction.minorCategory}",
            transaction.description,
            "${amount(transaction.amount)} ${transaction.currency}",
            transaction.paymentMethod,
            transaction.memo?.let { "memo: $it" },
        ).joinToString(" | ", prefix = "- ")

    private fun amount(value: Long): String = String.format(Locale.ROOT, "%,d", value)

    private companion object {
        const val DEFAULT_MAX_LISTED = 200
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
    }
}
