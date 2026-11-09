package com.homeassistant.application.usecase.finance

import com.homeassistant.application.port.inbound.finance.ImportFinanceTransactions
import com.homeassistant.application.port.outbound.finance.FinanceTransactionRepository
import com.homeassistant.domain.finance.FinanceTransaction

/** Stores imported ledger transactions in the [FinanceTransactionRepository]. */
class ImportFinanceTransactionsService(
    private val financeTransactions: FinanceTransactionRepository,
) : ImportFinanceTransactions {

    /** Saves [transactions]; an empty import writes nothing. */
    override fun import(transactions: List<FinanceTransaction>) {
        if (transactions.isEmpty()) return
        financeTransactions.save(transactions)
    }
}
