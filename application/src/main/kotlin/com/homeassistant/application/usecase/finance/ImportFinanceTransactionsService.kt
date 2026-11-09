package com.homeassistant.application.usecase.finance

import com.homeassistant.application.port.inbound.finance.ImportFinanceTransactions
import com.homeassistant.application.port.outbound.finance.FinanceTransactionRepository
import com.homeassistant.domain.finance.FinanceTransaction

/** Stores imported ledger transactions in the [FinanceTransactionRepository]. */
class ImportFinanceTransactionsService(
    private val financeTransactions: FinanceTransactionRepository,
) : ImportFinanceTransactions {

    /** Saves [transactions] and returns how many were saved; an empty import writes nothing. */
    override fun import(transactions: List<FinanceTransaction>): Int {
        if (transactions.isEmpty()) return 0
        return financeTransactions.save(transactions).size
    }
}
