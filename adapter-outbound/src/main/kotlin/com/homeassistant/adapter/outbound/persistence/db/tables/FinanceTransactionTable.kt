package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

internal object FinanceTransactionTable : Table("finance_transactions") {
    val userId = text("user_id")
    val transactionKey = text("transaction_key")
    val occurredAt = long("occurred_at")
    val type = text("type")
    val majorCategory = text("major_category")
    val minorCategory = text("minor_category")
    val description = text("description")
    val amount = long("amount")
    val currency = text("currency")
    val paymentMethod = text("payment_method")
    val memo = text("memo").nullable()
    override val primaryKey = PrimaryKey(userId, transactionKey)

    init {
        index(false, userId, occurredAt)
    }
}
