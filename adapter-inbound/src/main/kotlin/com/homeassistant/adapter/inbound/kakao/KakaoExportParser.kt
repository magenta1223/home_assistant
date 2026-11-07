package com.homeassistant.adapter.inbound.kakao

import com.homeassistant.domain.source.SourceDocumentDraft
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceDescriptor

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Parses KakaoTalk text exports into message records while preserving multiline payloads. */
object KakaoExportParser {
    private val bracketHeader = Regex("^\\[(.+)] \\[(오전|오후) (\\d{1,2}:\\d{2})] ?(.*)$")
    private val exportedHeader = Regex("^(\\d{4}년 \\d{1,2}월 \\d{1,2}일 (?:오전|오후) \\d{1,2}:\\d{2}), (.+?) : ?(.*)$")
    private val dottedExportedHeader = Regex("^(\\d{4}\\.\\s*\\d{1,2}\\.\\s*\\d{1,2}\\.\\s*(?:오전|오후)\\s*\\d{1,2}:\\d{2}),\\s*(.+?) : ?(.*)$")
    private val exportedDateSeparator = Regex("^\\d{4}년 \\d{1,2}월 \\d{1,2}일 (?:오전|오후) \\d{1,2}:\\d{2}$")
    private val dottedDateSeparator = Regex("^\\d{4}\\.\\s*\\d{1,2}\\.\\s*\\d{1,2}\\.\\s*(?:오전|오후)\\s*\\d{1,2}:\\d{2}$")
    private val bracketKoreanDateSeparator = Regex(
        "^\\s*(?:-{3,}\\s*)?(\\d{4})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일" +
            "(?:\\s+[월화수목금토일]요일)?(?:\\s*-{3,})?\\s*$",
    )
    private val bracketDottedDateSeparator = Regex(
        "^\\s*(?:-{3,}\\s*)?(\\d{4})\\.\\s*(\\d{1,2})\\.\\s*(\\d{1,2})\\." +
            "(?:\\s+[월화수목금토일]요일)?(?:\\s*-{3,})?\\s*$",
    )
    private val koreanTimestamp = Regex(
        "^(\\d{4})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일\\s*(오전|오후)\\s*(\\d{1,2}):(\\d{2})$",
    )
    private val dottedTimestamp = Regex(
        "^(\\d{4})\\.\\s*(\\d{1,2})\\.\\s*(\\d{1,2})\\.\\s*(오전|오후)\\s*(\\d{1,2}):(\\d{2})$",
    )

    fun parse(sourceName: String, text: String): SourceDocumentDraft = SourceDocumentDraft(
        source = SourceDescriptor(KakaoMessageRecord.SOURCE_TYPE, sourceName),
        records = parseEntries(text).map { it.build() },
    )

    private fun parseEntries(text: String): List<MessageBuilder> {
        val lines = text.lines()
        val messages = mutableListOf<MessageBuilder>()
        var bracketDate: LocalDate? = null
        lines.forEach { rawLine ->
            val line = rawLine.trimStart('﻿')
            parseBracketDate(line)?.let { date ->
                bracketDate = date
                return@forEach
            }
            val bracketMatch = bracketHeader.matchEntire(line)
            val exportedMatch = exportedHeader.matchEntire(line)
            val dottedExportedMatch = dottedExportedHeader.matchEntire(line)
            if (bracketMatch == null && exportedMatch == null && dottedExportedMatch == null) {
                if (exportedDateSeparator.matches(line) || dottedDateSeparator.matches(line)) return@forEach
                if (messages.isNotEmpty()) messages.last().append(rawLine)
                return@forEach
            }

            val sender = (bracketMatch?.groupValues?.get(1)
                ?: exportedMatch?.groupValues?.get(2)
                ?: dottedExportedMatch!!.groupValues[2]).trim()
            val timestamp = bracketMatch
                ?.let { bracketTimestamp(bracketDate, it.groupValues[2], it.groupValues[3]) }
                ?: parseTimestamp(
                    exportedMatch?.groupValues?.get(1)
                        ?: dottedExportedMatch!!.groupValues[1],
                )
            val content = bracketMatch?.groupValues?.get(4)
                ?: exportedMatch?.groupValues?.get(3)
                ?: dottedExportedMatch!!.groupValues[3]
            messages += MessageBuilder(sender, timestamp, content)
        }
        return messages
    }

    private class MessageBuilder(
        private val sender: String,
        private val timestamp: ParsedTimestamp,
        initialContent: String,
    ) {
        private val contentLines = mutableListOf(initialContent)

        fun append(line: String) {
            contentLines += line
        }

        fun build(): SourceRecordDraft = KakaoMessageRecord.record(
            sender = sender,
            canonicalTime = timestamp.canonical,
            text = contentLines.joinToString("\n").trimEnd(),
            recordedAt = timestamp.recordedAt,
        )
    }

    private data class ParsedTimestamp(
        val canonical: String,
        val recordedAt: Long,
    )

    private fun parseBracketDate(line: String): LocalDate? {
        val match = bracketKoreanDateSeparator.matchEntire(line)
            ?: bracketDottedDateSeparator.matchEntire(line)
            ?: return null
        return LocalDate.of(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
        )
    }

    private fun bracketTimestamp(date: LocalDate?, period: String, time: String): ParsedTimestamp {
        if (date == null) {
            return ParsedTimestamp("UNKNOWN_DATE|$period $time", System.currentTimeMillis())
        }
        return timestamp(
            date,
            period,
            time.substringBefore(':').toInt(),
            time.substringAfter(':').toInt(),
        )
    }

    private fun parseTimestamp(value: String): ParsedTimestamp {
        val match = koreanTimestamp.matchEntire(value) ?: dottedTimestamp.matchEntire(value)
            ?: error("Unsupported Kakao timestamp: $value")
        return timestamp(
            date = LocalDate.of(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            ),
            period = match.groupValues[4],
            hour = match.groupValues[5].toInt(),
            minute = match.groupValues[6].toInt(),
        )
    }

    private fun timestamp(
        date: LocalDate,
        period: String,
        hour: Int,
        minute: Int,
    ): ParsedTimestamp {
        require(hour in 1..12) { "Kakao hour must be between 1 and 12: $hour" }
        val hourOfDay = (hour % 12) + if (period == "오후") 12 else 0
        val time = LocalDateTime.of(date, LocalTime.of(hourOfDay, minute))
        return ParsedTimestamp(
            canonical = KakaoMessageRecord.canonicalTime(time),
            recordedAt = KakaoMessageRecord.recordedAt(time),
        )
    }
}
