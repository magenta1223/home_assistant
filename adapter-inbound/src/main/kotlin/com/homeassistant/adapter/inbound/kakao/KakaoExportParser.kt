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
        records = parseEntries(text).map { it.build(sourceName) },
    )

    /** The messages of [text] in file order; [KakaoExportMessage.sentAt] is null when the export gives no date. */
    internal fun messages(text: String): List<KakaoExportMessage> = parseEntries(text).map { it.message() }

    private fun parseEntries(text: String): List<MessageBuilder> {
        val lines = text.lines()
        val messages = mutableListOf<MessageBuilder>()
        var bracketDate: LocalDate? = null
        lines.forEach { rawLine ->
            val line = rawLine.trimStart('﻿')
            parseBracketDate(line)?.let { date ->
                if (messages.isNotEmpty()) messages.last().appendLegacy(rawLine)
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

            val legacySender = bracketMatch?.groupValues?.get(1)
                ?: exportedMatch?.groupValues?.get(2)
                ?: dottedExportedMatch!!.groupValues[2]
            val sender = legacySender.trim()
            val timestamp = bracketMatch
                ?.let { bracketTimestamp(bracketDate, it.groupValues[2], it.groupValues[3]) }
                ?: parseTimestamp(
                    exportedMatch?.groupValues?.get(1)
                        ?: dottedExportedMatch!!.groupValues[1],
                )
            val content = bracketMatch?.groupValues?.get(4)
                ?: exportedMatch?.groupValues?.get(3)
                ?: dottedExportedMatch!!.groupValues[3]
            messages += MessageBuilder(sender, legacySender, timestamp, content)
        }
        return messages
    }

    private class MessageBuilder(
        private val sender: String,
        private val legacySender: String,
        private val timestamp: ParsedTimestamp,
        initialContent: String,
    ) {
        private val contentLines = mutableListOf(initialContent)
        private val legacyContentLines = mutableListOf(initialContent)

        fun append(line: String) {
            contentLines += line
            legacyContentLines += line
        }

        fun appendLegacy(line: String) {
            legacyContentLines += line
        }

        fun message(): KakaoExportMessage = KakaoExportMessage(sender, timestamp.time, content())

        fun build(sourceName: String): SourceRecordDraft {
            val legacyContent = legacyContentLines.joinToString("\n").trimEnd()
            val legacyFingerprintText = listOf(sourceName, legacySender, timestamp.legacyDisplay, legacyContent)
                .joinToString("")
            return KakaoMessageRecord.record(
                sender = sender,
                displayTime = timestamp.display,
                canonicalTime = timestamp.canonical,
                text = content(),
                deduplicationAliases = setOf(KakaoMessageRecord.sha256(legacyFingerprintText)),
            )
        }

        private fun content(): String = contentLines.joinToString("\n").trimEnd()
    }

    private data class ParsedTimestamp(
        val time: LocalDateTime?,
        val display: String,
        val canonical: String,
        val legacyDisplay: String,
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
            val display = "$period $time"
            return ParsedTimestamp(null, display, "UNKNOWN_DATE|$display", display)
        }
        return timestamp(
            date,
            period,
            time.substringBefore(':').toInt(),
            time.substringAfter(':').toInt(),
            "$period $time",
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
            legacyDisplay = value,
        )
    }

    private fun timestamp(
        date: LocalDate,
        period: String,
        hour: Int,
        minute: Int,
        legacyDisplay: String,
    ): ParsedTimestamp {
        require(hour in 1..12) { "Kakao hour must be between 1 and 12: $hour" }
        val hourOfDay = (hour % 12) + if (period == "오후") 12 else 0
        val time = LocalDateTime.of(date, LocalTime.of(hourOfDay, minute))
        return ParsedTimestamp(
            time = time,
            display = KakaoMessageRecord.displayTime(time),
            canonical = KakaoMessageRecord.canonicalTime(time),
            legacyDisplay = legacyDisplay,
        )
    }
}

/** One exported message: [sentAt] is Korean local time to the minute, the precision of the export. */
internal data class KakaoExportMessage(
    val sender: String,
    val sentAt: LocalDateTime?,
    val text: String,
)
