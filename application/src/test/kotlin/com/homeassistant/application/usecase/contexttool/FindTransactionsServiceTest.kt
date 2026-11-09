package com.homeassistant.application.usecase.contexttool

import com.homeassistant.application.port.inbound.contexttool.ContextAccessExpiredException
import com.homeassistant.application.port.inbound.contexttool.TransactionSearch
import com.homeassistant.application.port.outbound.finance.FinanceTransactionCriteria
import com.homeassistant.application.port.outbound.finance.FinanceTransactionRepository
import com.homeassistant.domain.finance.FinanceTransaction
import com.homeassistant.domain.identity.UserId
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FindTransactionsServiceTest {
    @Test
    fun `the token owner's transactions in the given days are reported with their totals per currency`() {
        val grants = ContextGrants(Duration.ofMinutes(10))
        val ledger = CriteriaRecordingLedger(listOf(LUNCH, DINNER, LUNCH.copy(amount = -12, currency = "USD")))
        val service = FindTransactionsService(grants, ledger)

        val report = service.find(grants.issue(MEMBER), TransactionSearch(NOV_1, NOV_30, category = "식비"))

        assertEquals(
            FinanceTransactionCriteria(MEMBER, NOV_1.atStartOfDay(), NOV_30.plusDays(1).atStartOfDay(), "식비", null),
            ledger.criteria.single(),
        )
        assertEquals(
            """
            Transactions from 2026-11-01 to 2026-11-30, category 식비: 3 found.
            Total: -29,000 KRW, -12 USD
            - 2026-11-03 12:30:15 | 지출 | 식비 > 한식 | 김밥천국 | -8,000 KRW | 신용카드
            - 2026-11-03 19:05:00 | 지출 | 식비 > 한식 | 동네 치킨 | -21,000 KRW | 신용카드 | memo: 가족 저녁
            - 2026-11-03 12:30:15 | 지출 | 식비 > 한식 | 김밥천국 | -12 USD | 신용카드
            """.trimIndent(),
            report,
        )
    }

    @Test
    fun `the total covers every match while the list stops at the limit`() {
        val grants = ContextGrants(Duration.ofMinutes(10))
        val service = FindTransactionsService(grants, CriteriaRecordingLedger(listOf(LUNCH, DINNER)), maxListed = 1)

        val report = service.find(grants.issue(MEMBER), TransactionSearch(NOV_1, NOV_30))

        assertEquals(
            """
            Transactions from 2026-11-01 to 2026-11-30: 2 found.
            Total: -29,000 KRW
            - 2026-11-03 12:30:15 | 지출 | 식비 > 한식 | 김밥천국 | -8,000 KRW | 신용카드
            (1 more not listed)
            """.trimIndent(),
            report,
        )
    }

    @Test
    fun `no match is reported as such`() {
        val grants = ContextGrants(Duration.ofMinutes(10))
        val service = FindTransactionsService(grants, CriteriaRecordingLedger(emptyList()))

        val report = service.find(grants.issue(MEMBER), TransactionSearch(NOV_1, NOV_30, merchant = "스타벅스"))

        assertEquals("Transactions from 2026-11-01 to 2026-11-30, description containing 스타벅스: 0 found.", report)
    }

    @Test
    fun `an unknown token finds nothing`() {
        val ledger = CriteriaRecordingLedger(listOf(LUNCH))
        val service = FindTransactionsService(ContextGrants(Duration.ofMinutes(10)), ledger)

        assertFailsWith<ContextAccessExpiredException> { service.find("unknown", TransactionSearch(NOV_1, NOV_30)) }
        assertEquals(emptyList(), ledger.criteria)
    }

    /** Returns [matches] for any criteria and keeps the criteria it was asked for. */
    private class CriteriaRecordingLedger(private val matches: List<FinanceTransaction>) : FinanceTransactionRepository {
        val criteria = mutableListOf<FinanceTransactionCriteria>()

        override fun save(transactions: List<FinanceTransaction>): List<FinanceTransaction> = error("unused")

        override fun findMatching(criteria: FinanceTransactionCriteria): List<FinanceTransaction> {
            this.criteria += criteria
            return matches
        }
    }

    private companion object {
        val MEMBER = UserId("member-1")
        val NOV_1: LocalDate = LocalDate.of(2026, 11, 1)
        val NOV_30: LocalDate = LocalDate.of(2026, 11, 30)
        val LUNCH = FinanceTransaction(
            ownerId = MEMBER,
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
