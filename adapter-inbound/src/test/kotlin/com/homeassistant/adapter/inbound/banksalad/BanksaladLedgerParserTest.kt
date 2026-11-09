package com.homeassistant.adapter.inbound.banksalad

import com.homeassistant.adapter.inbound.banksalad.BanksaladExportFixture.LedgerRow
import com.homeassistant.domain.finance.FinanceTransaction
import com.homeassistant.domain.identity.UserId
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BanksaladLedgerParserTest {
    @Test
    fun `ledger rows become the owner's transactions with date and time joined`() {
        val export = BanksaladExportFixture.export(listOf(LUNCH_ROW))

        assertEquals(listOf(LUNCH), BanksaladLedgerParser.parse(OWNER, export))
    }

    @Test
    fun `columns are found by header name in any order`() {
        val export = BanksaladExportFixture.export(
            listOf(LUNCH_ROW),
            header = BanksaladExportFixture.LEDGER_HEADER.reversed(),
        )

        assertEquals(listOf(LUNCH), BanksaladLedgerParser.parse(OWNER, export))
    }

    @Test
    fun `a cancellation keeps its positive amount and a memo is kept`() {
        val export = BanksaladExportFixture.export(
            listOf(LUNCH_ROW.copy(time = LocalTime.of(13, 0, 0), amount = 8_000, memo = "주문 취소")),
        )

        val cancellation = BanksaladLedgerParser.parse(OWNER, export).single()

        assertEquals(8_000, cancellation.amount)
        assertEquals("주문 취소", cancellation.memo)
    }

    @Test
    fun `a workbook without the ledger sheet is rejected`() {
        val export = BanksaladExportFixture.export(listOf(LUNCH_ROW), ledgerSheet = "다른 시트")

        val error = assertFailsWith<IllegalArgumentException> { BanksaladLedgerParser.parse(OWNER, export) }

        assertTrue(error.message!!.contains("가계부 내역"))
    }

    @Test
    fun `a ledger without a required column is rejected`() {
        val export = BanksaladExportFixture.export(
            listOf(LUNCH_ROW),
            header = BanksaladExportFixture.LEDGER_HEADER - "결제수단",
        )

        val error = assertFailsWith<IllegalArgumentException> { BanksaladLedgerParser.parse(OWNER, export) }

        assertTrue(error.message!!.contains("결제수단"))
    }

    @Test
    fun `bytes that are not an xlsx workbook are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            BanksaladLedgerParser.parse(OWNER, "not a workbook".toByteArray())
        }
    }

    private companion object {
        val OWNER = UserId("member-1")
        val LUNCH_ROW = LedgerRow(
            date = LocalDate.of(2026, 11, 3),
            time = LocalTime.of(12, 30, 15),
            type = "지출",
            majorCategory = "식비",
            minorCategory = "한식",
            description = "테스트 식당",
            amount = -8_000,
        )
        val LUNCH = FinanceTransaction(
            ownerId = OWNER,
            occurredAt = LocalDateTime.of(2026, 11, 3, 12, 30, 15),
            type = "지출",
            majorCategory = "식비",
            minorCategory = "한식",
            description = "테스트 식당",
            amount = -8_000,
            currency = "KRW",
            paymentMethod = "테스트카드",
            memo = null,
        )
    }
}
