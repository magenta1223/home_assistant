package com.homeassistant.adapter.inbound.kakao

import com.homeassistant.domain.source.SourceRecordDraft
import java.security.MessageDigest
import java.time.LocalDateTime

/**
 * The Kakao source record format. The deduplication key depends only on sender, minute and text, so re-importing
 * an export, even under another file name, finds the messages that are already stored.
 */
internal object KakaoMessageRecord {
    const val SOURCE_TYPE = "kakao"

    fun record(
        sender: String,
        displayTime: String,
        canonicalTime: String,
        text: String,
        deduplicationAliases: Set<String> = emptySet(),
    ): SourceRecordDraft = SourceRecordDraft(
        deduplicationKey = sha256(listOf(sender, canonicalTime, text).joinToString("\u001F")),
        content = "$sender | $displayTime | $text",
        deduplicationAliases = deduplicationAliases,
    )

    fun displayTime(time: LocalDateTime): String {
        val period = if (time.hour < 12) "오전" else "오후"
        return "${time.year}년 ${time.monthValue}월 ${time.dayOfMonth}일 $period ${time.hour12()}:" +
            time.minute.toString().padStart(2, '0')
    }

    fun canonicalTime(time: LocalDateTime): String =
        "%04d-%02d-%02dT%02d:%02d".format(time.year, time.monthValue, time.dayOfMonth, time.hour, time.minute)

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun LocalDateTime.hour12(): Int = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
}
