package com.homeassistant.app.services

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores
import com.homeassistant.feature.finance.usecase.FindMonthlySpendingService
import com.homeassistant.feature.finance.usecase.ImportFinanceTransactionsService
import com.homeassistant.feature.finance.usecase.PaymentMethodSettingsService

/** Assembles the household finance ledger. */
internal class FinanceModule(repositories: RepositoryStores) {
    /** Imports ledger transactions into storage. */
    val importTransactions = ImportFinanceTransactionsService(repositories.financeTransactions)

    /** Shows the spending of a month of a user or of their household. */
    val findMonthlySpending = FindMonthlySpendingService(
        repositories.financeTransactions,
        repositories.householdMembers,
        repositories.paymentMethodSettings,
    )

    /** Keeps which payment methods each user shares with their household and what they call them. */
    val paymentMethodSettings = PaymentMethodSettingsService(repositories.paymentMethodSettings)
}
