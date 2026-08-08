package com.homeassistant.adapter.inbound.kakao

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KakaoExportParserTest {
    @Test
    fun `supported export fixtures produce the same canonical records`() {
        val bracket = parseFixture("bracket-export.txt")
        val korean = parseFixture("korean-timestamp-export.txt")
        val dotted = parseFixture("dotted-timestamp-export.txt")

        assertEquals(korean.records, dotted.records)
        assertEquals(korean.records, bracket.records.take(2))
        assertEquals(3, bracket.records.size)
        assertTrue(bracket.records[1].content.endsWith("응\n두 알 먹었어"))
    }

    @Test
    fun `source name does not affect deduplication keys`() {
        val text = fixture("korean-timestamp-export.txt")

        val first = KakaoExportParser.parse("original.txt", text)
        val renamed = KakaoExportParser.parse("renamed.txt", text)

        assertEquals(
            first.records.map { it.deduplicationKey },
            renamed.records.map { it.deduplicationKey },
        )
    }

    @Test
    fun `bracket date separator is preserved and distinguishes equal messages on different dates`() {
        val records = parseFixture("bracket-export.txt").records

        assertTrue(records[0].content.contains("2025년 1월 2일 오전 9:03"))
        assertTrue(records[2].content.contains("2025년 1월 3일 오전 9:03"))
        assertNotEquals(records[0].deduplicationKey, records[2].deduplicationKey)
    }

    private fun parseFixture(name: String) = KakaoExportParser.parse(name, fixture(name))

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/kakao/$name"))
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() }
}
