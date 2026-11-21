package com.homeassistant.application.port.inbound.finance

import com.homeassistant.domain.finance.FinanceTransaction
import com.homeassistant.domain.finance.SpendingGrouping
import com.homeassistant.domain.identity.UserId
import java.time.YearMonth

/** Shows a user what they spent in a month. */
fun interface FindMonthlySpending {
    /** The expenses of [userId] in [month], in Korea Standard Time, grouped by [grouping] and currency. */
    fun find(userId: UserId, month: YearMonth, grouping: SpendingGrouping): MonthlySpending
}

/** The expenses of [month] grouped by [grouping], the group with the largest spending first. */
data class MonthlySpending(
    val month: YearMonth,
    val grouping: SpendingGrouping,
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
