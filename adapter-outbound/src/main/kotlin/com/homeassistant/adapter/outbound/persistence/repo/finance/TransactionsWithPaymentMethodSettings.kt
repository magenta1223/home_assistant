package com.homeassistant.adapter.outbound.persistence.repo.finance

import com.homeassistant.adapter.outbound.persistence.db.tables.FinanceTransactionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.PaymentMethodSettingTable
import org.jetbrains.exposed.sql.Join
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and

/** The transactions, each with its owner's setting of its payment method when there is one. */
internal fun transactionsWithPaymentMethodSettings(): Join =
    FinanceTransactionTable.join(PaymentMethodSettingTable, JoinType.LEFT) {
        (FinanceTransactionTable.userId eq PaymentMethodSettingTable.userId) and
            (FinanceTransactionTable.paymentMethod eq PaymentMethodSettingTable.paymentMethod)
    }
