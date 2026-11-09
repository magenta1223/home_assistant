package com.homeassistant.application.usecase.finance

import com.homeassistant.application.port.outbound.finance.FinanceTransactionRepository
import com.homeassistant.domain.finance.FinanceTransaction
import com.homeassistant.domain.identity.UserId
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportFinanceTransactionsServiceTest {
    @Test
    fun `imported transactions are found in their owner's ledger`() {
        val ledger = InMemoryLedger()

        ImportFinanceTransactionsService(ledger).import(listOf(LUNCH))

        assertEquals(listOf(LUNCH), ledger.findByOwnerOccurredBetween(OWNER, NOVEMBER_START, DECEMBER_START))
    }

    @Test
    fun `an empty import writes nothing`() {
        val ledger = InMemoryLedger()

        ImportFinanceTransactionsService(ledger).import(emptyList())

        assertEquals(0, ledger.saveCount)
    }

    private class InMemoryLedger : FinanceTransactionRepository {
        private val stored = linkedMapOf<Pair<UserId, String>, FinanceTransaction>()
        var saveCount = 0
            private set

        override fun save(transactions: List<FinanceTransaction>): List<FinanceTransaction> {
            saveCount++
            val saved = transactions.associateBy { it.ownerId to it.transactionKey }
            stored.putAll(saved)
            return saved.values.toList()
        }

        override fun findByOwnerOccurredBetween(
            ownerId: UserId,
            from: LocalDateTime,
            to: LocalDateTime,
        ): List<FinanceTransaction> =
            stored.values
                .filter { it.ownerId == ownerId && it.occurredAt >= from && it.occurredAt < to }
                .sortedBy { it.occurredAt }
    }

    private companion object {
        val OWNER = UserId("member-1")
        val NOVEMBER_START: LocalDateTime = LocalDateTime.of(2026, 11, 1, 0, 0)
        val DECEMBER_START: LocalDateTime = LocalDateTime.of(2026, 12, 1, 0, 0)
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
    }
}
