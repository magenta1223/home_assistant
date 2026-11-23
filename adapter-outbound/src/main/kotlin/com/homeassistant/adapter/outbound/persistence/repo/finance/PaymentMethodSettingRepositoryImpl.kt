package com.homeassistant.adapter.outbound.persistence.repo.finance

import com.homeassistant.adapter.outbound.persistence.db.SqliteDatabases
import com.homeassistant.adapter.outbound.persistence.db.tables.FinanceTransactionTable
import com.homeassistant.adapter.outbound.persistence.db.tables.PaymentMethodSettingTable
import com.homeassistant.domain.finance.PaymentMethodSetting
import com.homeassistant.domain.identity.UserId
import com.homeassistant.feature.finance.port.outbound.PaymentMethodSettingRepository
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.selectAll

internal class PaymentMethodSettingRepositoryImpl(private val db: SqliteDatabases) : PaymentMethodSettingRepository {

    override fun findPaymentMethodsOf(userId: UserId): List<PaymentMethodSetting> = db.read {
        transactionsWithPaymentMethodSettings()
            .select(FinanceTransactionTable.paymentMethod, PaymentMethodSettingTable.shared, PaymentMethodSettingTable.alias)
            .withDistinct()
            .where { FinanceTransactionTable.userId eq userId.value }
            .orderBy(FinanceTransactionTable.paymentMethod)
            .map {
                PaymentMethodSetting(
                    ownerId = userId,
                    paymentMethod = it[FinanceTransactionTable.paymentMethod],
                    shared = it.getOrNull(PaymentMethodSettingTable.shared) ?: false,
                    alias = it[PaymentMethodSettingTable.alias],
                )
            }
    }

    override fun findSettingsOf(ownerIds: Set<UserId>): List<PaymentMethodSetting> = db.read {
        PaymentMethodSettingTable.selectAll()
            .where { PaymentMethodSettingTable.userId inList ownerIds.map(UserId::value) }
            .map { it.toPaymentMethodSetting() }
    }

    override fun save(settings: List<PaymentMethodSetting>): List<PaymentMethodSetting> = db.write {
        PaymentMethodSettingTable.batchUpsert(settings, shouldReturnGeneratedValues = false) { setting ->
            this[PaymentMethodSettingTable.userId] = setting.ownerId.value
            this[PaymentMethodSettingTable.paymentMethod] = setting.paymentMethod
            this[PaymentMethodSettingTable.shared] = setting.shared
            this[PaymentMethodSettingTable.alias] = setting.alias
        }
        settings
    }

    private fun ResultRow.toPaymentMethodSetting() = PaymentMethodSetting(
        ownerId = UserId(this[PaymentMethodSettingTable.userId]),
        paymentMethod = this[PaymentMethodSettingTable.paymentMethod],
        shared = this[PaymentMethodSettingTable.shared],
        alias = this[PaymentMethodSettingTable.alias],
    )
}
