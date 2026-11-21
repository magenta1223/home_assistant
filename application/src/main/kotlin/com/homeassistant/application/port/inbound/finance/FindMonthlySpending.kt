package com.homeassistant.application.port.inbound.finance

import com.homeassistant.domain.finance.FinanceTransaction
import com.homeassistant.domain.finance.SpendingGrouping
import com.homeassistant.domain.finance.SpendingScope
import com.homeassistant.domain.identity.RegisteredUser
import com.homeassistant.domain.identity.UserId
import java.time.YearMonth

/** Shows a user what they, or their household, spent in a month. */
fun interface FindMonthlySpending {
    /**
     * The expenses in [month], in Korea Standard Time, of [userId] or of [userId]'s household as [scope] says,
     * grouped by [grouping] and currency. A user in no household sees only their own expenses in either scope.
     */
    fun find(userId: UserId, month: YearMonth, grouping: SpendingGrouping, scope: SpendingScope): MonthlySpending
}

/**
 * The expenses of [month] grouped by [grouping], the group with the largest spending first.
 *
 * @property members the household members whose expenses are shown; empty when only the user's own are.
 */
data class MonthlySpending(
    val month: YearMonth,
    val grouping: SpendingGrouping,
    val scope: SpendingScope,
    val members: List<RegisteredUser>,
    val groups: List<SpendingGroup>,
)

/**
 * The expenses in [currency] whose [grouping][MonthlySpending.grouping] value is [name].
 *
 * @property total the signed sum of their amounts; spending is negative and a cancellation offsets it.
 * @property count how many expenses there are.
 * @property transactions the expenses, oldest first.
 */
data class SpendingGroup(
    val name: String,
    val currency: String,
    val total: Long,
    val count: Int,
    val transactions: List<FinanceTransaction>,
)
