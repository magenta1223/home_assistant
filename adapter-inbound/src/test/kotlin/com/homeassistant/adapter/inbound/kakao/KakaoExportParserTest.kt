package com.homeassistant.adapter.inbound.kakao

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KakaoExportParserTest {
    @Test
    fun `supported export fixtures produce the same canonical records`() {
        val bracket = parseFixture("bracket-export.txt")
        val korean = parseFixture("korean-timestamp-export.txt")
        val dotted = parseFixture("dotted-timestamp-export.txt")

        assertEquals(korean.records.map { it.canonicalValues() }, dotted.records.map { it.canonicalValues() })
        assertEquals(korean.records.map { it.canonicalValues() }, bracket.records.take(2).map { it.canonicalValues() })
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
        assertNotEquals(
            first.records.map { it.deduplicationAliases },
            renamed.records.map { it.deduplicationAliases },
        )
    }

    @Test
    fun `bracket date separator is preserved and distinguishes equal messages on different dates`() {
        val records = parseFixture("bracket-export.txt").records

        assertTrue(records[0].content.contains("2025년 1월 2일 오전 9:03"))
        assertTrue(records[2].content.contains("2025년 1월 3일 오전 9:03"))
        assertNotEquals(records[0].deduplicationKey, records[2].deduplicationKey)
    }

    @Test
    fun `same-name historical fingerprint is exposed as a migration alias`() {
        val sourceName = "kakao-export.txt"
        val record = KakaoExportParser.parse(
            sourceName,
            "2025년 1월 2일 오전 9:03, 민수 : 아침 약은 먹었어?",
        ).records.single()

        assertEquals(
            setOf(sha256("$sourceName\u001F민수\u001F2025년 1월 2일 오전 9:03\u001F아침 약은 먹었어?")),
            record.deduplicationAliases,
        )
    }

    @Test
    fun `historical bracket fingerprint retains the following date separator`() {
        val sourceName = "bracket-export.txt"
        val record = KakaoExportParser.parse(sourceName, fixture(sourceName)).records[1]
        val legacyContent = "응\n두 알 먹었어\n--------------- 2025년 1월 3일 금요일 ---------------"

        assertEquals(
            setOf(sha256("$sourceName\u001F영희\u001F오전 9:04\u001F$legacyContent")),
            record.deduplicationAliases,
        )
    }

    @Test
    fun `twelve oclock is normalized for both periods`() {
        val records = KakaoExportParser.parse(
            "twelve.txt",
            """
            2025년 1월 2일 오전 12:05, 민수 : 자정 직후
            2025년 1월 2일 오후 12:05, 민수 : 정오 직후
            """.trimIndent(),
        ).records

        assertTrue(records[0].content.contains("오전 12:05"))
        assertTrue(records[1].content.contains("오후 12:05"))
        assertEquals(sha256("민수\u001F2025-01-02T00:05\u001F자정 직후"), records[0].deduplicationKey)
        assertEquals(sha256("민수\u001F2025-01-02T12:05\u001F정오 직후"), records[1].deduplicationKey)
    }

    @Test
    fun `thirteen oclock is rejected for both periods`() {
        listOf("오전", "오후").forEach { period ->
            assertFailsWith<IllegalArgumentException> {
                KakaoExportParser.parse(
                    "invalid.txt",
                    "2025년 1월 2일 $period 13:05, 민수 : 잘못된 시각",
                )
            }
        }
    }

    private fun parseFixture(name: String) = KakaoExportParser.parse(name, fixture(name))

    private fun com.homeassistant.domain.source.SourceRecordDraft.canonicalValues() = deduplicationKey to content

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/kakao/$name"))
            .bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
