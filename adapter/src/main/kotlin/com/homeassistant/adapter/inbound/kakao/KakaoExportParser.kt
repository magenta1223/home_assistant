package com.homeassistant.adapter.inbound.kakao

import com.homeassistant.application.topicanalysis.analyze.SourceTextParser
import com.homeassistant.domain.source.SourceRecordDraft

import java.security.MessageDigest

/** Parses KakaoTalk text exports into message records while preserving multiline payloads. */
object KakaoExportParser : SourceTextParser {
    private val bracketHeader = Regex("^\\[(.+)] \\[(오전|오후) (\\d{1,2}:\\d{2})] ?(.*)$")
    private val exportedHeader = Regex("^(\\d{4}년 \\d{1,2}월 \\d{1,2}일 (?:오전|오후) \\d{1,2}:\\d{2}), (.+?) : ?(.*)$")
    private val dottedExportedHeader = Regex("^(\\d{4}\\.\\s*\\d{1,2}\\.\\s*\\d{1,2}\\.\\s*(?:오전|오후)\\s*\\d{1,2}:\\d{2}),\\s*(.+?) : ?(.*)$")
    private val exportedDateSeparator = Regex("^\\d{4}년 \\d{1,2}월 \\d{1,2}일 (?:오전|오후) \\d{1,2}:\\d{2}$")
    private val dottedDateSeparator = Regex("^\\d{4}\\.\\s*\\d{1,2}\\.\\s*\\d{1,2}\\.\\s*(?:오전|오후)\\s*\\d{1,2}:\\d{2}$")

    override fun parse(sourceName: String, text: String): List<SourceRecordDraft> {
        val lines = text.lines()
        val messages = mutableListOf<MessageBuilder>()
        lines.forEach { rawLine ->
            val line = rawLine.trimStart('\uFEFF')
            val bracketMatch = bracketHeader.matchEntire(line)
            val exportedMatch = exportedHeader.matchEntire(line)
            val dottedExportedMatch = dottedExportedHeader.matchEntire(line)
            if (bracketMatch == null && exportedMatch == null && dottedExportedMatch == null) {
                if (exportedDateSeparator.matches(line) || dottedDateSeparator.matches(line)) return@forEach
                if (messages.isNotEmpty()) messages.last().append(rawLine)
                return@forEach
            }

            val sender = bracketMatch?.groupValues?.get(1)
                ?: exportedMatch?.groupValues?.get(2)
                ?: dottedExportedMatch!!.groupValues[2]
            val displayTime = bracketMatch
                ?.let { "${it.groupValues[2]} ${it.groupValues[3]}" }
                ?: exportedMatch?.groupValues?.get(1)
                ?: dottedExportedMatch!!.groupValues[1]
            val content = bracketMatch?.groupValues?.get(4)
                ?: exportedMatch?.groupValues?.get(3)
                ?: dottedExportedMatch!!.groupValues[3]
            messages += MessageBuilder(sourceName, sender, displayTime, content)
        }

        return messages.map { it.build() }
    }

    private class MessageBuilder(
        private val sourceFileName: String,
        private val sender: String,
        private val displayTime: String,
        initialContent: String,
    ) {
        private val contentLines = mutableListOf(initialContent)

        fun append(line: String) {
            contentLines += line
        }

        fun build(): SourceRecordDraft {
            val content = contentLines.joinToString("\n").trimEnd()
            val fingerprintText = listOf(sourceFileName, sender, displayTime, content).joinToString("\u001F")
            return SourceRecordDraft(
                sourceType = "kakao",
                sourceName = sourceFileName,
                deduplicationKey = sha256(fingerprintText),
                content = "$sender | $displayTime | $content",
            )
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
