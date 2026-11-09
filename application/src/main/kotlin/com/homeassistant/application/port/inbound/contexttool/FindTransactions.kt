package com.homeassistant.application.port.inbound.contexttool

import java.time.LocalDate

/** Lets an agent look up household finance transactions while it answers, as the user its context token stands for. */
fun interface FindTransactions {
    /**
     * That user's transactions matching [search], listed oldest first with their total per currency, as text.
     *
     * @throws ContextAccessExpiredException when [contextToken] was never issued, was revoked or has expired.
     */
    fun find(contextToken: String, search: TransactionSearch): String
}

/**
 * Transactions from [from] through [to], both inclusive; when given, only those in [category] (a major or minor
 * category) and only those whose description contains [merchant].
 */
data class TransactionSearch(
    val from: LocalDate,
    val to: LocalDate,
    val category: String? = null,
    val merchant: String? = null,
) {
    init {
        require(!to.isBefore(from)) { "to must not be before from" }
    }
}
