package com.homeassistant.adapter.outbound.persistence.repo.finance

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.adapter.outbound.persistence.repo.RepositoryStores
import com.homeassistant.domain.finance.FinanceTransaction
import com.homeassistant.domain.finance.PaymentMethodSetting
import com.homeassistant.domain.finance.SpendingGrouping
import com.homeassistant.domain.identity.UserId
import com.homeassistant.feature.finance.port.outbound.FinanceTransactionCriteria
import com.homeassistant.feature.finance.port.outbound.SpendingTotal
import com.homeassistant.feature.finance.port.outbound.TransactionOwners
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class PaymentMethodSettingRepositoryTest {
    @Test
    fun `a user's payment methods are those they paid with, unshared and unnamed until set`() = withStores { stores ->
        stores.financeTransactions.save(listOf(LUNCH, COFFEE, PARTNERS_LUNCH))
        stores.paymentMethodSettings.save(listOf(PaymentMethodSetting(OWNER, "체크카드", shared = true, alias = "생활비")))

        assertEquals(
            listOf(
                PaymentMethodSetting(OWNER, "신용카드", shared = false, alias = null),
                PaymentMethodSetting(OWNER, "체크카드", shared = true, alias = "생활비"),
            ),
            stores.paymentMethodSettings.findPaymentMethodsOf(OWNER),
        )
    }

    @Test
    fun `saving a setting again replaces it`() = withStores { stores ->
        val settings = stores.paymentMethodSettings
        settings.save(listOf(PaymentMethodSetting(OWNER, "신용카드", shared = true, alias = "식비")))

        settings.save(listOf(PaymentMethodSetting(OWNER, "신용카드", shared = false, alias = null)))

        assertEquals(
            listOf(PaymentMethodSetting(OWNER, "신용카드", shared = false, alias = null)),
            settings.findSettingsOf(setOf(OWNER, PARTNER)),
        )
    }

    @Test
    fun `the shared transactions of owners are only those paid with a shared payment method`() = withStores { stores ->
        stores.financeTransactions.save(listOf(LUNCH, COFFEE, PARTNERS_LUNCH))
        stores.paymentMethodSettings.save(
            listOf(
                PaymentMethodSetting(OWNER, "신용카드", shared = true, alias = "식비"),
                PaymentMethodSetting(OWNER, "체크카드", shared = false, alias = null),
                PaymentMethodSetting(PARTNER, "신용카드", shared = true, alias = "식비"),
            ),
        )
        val shared = criteria(TransactionOwners.SharedBy(setOf(OWNER, PARTNER)))

        assertEquals(listOf(LUNCH, PARTNERS_LUNCH), stores.financeTransactions.findMatching(shared))
        assertEquals(
            listOf(SpendingTotal("식비", "KRW", -20_000, 2)),
            stores.financeTransactions.findTotalsBy(SpendingGrouping.PAYMENT_METHOD, shared),
        )
    }

    @Test
    fun `every transaction is found for all of an owner's, grouped by alias or payment method`() =
        withStores { stores ->
            stores.financeTransactions.save(listOf(LUNCH, COFFEE))
            stores.paymentMethodSettings.save(listOf(PaymentMethodSetting(OWNER, "신용카드", shared = false, alias = "식비")))
            val all = criteria(TransactionOwners.All(setOf(OWNER)))

            assertEquals(listOf(LUNCH, COFFEE), stores.financeTransactions.findMatching(all))
            assertEquals(
                listOf(SpendingTotal("식비", "KRW", -8_000, 1), SpendingTotal("체크카드", "KRW", -5_000, 1)),
                stores.financeTransactions.findTotalsBy(SpendingGrouping.PAYMENT_METHOD, all),
            )
        }

    private fun criteria(owners: TransactionOwners) =
        FinanceTransactionCriteria(owners, LocalDateTime.of(2026, 11, 1, 0, 0), LocalDateTime.of(2026, 12, 1, 0, 0))

    private fun withStores(test: (RepositoryStores) -> Unit) {
        val path = Files.createTempFile("payment-method-settings", ".db")
        try {
            test(RepositoryFactory.create(path.toString()))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private companion object {
        val OWNER = UserId("member-1")
        val PARTNER = UserId("member-2")
        val LUNCH = FinanceTransaction(
            ownerId = OWNER,
            occurredAt = LocalDateTime.of(2026, 11, 3, 12, 30, 15),
            type = "지출",
            majorCategory = "식비",
            minorCategory = "한식",
            description = "김밥천국",
            amount = -8_000,
            currency = "KRW",
            paymentMethod = "신용카드",
            memo = null,
        )
        val COFFEE = LUNCH.copy(
            occurredAt = LocalDateTime.of(2026, 11, 3, 13, 0, 0),
            description = "스타벅스",
            amount = -5_000,
            paymentMethod = "체크카드",
        )
        val PARTNERS_LUNCH = LUNCH.copy(
            ownerId = PARTNER,
            occurredAt = LocalDateTime.of(2026, 11, 4, 12, 0, 0),
            description = "백반집",
            amount = -12_000,
        )
    }
}
