package com.homeassistant.adapter.outbound.persistence.db.tables

import org.jetbrains.exposed.sql.Table

internal object PaymentMethodSettingTable : Table("finance_payment_method_settings") {
    val userId = text("user_id")
    val paymentMethod = text("payment_method")
    val shared = bool("shared").default(false)
    val alias = text("alias").nullable()
    override val primaryKey = PrimaryKey(userId, paymentMethod)
}
