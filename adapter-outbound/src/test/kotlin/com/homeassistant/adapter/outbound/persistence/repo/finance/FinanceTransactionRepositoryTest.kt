package com.homeassistant.adapter.outbound.persistence.repo.finance

import com.homeassistant.adapter.outbound.persistence.repo.RepositoryFactory
import com.homeassistant.feature.finance.port.outbound.FinanceTransactionCriteria
import com.homeassistant.feature.finance.port.outbound.FinanceTransactionRepository
import com.homeassistant.feature.finance.port.outbound.SpendingTotal
import com.homeassistant.feature.finance.port.outbound.TransactionOwners
import com.homeassistant.domain.finance.FinanceTransaction
import com.homeassistant.domain.finance.SpendingGrouping
import com.homeassistant.domain.identity.UserId
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class FinanceTransactionRepositoryTest {
    @Test
    fun `saved transactions are returned and found in time order`() = withRepository { transactions ->
        assertEquals(listOf(DINNER, LUNCH), transactions.save(listOf(DINNER, LUNCH)))

        assertEquals(listOf(LUNCH, DINNER), transactions.findInNovember(OWNER))
    }

    @Test
    fun `saving a stored transaction again updates its type, categories and memo without adding one`() =
        withRepository { transactions ->
            transactions.save(listOf(LUNCH, DINNER))
            val recategorized = LUNCH.copy(type = "이체", majorCategory = "생활", minorCategory = "기타", memo = "회식")

            assertEquals(listOf(recategorized), transactions.save(listOf(recategorized)))

            assertEquals(listOf(recategorized, DINNER), transactions.findInNovember(OWNER))
        }

    @Test
    fun `a key repeated in one save is saved once, as its last transaction`() = withRepository { transactions ->
        val recategorized = LUNCH.copy(minorCategory = "분식")

        assertEquals(listOf(recategorized), transactions.save(listOf(LUNCH, recategorized)))
        assertEquals(listOf(recategorized), transactions.findInNovember(OWNER))
    }

    @Test
    fun `transactions missing from a later save stay stored`() = withRepository { transactions ->
        transactions.save(listOf(LUNCH, DINNER))

        transactions.save(listOf(DINNER))

        assertEquals(listOf(LUNCH, DINNER), transactions.findInNovember(OWNER))
    }

    @Test
    fun `another user's transactions are not found`() = withRepository { transactions ->
        val othersLunch = LUNCH.copy(ownerId = UserId("member-2"))
        transactions.save(listOf(LUNCH, othersLunch))

        assertEquals(listOf(LUNCH), transactions.findInNovember(OWNER))
        assertEquals(listOf(othersLunch), transactions.findInNovember(UserId("member-2")))
    }

    @Test
    fun `only transactions from the start up to before the end are found`() = withRepository { transactions ->
        val atStart = LUNCH.copy(occurredAt = NOVEMBER_START, description = "새벽 편의점")
        val atEnd = LUNCH.copy(occurredAt = DECEMBER_START, description = "12월 편의점")
        val before = LUNCH.copy(occurredAt = NOVEMBER_START.minusSeconds(1), description = "10월 편의점")
        transactions.save(listOf(before, atStart, LUNCH, atEnd))

        assertEquals(listOf(atStart, LUNCH), transactions.findInNovember(OWNER))
    }

    @Test
    fun `a category matches either the major or the minor category`() = withRepository { transactions ->
        val chicken = DINNER.copy(minorCategory = "치킨")
        val taxi = LUNCH.copy(occurredAt = LUNCH.occurredAt.plusHours(1), majorCategory = "교통", minorCategory = "택시")
        transactions.save(listOf(LUNCH, chicken, taxi))

        assertEquals(listOf(LUNCH, chicken), transactions.findInNovember(OWNER, category = "식비"))
        assertEquals(listOf(taxi), transactions.findInNovember(OWNER, category = "택시"))
    }

    @Test
    fun `a description filter matches the text anywhere and takes wildcards literally`() =
        withRepository { transactions ->
            val discount = DINNER.copy(occurredAt = DINNER.occurredAt.plusHours(1), description = "치킨 50% 할인")
            transactions.save(listOf(LUNCH, DINNER, discount))

            assertEquals(listOf(DINNER, discount), transactions.findInNovember(OWNER, descriptionContains = "치킨"))
            assertEquals(listOf(discount), transactions.findInNovember(OWNER, descriptionContains = "50%"))
            assertEquals(emptyList(), transactions.findInNovember(OWNER, descriptionContains = "_"))
        }

    @Test
    fun `transactions and totals of several owners are found together`() = withRepository { transactions ->
        val partnersDinner = DINNER.copy(ownerId = UserId("member-2"))
        val strangersLunch = LUNCH.copy(ownerId = UserId("member-3"))
        transactions.save(listOf(LUNCH, partnersDinner, strangersLunch))
        val household = FinanceTransactionCriteria(TransactionOwners.All(setOf(OWNER, UserId("member-2"))), NOVEMBER_START, DECEMBER_START)

        assertEquals(listOf(LUNCH, partnersDinner), transactions.findMatching(household))
        assertEquals(
            listOf(SpendingTotal("식비", "KRW", -29_000, 2)),
            transactions.findTotalsBy(SpendingGrouping.CATEGORY, household),
        )
    }

    @Test
    fun `a type filter finds only transactions of that type`() = withRepository { transactions ->
        val transfer = DINNER.copy(type = "이체")
        transactions.save(listOf(LUNCH, transfer))

        assertEquals(listOf(LUNCH), transactions.findMatching(novemberOf(OWNER, type = "지출")))
    }

    @Test
    fun `totals sum the amounts per category and currency, the largest spending first`() =
        withRepository { transactions ->
            val cancelledDinner = DINNER.copy(occurredAt = DINNER.occurredAt.plusHours(1), amount = 5_000)
            val taxi = LUNCH.copy(
                occurredAt = LUNCH.occurredAt.plusHours(2),
                majorCategory = "교통",
                description = "택시",
                amount = -30_000,
            )
            val dollarLunch = LUNCH.copy(amount = -12, currency = "USD")
            transactions.save(listOf(LUNCH, DINNER, cancelledDinner, taxi, dollarLunch))

            assertEquals(
                listOf(
                    SpendingTotal("교통", "KRW", -30_000, 1),
                    SpendingTotal("식비", "KRW", -24_000, 3),
                    SpendingTotal("식비", "USD", -12, 1),
                ),
                transactions.findTotalsBy(SpendingGrouping.CATEGORY, novemberOf(OWNER)),
            )
        }

    @Test
    fun `totals by payment method sum the amounts per payment method`() = withRepository { transactions ->
        val coffee = LUNCH.copy(occurredAt = LUNCH.occurredAt.plusHours(1), amount = -5_000, paymentMethod = "체크카드")
        transactions.save(listOf(LUNCH, DINNER, coffee))

        assertEquals(
            listOf(SpendingTotal("신용카드", "KRW", -29_000, 2), SpendingTotal("체크카드", "KRW", -5_000, 1)),
            transactions.findTotalsBy(SpendingGrouping.PAYMENT_METHOD, novemberOf(OWNER)),
        )
    }

    @Test
    fun `totals count only the matching transactions`() = withRepository { transactions ->
        val othersLunch = LUNCH.copy(ownerId = UserId("member-2"))
        val octoberLunch = LUNCH.copy(occurredAt = NOVEMBER_START.minusSeconds(1))
        val transfer = DINNER.copy(type = "이체")
        transactions.save(listOf(LUNCH, othersLunch, octoberLunch, transfer))

        assertEquals(
            listOf(SpendingTotal("식비", "KRW", -8_000, 1)),
            transactions.findTotalsBy(SpendingGrouping.CATEGORY, novemberOf(OWNER, type = "지출")),
        )
    }

    private fun novemberOf(ownerId: UserId, type: String? = null) =
        FinanceTransactionCriteria(TransactionOwners.All(setOf(ownerId)), NOVEMBER_START, DECEMBER_START, type = type)

    private fun FinanceTransactionRepository.findInNovember(
        ownerId: UserId,
        category: String? = null,
        descriptionContains: String? = null,
    ): List<FinanceTransaction> =
        findMatching(FinanceTransactionCriteria(TransactionOwners.All(setOf(ownerId)), NOVEMBER_START, DECEMBER_START, category, descriptionContains))

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
