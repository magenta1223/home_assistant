package com.homeassistant.app.services

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores
import com.homeassistant.application.usecase.finance.ImportFinanceTransactionsService

/** Assembles the household finance ledger. */
internal class FinanceModule(repositories: RepositoryStores) {
    /** Imports ledger transactions into storage. */
    val importTransactions = ImportFinanceTransactionsService(repositories.financeTransactions)
}
