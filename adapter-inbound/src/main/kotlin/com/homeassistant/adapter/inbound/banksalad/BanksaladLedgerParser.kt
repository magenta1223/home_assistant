package com.homeassistant.adapter.inbound.banksalad

import com.homeassistant.domain.finance.FinanceTransaction
import com.homeassistant.domain.identity.UserId
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.LocalDateTime

/**
 * Reads the ledger sheet of a Banksalad xlsx export. The sheet and column names of the export live only here; the
 * columns are found by their header names in the first row, so their order does not matter.
 */
internal object BanksaladLedgerParser {
    private const val LEDGER_SHEET = "가계부 내역"
    private const val DATE = "날짜"
    private const val TIME = "시간"
    private const val TYPE = "타입"
    private const val MAJOR_CATEGORY = "대분류"
    private const val MINOR_CATEGORY = "소분류"
    private const val DESCRIPTION = "내용"
    private const val AMOUNT = "금액"
    private const val CURRENCY = "화폐"
    private const val PAYMENT_METHOD = "결제수단"
    private const val MEMO = "메모"
    private val COLUMNS =
        listOf(DATE, TIME, TYPE, MAJOR_CATEGORY, MINOR_CATEGORY, DESCRIPTION, AMOUNT, CURRENCY, PAYMENT_METHOD, MEMO)

    /**
     * The transactions of the ledger sheet in [workbook], owned by [ownerId], in sheet order.
     *
     * @throws IllegalArgumentException when [workbook] is not an xlsx file, has no ledger sheet or column, or a row
     * holds a value of the wrong kind; the message names the sheet, column or row.
     */
    fun parse(ownerId: UserId, workbook: ByteArray): List<FinanceTransaction> {
        val opened = try {
            WorkbookFactory.create(ByteArrayInputStream(workbook))
        } catch (error: IOException) {
            throw IllegalArgumentException("the file is not a readable xlsx workbook", error)
        }
        return opened.use {
            val sheet = requireNotNull(it.getSheet(LEDGER_SHEET)) { "sheet '$LEDGER_SHEET' is missing" }
            val columns = columnIndexes(sheet)
            sheet.drop(1).map { row -> row.toTransaction(ownerId, columns) }
        }
    }

    /** The index of every ledger column, by header name. */
    private fun columnIndexes(sheet: Sheet): Map<String, Int> {
        val header = requireNotNull(sheet.getRow(0)) { "sheet '$LEDGER_SHEET' has no header row" }
        val indexes = header.associate { cell -> cell.text() to cell.columnIndex }
        val missing = COLUMNS.filterNot(indexes::containsKey)
        require(missing.isEmpty()) { "sheet '$LEDGER_SHEET' is missing columns: ${missing.joinToString()}" }
        return indexes
    }

    private fun Row.toTransaction(ownerId: UserId, columns: Map<String, Int>): FinanceTransaction {
        fun cell(column: String): Cell? = getCell(columns.getValue(column))
        fun text(column: String): String = cell(column)?.text().orEmpty()
        val rowNumber = rowNum + 1
        return FinanceTransaction(
            ownerId = ownerId,
            occurredAt = LocalDateTime.of(
                dateTime(cell(DATE), rowNumber, DATE).toLocalDate(),
                dateTime(cell(TIME), rowNumber, TIME).toLocalTime(),
            ),
            type = text(TYPE),
            majorCategory = text(MAJOR_CATEGORY),
            minorCategory = text(MINOR_CATEGORY),
            description = text(DESCRIPTION),
            amount = wholeNumber(cell(AMOUNT), rowNumber),
            currency = text(CURRENCY),
            paymentMethod = text(PAYMENT_METHOD),
            memo = text(MEMO).ifBlank { null },
        )
    }

    /** The date or time of a date-formatted numeric cell, rounded to the second. */
    private fun dateTime(cell: Cell?, rowNumber: Int, column: String): LocalDateTime {
        require(cell != null && cell.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            "row $rowNumber: $column must be a date or time cell"
        }
        return DateUtil.getLocalDateTime(cell.numericCellValue, false, true)
    }

    private fun wholeNumber(cell: Cell?, rowNumber: Int): Long {
        val value = cell?.takeIf { it.cellType == CellType.NUMERIC }?.numericCellValue
        require(value != null && value % 1.0 == 0.0) { "row $rowNumber: $AMOUNT must be a whole number" }
        return value.toLong()
    }

    private fun Cell.text(): String = FORMATTER.formatCellValue(this).trim()

    private val FORMATTER = DataFormatter()
}
