package com.homeassistant.adapter.inbound.banksalad

import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime

/** Builds small anonymized xlsx files shaped like a Banksalad export. */
internal object BanksaladExportFixture {
    private const val SECONDS_PER_DAY = 86_400.0

    val LEDGER_HEADER = listOf("날짜", "시간", "타입", "대분류", "소분류", "내용", "금액", "화폐", "결제수단", "메모")

    /** One ledger row; [memo] null leaves the memo cell empty. */
    data class LedgerRow(
        val date: LocalDate,
        val time: LocalTime,
        val type: String,
        val majorCategory: String,
        val minorCategory: String,
        val description: String,
        val amount: Long,
        val currency: String = "KRW",
        val paymentMethod: String = "테스트카드",
        val memo: String? = null,
    )

    /** An export whose ledger sheet holds [rows] under [header], next to an overview sheet the parser ignores. */
    fun export(
        rows: List<LedgerRow>,
        header: List<String> = LEDGER_HEADER,
        ledgerSheet: String = "가계부 내역",
    ): ByteArray =
        XSSFWorkbook().use { workbook ->
            workbook.createSheet("뱅샐현황").createRow(0).createCell(0).setCellValue("요약")
            val dateStyle = workbook.createCellStyle().apply {
                dataFormat = workbook.creationHelper.createDataFormat().getFormat("yyyy-mm-dd")
            }
            val timeStyle = workbook.createCellStyle().apply {
                dataFormat = workbook.creationHelper.createDataFormat().getFormat("hh:mm:ss")
            }
            val sheet = workbook.createSheet(ledgerSheet)
            val headerRow = sheet.createRow(0)
            header.forEachIndexed { index, name -> headerRow.createCell(index).setCellValue(name) }
            rows.forEachIndexed { index, row ->
                sheet.createRow(index + 1).fill(header, row, dateStyle, timeStyle)
            }
            ByteArrayOutputStream().also(workbook::write).toByteArray()
        }

    private fun Row.fill(
        header: List<String>,
        row: LedgerRow,
        dateStyle: CellStyle,
        timeStyle: CellStyle,
    ) {
        header.forEachIndexed { index, name ->
            when (name) {
                "날짜" -> createCell(index).apply {
                    setCellValue(row.date.atStartOfDay())
                    cellStyle = dateStyle
                }
                "시간" -> createCell(index).apply {
                    setCellValue(row.time.toSecondOfDay() / SECONDS_PER_DAY)
                    cellStyle = timeStyle
                }
                "타입" -> createCell(index).setCellValue(row.type)
                "대분류" -> createCell(index).setCellValue(row.majorCategory)
                "소분류" -> createCell(index).setCellValue(row.minorCategory)
                "내용" -> createCell(index).setCellValue(row.description)
                "금액" -> createCell(index).setCellValue(row.amount.toDouble())
                "화폐" -> createCell(index).setCellValue(row.currency)
                "결제수단" -> createCell(index).setCellValue(row.paymentMethod)
                "메모" -> row.memo?.let { createCell(index).setCellValue(it) }
            }
        }
    }
}
