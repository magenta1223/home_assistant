package com.homeassistant.adapter.inbound.kakao

import com.homeassistant.domain.source.SourceDocumentDraft
import com.homeassistant.domain.source.SourceRecordDraft
import com.homeassistant.domain.source.SourceDescriptor

import java.security.MessageDigest
import java.time.LocalDate
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

    fun parse(sourceName: String, text: String): SourceDocumentDraft {
        val lines = text.lines()
        val messages = mutableListOf<MessageBuilder>()
        var bracketDate: LocalDate? = null
        lines.forEach { rawLine ->
            val line = rawLine.trimStart('\uFEFF')
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

        return SourceDocumentDraft(
            source = SourceDescriptor("kakao", sourceName),
            records = messages.map { it.build() },
        )
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

        fun build(): SourceRecordDraft {
            val content = contentLines.joinToString("\n").trimEnd()
            val fingerprintText = listOf(sender, timestamp.canonical, content).joinToString("\u001F")
            return SourceRecordDraft(
                deduplicationKey = sha256(fingerprintText),
                content = "$sender | ${timestamp.display} | $content",
            )
        }
    }

    private data class ParsedTimestamp(
        val display: String,
        val canonical: String,
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
            return ParsedTimestamp(display, "UNKNOWN_DATE|$display")
        }
        return timestamp(date, period, time.substringBefore(':').toInt(), time.substringAfter(':').toInt())
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

    private fun timestamp(date: LocalDate, period: String, hour: Int, minute: Int): ParsedTimestamp {
        val hourOfDay = (hour % 12) + if (period == "오후") 12 else 0
        val time = LocalTime.of(hourOfDay, minute)
        return ParsedTimestamp(
            display = "${date.year}년 ${date.monthValue}월 ${date.dayOfMonth}일 $period ${time.hour12()}:" +
                time.minute.toString().padStart(2, '0'),
            canonical = "%04d-%02d-%02dT%02d:%02d".format(
                date.year,
                date.monthValue,
                date.dayOfMonth,
                time.hour,
                time.minute,
            ),
        )
    }

    private fun LocalTime.hour12(): Int = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
