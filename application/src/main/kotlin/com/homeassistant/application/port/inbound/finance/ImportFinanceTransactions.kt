package com.homeassistant.application.port.inbound.finance

import com.homeassistant.domain.finance.FinanceTransaction

/** Brings transactions read from a household finance ledger into storage. */
fun interface ImportFinanceTransactions {
    /**
     * Stores [transactions] under their owners. A transaction imported before takes the submitted type, categories
     * and memo; earlier transactions missing from [transactions] stay stored.
     */
    fun import(transactions: List<FinanceTransaction>)
}
