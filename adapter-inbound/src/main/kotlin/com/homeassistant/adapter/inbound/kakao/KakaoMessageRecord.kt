package com.homeassistant.adapter.inbound.kakao

import com.homeassistant.domain.source.SourceRecordDraft
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The Kakao source record format. The deduplication key depends only on sender, minute and text, so re-importing
 * an export, even under another file name, finds the messages that are already stored.
 */
internal object KakaoMessageRecord {
    const val SOURCE_TYPE = "kakao"

    fun record(
        sender: String,
        canonicalTime: String,
        text: String,
        recordedAt: Long,
        deduplicationAliases: Set<String> = emptySet(),
    ): SourceRecordDraft = SourceRecordDraft(
        deduplicationKey = sha256(listOf(sender, canonicalTime, text).joinToString("\u001F")),
        content = "$sender: $text",
        deduplicationAliases = deduplicationAliases,
        recordedAt = recordedAt,
    )

    fun canonicalTime(time: LocalDateTime): String =
        "%04d-%02d-%02dT%02d:%02d".format(time.year, time.monthValue, time.dayOfMonth, time.hour, time.minute)

    /** Kakao exports carry local time without a zone. */
    fun recordedAt(time: LocalDateTime): Long =
        time.atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
