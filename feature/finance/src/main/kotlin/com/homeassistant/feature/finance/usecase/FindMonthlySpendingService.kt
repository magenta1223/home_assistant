package com.homeassistant.feature.finance.usecase

import com.homeassistant.application.port.inbound.finance.FindMonthlySpending
import com.homeassistant.application.port.inbound.finance.MonthlySpending
import com.homeassistant.application.port.inbound.finance.SpendingGroup
import com.homeassistant.domain.finance.FinanceTransaction
import com.homeassistant.domain.finance.SpendingGrouping
import com.homeassistant.domain.finance.SpendingScope
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import com.homeassistant.feature.finance.port.outbound.FinanceTransactionCriteria
import com.homeassistant.feature.finance.port.outbound.FinanceTransactionRepository
import com.homeassistant.feature.finance.port.outbound.HouseholdMembers
import com.homeassistant.feature.finance.port.outbound.SpendingTotal
import java.time.YearMonth

/**
 * Groups the expenses of a month of a user, or of the members of their household in [household], as [transactions]
 * totals them, and lists each group's expenses under it.
 */
class FindMonthlySpendingService(
    private val transactions: FinanceTransactionRepository,
    private val household: HouseholdMembers,
) : FindMonthlySpending {

    override fun find(
        userId: UserId,
        month: YearMonth,
        grouping: SpendingGrouping,
        scope: SpendingScope,
    ): MonthlySpending {
        val members = membersShown(userId, scope)
        val expenses = expensesIn(owners(userId, members), month)
        val totals = transactions.findTotalsBy(grouping, expenses)
        val listed = transactions.findMatching(expenses)
        return MonthlySpending(month, grouping, scope, members, groups(totals, listed, grouping))
    }

    private fun membersShown(userId: UserId, scope: SpendingScope): List<RegisteredUser> = when (scope) {
        SpendingScope.MINE -> emptyList()
        SpendingScope.HOUSEHOLD -> household.findMembersOf(userId)
    }

    /** The household members, or only [userId] when no household member is shown. */
    private fun owners(userId: UserId, members: List<RegisteredUser>): Set<UserId> =
        members.mapTo(linkedSetOf(), RegisteredUser::userId).ifEmpty { setOf(userId) }

    private fun expensesIn(owners: Set<UserId>, month: YearMonth) = FinanceTransactionCriteria(
        ownerIds = owners,
        from = month.atDay(1).atStartOfDay(),
        to = month.plusMonths(1).atDay(1).atStartOfDay(),
        type = FinanceTransaction.EXPENSE_TYPE,
    )

    /** One group per total, in the totals' order, holding the expenses that share its value and currency. */
    private fun groups(
        totals: List<SpendingTotal>,
        expenses: List<FinanceTransaction>,
        grouping: SpendingGrouping,
    ): List<SpendingGroup> {
        val expensesByGroup = expenses.groupBy { grouping.valueOf(it) to it.currency }
        return totals.map { total ->
            SpendingGroup(
                name = total.name,
                currency = total.currency,
                total = total.amount,
                count = total.count,
                transactions = expensesByGroup[total.name to total.currency].orEmpty(),
            )
        }
    }

    private fun SpendingGrouping.valueOf(transaction: FinanceTransaction): String = when (this) {
        SpendingGrouping.CATEGORY -> transaction.majorCategory
        SpendingGrouping.PAYMENT_METHOD -> transaction.paymentMethod
    }
}
