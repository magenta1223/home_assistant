package com.homeassistant.application.port.outbound.finance

import com.homeassistant.domain.finance.FinanceTransaction
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
}

/**
 * Which transactions of [ownerId] to find: those that occurred at or after [from] and before [to], only those whose
 * major or minor category is [category] when it is given, and only those whose description contains
 * [descriptionContains] when it is given.
 */
data class FinanceTransactionCriteria(
    val ownerId: UserId,
    val from: LocalDateTime,
    val to: LocalDateTime,
    val category: String? = null,
    val descriptionContains: String? = null,
)
