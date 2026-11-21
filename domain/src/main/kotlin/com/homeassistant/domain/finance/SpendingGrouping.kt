package com.homeassistant.domain.finance

/** What spending is grouped by. */
enum class SpendingGrouping {
    /** The ledger's top-level category, [FinanceTransaction.majorCategory]. */
    CATEGORY,

    /** The account or card that paid, [FinanceTransaction.paymentMethod]. */
    PAYMENT_METHOD,
}
