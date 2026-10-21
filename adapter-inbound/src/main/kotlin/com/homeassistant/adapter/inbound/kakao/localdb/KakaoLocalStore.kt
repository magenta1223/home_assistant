package com.homeassistant.adapter.inbound.kakao.localdb

import java.time.Instant

/**
 * Reads messages from the plaintext KakaoTalk local DB. Everything that depends on the DB schema stays behind
 * this boundary; [KakaoLocalDbSync] only sees [KakaoLocalMessage].
 */
interface KakaoLocalStore {
    /**
     * Returns at most [limit] text messages of [chatId] whose `logId` is greater than [afterLogId],
     * in ascending `logId` order. Non-text messages (photos, emoticons, system notices) are left out.
     */
    fun messagesAfter(chatId: String, afterLogId: Long, limit: Int): List<KakaoLocalMessage>
}

data class KakaoLocalMessage(
    val logId: Long,
    val senderName: String,
    val text: String,
    val sentAt: Instant,
)
