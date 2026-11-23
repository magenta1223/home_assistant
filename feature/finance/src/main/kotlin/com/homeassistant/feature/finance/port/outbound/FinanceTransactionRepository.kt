package com.homeassistant.feature.finance.port.outbound

import com.homeassistant.domain.finance.FinanceTransaction
import com.homeassistant.domain.finance.SpendingGrouping
import com.homeassistant.domain.identity.UserId
import java.time.LocalDateTime

/** Stores and retrieves finance transactions while hiding the persistence mechanism. */
interface FinanceTransactionRepository {

    /**
     * Stores [transactions] by their owner and [FinanceTransaction.transactionKey]. A new key is added; a stored key
     * keeps its other values and takes the submitted type, categories and memo. Stored transactions missing from
     * [transactions] stay stored. Returns the saved transactions, one per key; when [transactions] repeats a key, the
     * last one is saved.
     */
    fun save(transactions: List<FinanceTransaction>): List<FinanceTransaction>

    /** The transactions matching [criteria], oldest first. */
    fun findMatching(criteria: FinanceTransactionCriteria): List<FinanceTransaction>

    /**
     * The totals of the transactions matching [criteria], one per [grouping] value and currency, the smallest sum (the
     * largest spending) first. A payment method is grouped under its owner's alias when it has one.
     */
    fun findTotalsBy(grouping: SpendingGrouping, criteria: FinanceTransactionCriteria): List<SpendingTotal>
}

/**
 * Which transactions to find: those of [owners] that occurred at or after [from] and before [to], only those whose
 * major or minor category is [category] when it is given, only those whose description contains
 * [descriptionContains] when it is given, and only those of [type] when it is given.
 */
data class FinanceTransactionCriteria(
    val owners: TransactionOwners,
    val from: LocalDateTime,
    val to: LocalDateTime,
    val category: String? = null,
    val descriptionContains: String? = null,
    val type: String? = null,
)

/** Whose transactions to find. */
sealed interface TransactionOwners {
    /** The owners whose transactions are found. */
    val userIds: Set<UserId>

    /** Every transaction of [userIds]. */
    data class All(override val userIds: Set<UserId>) : TransactionOwners

    /** The transactions of [userIds] paid with a payment method its owner shares with their household. */
    data class SharedBy(override val userIds: Set<UserId>) : TransactionOwners
}

/** The [count] transactions in [currency] whose grouped value is [name], and the signed sum of their amounts. */
data class SpendingTotal(
    val name: String,
    val currency: String,
    val amount: Long,
    val count: Int,
)
