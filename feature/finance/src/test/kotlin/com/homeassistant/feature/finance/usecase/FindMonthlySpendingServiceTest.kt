package com.homeassistant.feature.finance.usecase

import com.homeassistant.application.port.inbound.finance.MonthlySpending
import com.homeassistant.application.port.inbound.finance.SpendingGroup
import com.homeassistant.application.port.inbound.finance.SpendingTransaction
import com.homeassistant.domain.finance.FinanceTransaction
import com.homeassistant.domain.finance.PaymentMethodSetting
import com.homeassistant.domain.finance.SpendingGrouping
import com.homeassistant.domain.finance.SpendingScope
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import com.homeassistant.feature.finance.port.outbound.FinanceTransactionCriteria
import com.homeassistant.feature.finance.port.outbound.FinanceTransactionRepository
import com.homeassistant.feature.finance.port.outbound.HouseholdMembers
import com.homeassistant.feature.finance.port.outbound.PaymentMethodSettingRepository
import com.homeassistant.feature.finance.port.outbound.SpendingTotal
import com.homeassistant.feature.finance.port.outbound.TransactionOwners
import java.time.LocalDateTime
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals

class FindMonthlySpendingServiceTest {
    @Test
    fun `every expense of the user is asked for in the user's own scope`() {
        val ledger = StoredLedger(totals = emptyList(), transactions = emptyList())

        val spending = service(ledger).find(MEMBER, NOVEMBER, CATEGORY, SpendingScope.MINE)

        val expensesInNovember = novemberExpensesOf(TransactionOwners.All(setOf(MEMBER)))
        assertEquals(listOf(expensesInNovember, expensesInNovember), ledger.criteria)
        assertEquals(emptyList(), spending.members)
    }

    @Test
    fun `the household scope asks for the shared expenses of every member and names them`() {
        val ledger = StoredLedger(totals = emptyList(), transactions = emptyList())

        val spending = service(ledger).find(MEMBER, NOVEMBER, CATEGORY, SpendingScope.HOUSEHOLD)

        assertEquals(
            novemberExpensesOf(TransactionOwners.SharedBy(setOf(PARTNER.userId, MEMBER))),
            ledger.criteria.first(),
        )
        assertEquals(listOf(PARTNER, SELF), spending.members)
    }

    @Test
    fun `someone in no household sees every expense of their own in the household scope`() {
        val ledger = StoredLedger(totals = emptyList(), transactions = emptyList())

        val spending = service(ledger, household = HouseholdMembers { emptyList() })
            .find(MEMBER, NOVEMBER, CATEGORY, SpendingScope.HOUSEHOLD)

        assertEquals(novemberExpensesOf(TransactionOwners.All(setOf(MEMBER))), ledger.criteria.first())
        assertEquals(emptyList(), spending.members)
    }

    @Test
    fun `each total becomes a group in its order, holding the expenses of its value and currency`() {
        val ledger = StoredLedger(
            totals = listOf(SpendingTotal("신용카드", "KRW", -29_000, 2), SpendingTotal("체크카드", "KRW", -5_000, 1)),
            transactions = listOf(LUNCH, COFFEE, DINNER),
        )

        val spending = service(ledger).find(MEMBER, NOVEMBER, SpendingGrouping.PAYMENT_METHOD, SpendingScope.MINE)

        assertEquals(
            MonthlySpending(
                month = NOVEMBER,
                grouping = SpendingGrouping.PAYMENT_METHOD,
                scope = SpendingScope.MINE,
                members = emptyList(),
                groups = listOf(
                    SpendingGroup("신용카드", "KRW", -29_000, 2, listOf(named(LUNCH), named(DINNER))),
                    SpendingGroup("체크카드", "KRW", -5_000, 1, listOf(named(COFFEE))),
                ),
            ),
            spending,
        )
        assertEquals(listOf(SpendingGrouping.PAYMENT_METHOD), ledger.groupings)
    }

    @Test
    fun `a payment method with an alias is named and grouped by the alias`() {
        val ledger = StoredLedger(
            totals = listOf(SpendingTotal("식비", "KRW", -29_000, 2), SpendingTotal("체크카드", "KRW", -5_000, 1)),
            transactions = listOf(LUNCH, COFFEE, DINNER),
        )
        val settings = PaymentMethodSettingRepositoryStub(
            listOf(PaymentMethodSetting(MEMBER, "신용카드", shared = true, alias = "식비")),
        )

        val spending = service(ledger, settings = settings)
            .find(MEMBER, NOVEMBER, SpendingGrouping.PAYMENT_METHOD, SpendingScope.MINE)

        assertEquals(
            listOf(
                SpendingGroup("식비", "KRW", -29_000, 2, listOf(named(LUNCH, "식비"), named(DINNER, "식비"))),
                SpendingGroup("체크카드", "KRW", -5_000, 1, listOf(named(COFFEE))),
            ),
            spending.groups,
        )
        assertEquals(listOf(setOf(MEMBER)), settings.askedOwners)
    }

