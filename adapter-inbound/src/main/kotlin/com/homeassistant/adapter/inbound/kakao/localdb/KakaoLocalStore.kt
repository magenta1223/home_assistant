package com.homeassistant.adapter.inbound.kakao.localdb

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Reads the messages of one allowlisted chat. Everything that depends on where they come from stays behind this
 * boundary; [KakaoLocalDbSync] only sees [KakaoLocalMessage].
 */
interface KakaoLocalStore {
    /**
     * Returns the text messages of [chatKey] sent at or after the minute [from], in send order. A batch holds at
     * least [limit] messages when that many remain, but never splits a minute: it ends with every message of its
     * last minute, so it may hold more than [limit]. Messages that only stand for media are left out.
     */
    fun messagesFrom(chatKey: String, from: Instant, limit: Int): List<KakaoLocalMessage>
}

data class KakaoLocalMessage(
    val senderName: String,
    val text: String,
    val sentAt: Instant,
)

/** Takes [limit] messages of a send-ordered list and then the rest of the last taken minute. */
internal fun List<KakaoLocalMessage>.takeWholeMinutes(limit: Int): List<KakaoLocalMessage> {
    if (size <= limit) return this
    val lastMinute = this[limit - 1].sentAt.truncatedTo(ChronoUnit.MINUTES)
    return take(limit) + drop(limit).takeWhile { it.sentAt.truncatedTo(ChronoUnit.MINUTES) == lastMinute }
}
