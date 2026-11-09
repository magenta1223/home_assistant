package com.homeassistant.domain.finance

import com.homeassistant.domain.identity.UserId
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FinanceTransactionTest {
    @Test
    fun `editing the type, categories or memo keeps the transaction key`() {
        val edited = LUNCH.copy(type = "이체", majorCategory = "생활", minorCategory = "기타", memo = "회식")

        assertEquals(LUNCH.transactionKey, edited.transactionKey)
    }

    @Test
    fun `a different time, description, amount, currency or payment method gives a different key`() {
        listOf(
            LUNCH.copy(occurredAt = LUNCH.occurredAt.plusSeconds(1)),
            LUNCH.copy(description = "다른 식당"),
            LUNCH.copy(amount = 12_000),
            LUNCH.copy(currency = "USD"),
            LUNCH.copy(paymentMethod = "체크카드"),
        ).forEach { changed ->
            assertNotEquals(LUNCH.transactionKey, changed.transactionKey, "$changed")
        }
    }

    private companion object {
        val LUNCH = FinanceTransaction(
            ownerId = UserId("member-1"),
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
