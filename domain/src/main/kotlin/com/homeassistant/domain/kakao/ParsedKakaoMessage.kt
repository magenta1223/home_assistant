package com.homeassistant.domain.kakao

/**
 * Parsed KakaoTalk message before it is stored.
 *
 * @property sourceFileName Name of the Kakao export file that produced the message.
 * @property sender Display name of the sender in the export.
 * @property displayTime Timestamp text as written in the export.
 * @property text Message body, including preserved multiline content.
 * @property lineStart First source line included in this parsed message.
 * @property lineEnd Last source line included in this parsed message.
 * @property fingerprint Deduplication key derived from the parsed message.
 */
data class ParsedKakaoMessage(
    val sourceFileName: String,
    val sender: String,
    val displayTime: String,
    val text: String,
    val lineStart: Int,
    val lineEnd: Int,
    val fingerprint: String,
)
