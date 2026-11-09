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

    /** The transactions of [ownerId] that occurred at or after [from] and before [to], oldest first. */
    fun findByOwnerOccurredBetween(ownerId: UserId, from: LocalDateTime, to: LocalDateTime): List<FinanceTransaction>
}
