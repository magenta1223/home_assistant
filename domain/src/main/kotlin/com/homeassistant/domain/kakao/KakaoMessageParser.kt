package com.homeassistant.domain.kakao

import java.security.MessageDigest

/** Parses KakaoTalk text exports into message records while preserving multiline payloads. */
object KakaoMessageParser {
    private val header = Regex("^\\[(.+)] \\[(오전|오후) (\\d{1,2}:\\d{2})] ?(.*)$")

    fun parse(sourceFileName: KakaoSourceFileName, text: KakaoExportText): List<ParsedKakaoMessage> {
        val lines = text.value.lines()
        val messages = mutableListOf<MessageBuilder>()
        lines.forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val match = header.matchEntire(rawLine)
            if (match == null) {
                if (messages.isNotEmpty()) messages.last().append(rawLine, lineNumber)
                return@forEachIndexed
            }

            val sender = match.groupValues[1]
            val displayTime = "${match.groupValues[2]} ${match.groupValues[3]}"
            val content = match.groupValues[4]
            messages += MessageBuilder(sourceFileName, sender, displayTime, content, lineNumber)
        }

        return messages.map { it.build() }
    }

    private class MessageBuilder(
        private val sourceFileName: KakaoSourceFileName,
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
            val fingerprintText = listOf(sourceFileName.value, sender, displayTime, content).joinToString("\u001F")
            return ParsedKakaoMessage(
                sourceFileName = sourceFileName,
                sender = KakaoSenderName(sender),
                displayTime = displayTime,
                text = KakaoMessageText(content),
                lineStart = KakaoLineNumber(lineStart),
                lineEnd = KakaoLineNumber(lineEnd),
                fingerprint = KakaoMessageFingerprint(sha256(fingerprintText)),
            )
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
