package com.homeassistant.domain.kakao

import java.security.MessageDigest

/** Parses KakaoTalk text exports into message records while preserving multiline payloads. */
object KakaoMessageParser {
    private val bracketHeader = Regex("^\\[(.+)] \\[(오전|오후) (\\d{1,2}:\\d{2})] ?(.*)$")
    private val exportedHeader = Regex("^(\\d{4}년 \\d{1,2}월 \\d{1,2}일 (?:오전|오후) \\d{1,2}:\\d{2}), (.+?) : ?(.*)$")
    private val exportedDateSeparator = Regex("^\\d{4}년 \\d{1,2}월 \\d{1,2}일 (?:오전|오후) \\d{1,2}:\\d{2}$")

    fun parse(sourceFileName: String, text: String): List<ParsedKakaoMessage> {
        val lines = text.lines()
        val messages = mutableListOf<MessageBuilder>()
        lines.forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = rawLine.trimStart('\uFEFF')
            val bracketMatch = bracketHeader.matchEntire(line)
            val exportedMatch = exportedHeader.matchEntire(line)
            if (bracketMatch == null && exportedMatch == null) {
                if (exportedDateSeparator.matches(line)) return@forEachIndexed
                if (messages.isNotEmpty()) messages.last().append(rawLine, lineNumber)
                return@forEachIndexed
            }

            val sender = bracketMatch?.groupValues?.get(1) ?: exportedMatch!!.groupValues[2]
            val displayTime = bracketMatch
                ?.let { "${it.groupValues[2]} ${it.groupValues[3]}" }
                ?: exportedMatch!!.groupValues[1]
            val content = bracketMatch?.groupValues?.get(4) ?: exportedMatch!!.groupValues[3]
            messages += MessageBuilder(sourceFileName, sender, displayTime, content, lineNumber)
        }

        return messages.map { it.build() }
    }

    private class MessageBuilder(
        private val sourceFileName: String,
        private val sender: String,
        private val displayTime: String,
        initialContent: String,
        private val lineStart: Int,
    ) {
        private val contentLines = mutableListOf(initialContent)
        private var lineEnd = lineStart

        fun append(line: String, lineNumber: Int) {
            contentLines += line
            lineEnd = lineNumber
        }

        fun build(): ParsedKakaoMessage {
            val content = contentLines.joinToString("\n").trimEnd()
            val fingerprintText = listOf(sourceFileName, sender, displayTime, content).joinToString("\u001F")
            return ParsedKakaoMessage(
                sourceFileName = sourceFileName,
                sender = sender,
                displayTime = displayTime,
                text = content,
                lineStart = lineStart,
                lineEnd = lineEnd,
                fingerprint = sha256(fingerprintText),
            )
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