    @Test
    fun `a value spent in two currencies forms a group per currency`() {
        val dollarLunch = LUNCH.copy(amount = -12, currency = "USD")
        val ledger = StoredLedger(
            totals = listOf(SpendingTotal("식비", "KRW", -8_000, 1), SpendingTotal("식비", "USD", -12, 1)),
            transactions = listOf(LUNCH, dollarLunch),
        )

        val spending = service(ledger).find(MEMBER, NOVEMBER, CATEGORY, SpendingScope.MINE)

        assertEquals(
            listOf(
                SpendingGroup("식비", "KRW", -8_000, 1, listOf(named(LUNCH))),
                SpendingGroup("식비", "USD", -12, 1, listOf(named(dollarLunch))),
            ),
            spending.groups,
        )
    }

    private fun service(
        ledger: FinanceTransactionRepository,
        household: HouseholdMembers = HOUSEHOLD,
        settings: PaymentMethodSettingRepository = PaymentMethodSettingRepositoryStub(emptyList()),
    ) = FindMonthlySpendingService(ledger, household, settings)

    private fun named(expense: FinanceTransaction, name: String = expense.paymentMethod) =
        SpendingTransaction(expense, name)

    private fun novemberExpensesOf(owners: TransactionOwners) = FinanceTransactionCriteria(
        owners = owners,
        from = LocalDateTime.of(2026, 11, 1, 0, 0),
        to = LocalDateTime.of(2026, 12, 1, 0, 0),
        type = FinanceTransaction.EXPENSE_TYPE,
    )

    /** Answers with the given [totals] and [transactions] and keeps what it was asked for. */
    private class StoredLedger(
        private val totals: List<SpendingTotal>,
        private val transactions: List<FinanceTransaction>,
    ) : FinanceTransactionRepository {
        val criteria = mutableListOf<FinanceTransactionCriteria>()
        val groupings = mutableListOf<SpendingGrouping>()

        override fun save(transactions: List<FinanceTransaction>): List<FinanceTransaction> = error("unused")

        override fun findMatching(criteria: FinanceTransactionCriteria): List<FinanceTransaction> {
            this.criteria += criteria
            return transactions
        }

        override fun findTotalsBy(grouping: SpendingGrouping, criteria: FinanceTransactionCriteria): List<SpendingTotal> {
            groupings += grouping
            this.criteria += criteria
            return totals
        }
    }

    /** Answers with the given stored [settings] and keeps whose settings it was asked for. */
    private class PaymentMethodSettingRepositoryStub(
        private val settings: List<PaymentMethodSetting>,
    ) : PaymentMethodSettingRepository {
        val askedOwners = mutableListOf<Set<UserId>>()

        override fun findPaymentMethodsOf(userId: UserId): List<PaymentMethodSetting> = error("unused")

        override fun findSettingsOf(ownerIds: Set<UserId>): List<PaymentMethodSetting> {
            askedOwners += ownerIds
            return settings
        }

        override fun save(settings: List<PaymentMethodSetting>): List<PaymentMethodSetting> = error("unused")
    }

    private companion object {
        val MEMBER = UserId("member-1")
        val SELF = RegisteredUser(MEMBER, "김동훈")
        val PARTNER = RegisteredUser(UserId("member-2"), "Alex")
        val HOUSEHOLD = HouseholdMembers { listOf(PARTNER, SELF) }
        val CATEGORY = SpendingGrouping.CATEGORY
        val NOVEMBER: YearMonth = YearMonth.of(2026, 11)
        val LUNCH = FinanceTransaction(
            ownerId = MEMBER,
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
            minorCategory = "카페",
            description = "스타벅스",
            amount = -5_000,
            paymentMethod = "체크카드",
        )
        val DINNER = LUNCH.copy(
            occurredAt = LocalDateTime.of(2026, 11, 3, 19, 5, 0),
            description = "동네 치킨",
            amount = -21_000,
        )
    }
}
