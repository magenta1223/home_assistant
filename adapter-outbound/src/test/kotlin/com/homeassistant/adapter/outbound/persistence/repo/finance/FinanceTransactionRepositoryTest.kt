package com.homeassistant.adapter.outbound.persistence.repo.finance

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.application.port.outbound.finance.FinanceTransactionRepository
import com.homeassistant.domain.finance.FinanceTransaction
import com.homeassistant.domain.identity.UserId
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class FinanceTransactionRepositoryTest {
    @Test
    fun `saved transactions are found in time order`() = withRepository { transactions ->
        transactions.save(listOf(DINNER, LUNCH))

        assertEquals(listOf(LUNCH, DINNER), transactions.find(OWNER, NOVEMBER_START, DECEMBER_START))
    }

    @Test
    fun `saving a stored transaction again updates its type, categories and memo without adding one`() =
        withRepository { transactions ->
            transactions.save(listOf(LUNCH, DINNER))
            val recategorized = LUNCH.copy(type = "이체", majorCategory = "생활", minorCategory = "기타", memo = "회식")

            transactions.save(listOf(recategorized))

            assertEquals(listOf(recategorized, DINNER), transactions.find(OWNER, NOVEMBER_START, DECEMBER_START))
        }

    @Test
    fun `transactions missing from a later save stay stored`() = withRepository { transactions ->
        transactions.save(listOf(LUNCH, DINNER))

        transactions.save(listOf(DINNER))

        assertEquals(listOf(LUNCH, DINNER), transactions.find(OWNER, NOVEMBER_START, DECEMBER_START))
    }

    @Test
    fun `another user's transactions are not found`() = withRepository { transactions ->
        val othersLunch = LUNCH.copy(ownerId = UserId("member-2"))
        transactions.save(listOf(LUNCH, othersLunch))

        assertEquals(listOf(LUNCH), transactions.find(OWNER, NOVEMBER_START, DECEMBER_START))
        assertEquals(listOf(othersLunch), transactions.find(UserId("member-2"), NOVEMBER_START, DECEMBER_START))
    }

    @Test
    fun `only transactions from the start up to before the end are found`() = withRepository { transactions ->
        val atStart = LUNCH.copy(occurredAt = NOVEMBER_START, description = "새벽 편의점")
        val atEnd = LUNCH.copy(occurredAt = DECEMBER_START, description = "12월 편의점")
        val before = LUNCH.copy(occurredAt = NOVEMBER_START.minusSeconds(1), description = "10월 편의점")
        transactions.save(listOf(before, atStart, LUNCH, atEnd))

        assertEquals(listOf(atStart, LUNCH), transactions.find(OWNER, NOVEMBER_START, DECEMBER_START))
    }

    private fun withRepository(test: (FinanceTransactionRepository) -> Unit) {
        val path = Files.createTempFile("finance-transactions", ".db")
        try {
            test(RepositoryFactory.create(path.toString()).financeTransactions)
        } finally {
            Files.deleteIfExists(path)
        }
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
        val DINNER = LUNCH.copy(
            occurredAt = LocalDateTime.of(2026, 11, 3, 19, 5, 0),
            description = "동네 치킨",
            amount = -21_000,
            memo = "가족 저녁",
        )
    }
}
