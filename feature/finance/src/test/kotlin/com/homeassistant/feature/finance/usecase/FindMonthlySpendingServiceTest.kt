package com.homeassistant.feature.finance.usecase

import com.homeassistant.application.port.inbound.finance.MonthlySpending
import com.homeassistant.application.port.inbound.finance.SpendingGroup
import com.homeassistant.domain.finance.FinanceTransaction
import com.homeassistant.domain.finance.SpendingGrouping
import com.homeassistant.domain.identity.UserId
import com.homeassistant.feature.finance.port.outbound.FinanceTransactionCriteria
import com.homeassistant.feature.finance.port.outbound.FinanceTransactionRepository
import com.homeassistant.feature.finance.port.outbound.SpendingTotal
import java.time.LocalDateTime
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals

class FindMonthlySpendingServiceTest {
    @Test
    fun `the month's expenses of the user are asked for`() {
        val ledger = StoredLedger(totals = emptyList(), transactions = emptyList())

        FindMonthlySpendingService(ledger).find(MEMBER, NOVEMBER, SpendingGrouping.CATEGORY)

        val expensesInNovember = FinanceTransactionCriteria(
            ownerId = MEMBER,
            from = LocalDateTime.of(2026, 11, 1, 0, 0),
            to = LocalDateTime.of(2026, 12, 1, 0, 0),
            type = FinanceTransaction.EXPENSE_TYPE,
        )
        assertEquals(listOf(expensesInNovember, expensesInNovember), ledger.criteria)
    }

    @Test
    fun `each total becomes a group in its order, holding the expenses of its value and currency`() {
        val ledger = StoredLedger(
            totals = listOf(SpendingTotal("신용카드", "KRW", -29_000, 2), SpendingTotal("체크카드", "KRW", -5_000, 1)),
            transactions = listOf(LUNCH, COFFEE, DINNER),
        )

        val spending = FindMonthlySpendingService(ledger).find(MEMBER, NOVEMBER, SpendingGrouping.PAYMENT_METHOD)

        assertEquals(
            MonthlySpending(
                month = NOVEMBER,
                grouping = SpendingGrouping.PAYMENT_METHOD,
                groups = listOf(
                    SpendingGroup("신용카드", "KRW", -29_000, 2, listOf(LUNCH, DINNER)),
                    SpendingGroup("체크카드", "KRW", -5_000, 1, listOf(COFFEE)),
                ),
            ),
            spending,
        )
        assertEquals(listOf(SpendingGrouping.PAYMENT_METHOD), ledger.groupings)
    }

    @Test
    fun `a value spent in two currencies forms a group per currency`() {
        val dollarLunch = LUNCH.copy(amount = -12, currency = "USD")
        val ledger = StoredLedger(
            totals = listOf(SpendingTotal("식비", "KRW", -8_000, 1), SpendingTotal("식비", "USD", -12, 1)),
            transactions = listOf(LUNCH, dollarLunch),
        )

        val spending = FindMonthlySpendingService(ledger).find(MEMBER, NOVEMBER, SpendingGrouping.CATEGORY)

        assertEquals(
            listOf(
                SpendingGroup("식비", "KRW", -8_000, 1, listOf(LUNCH)),
                SpendingGroup("식비", "USD", -12, 1, listOf(dollarLunch)),
            ),
            spending.groups,
        )
    }

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

    private companion object {
        val MEMBER = UserId("member-1")
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
