package com.homeassistant.feature.finance.usecase

import com.homeassistant.application.port.inbound.finance.FindMonthlySpending
import com.homeassistant.application.port.inbound.finance.MonthlySpending
import com.homeassistant.application.port.inbound.finance.SpendingGroup
import com.homeassistant.application.port.inbound.finance.SpendingTransaction
import com.homeassistant.domain.finance.FinanceTransaction
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
import java.time.YearMonth

/**
 * Groups the expenses of a month of a user, or the shared expenses of the members of their household in [household],
 * as [transactions] totals them, lists each group's expenses under it, and names payment methods by their owners'
 * aliases in [paymentMethodSettings].
 */
class FindMonthlySpendingService(
    private val transactions: FinanceTransactionRepository,
    private val household: HouseholdMembers,
    private val paymentMethodSettings: PaymentMethodSettingRepository,
) : FindMonthlySpending {

    override fun find(
        userId: UserId,
        month: YearMonth,
        grouping: SpendingGrouping,
        scope: SpendingScope,
    ): MonthlySpending {
        val members = membersShown(userId, scope)
        val owners = owners(userId, members)
        val expenses = expensesIn(owners, month)
        val totals = transactions.findTotalsBy(grouping, expenses)
        val listed = withPaymentMethodNames(transactions.findMatching(expenses), owners)
        return MonthlySpending(month, grouping, scope, members, groups(totals, listed, grouping))
    }

    private fun membersShown(userId: UserId, scope: SpendingScope): List<RegisteredUser> = when (scope) {
        SpendingScope.MINE -> emptyList()
        SpendingScope.HOUSEHOLD -> household.findMembersOf(userId)
    }

    /** The shared expenses of the household members, or every expense of [userId] when no member is shown. */
    private fun owners(userId: UserId, members: List<RegisteredUser>): TransactionOwners =
        if (members.isEmpty()) {
            TransactionOwners.All(setOf(userId))
        } else {
            TransactionOwners.SharedBy(members.mapTo(linkedSetOf(), RegisteredUser::userId))
        }

    private fun expensesIn(owners: TransactionOwners, month: YearMonth) = FinanceTransactionCriteria(
        owners = owners,
        from = month.atDay(1).atStartOfDay(),
        to = month.plusMonths(1).atDay(1).atStartOfDay(),
        type = FinanceTransaction.EXPENSE_TYPE,
    )

    /** Pairs each expense with its owner's alias for its payment method, or with the payment method itself. */
    private fun withPaymentMethodNames(
        expenses: List<FinanceTransaction>,
        owners: TransactionOwners,
    ): List<SpendingTransaction> {
        val names = paymentMethodSettings.findSettingsOf(owners.userIds)
            .associate { (it.ownerId to it.paymentMethod) to it.displayName }
        return expenses.map {
            SpendingTransaction(it, names[it.ownerId to it.paymentMethod] ?: it.paymentMethod)
        }
    }

    /** One group per total, in the totals' order, holding the expenses that share its value and currency. */
    private fun groups(
        totals: List<SpendingTotal>,
        expenses: List<SpendingTransaction>,
        grouping: SpendingGrouping,
    ): List<SpendingGroup> {
        val expensesByGroup = expenses.groupBy { grouping.valueOf(it) to it.transaction.currency }
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

    private fun SpendingGrouping.valueOf(expense: SpendingTransaction): String = when (this) {
        SpendingGrouping.CATEGORY -> expense.transaction.majorCategory
        SpendingGrouping.PAYMENT_METHOD -> expense.paymentMethodName
    }
}
