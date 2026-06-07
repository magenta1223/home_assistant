package com.homeassistant.domain.kakao

/** Raw text from a KakaoTalk export file. */
@JvmInline value class KakaoExportText(val value: String)

/** Source file name for an imported KakaoTalk export. */
@JvmInline value class KakaoSourceFileName(val value: String)

/** Display name of the KakaoTalk message sender. */
@JvmInline value class KakaoSenderName(val value: String)

/** Message body parsed from a KakaoTalk export. */
@JvmInline value class KakaoMessageText(val value: String)

/** One-based line number in the source KakaoTalk export. */
@JvmInline value class KakaoLineNumber(val value: Int)

/** Stable deduplication key for a KakaoTalk message. */
@JvmInline value class KakaoMessageFingerprint(val value: String)

/** Persistent identifier for an imported KakaoTalk message. */
@JvmInline value class KakaoMessageId(val value: Int)

/** Number of newly imported messages after deduplication. */
@JvmInline value class ImportedMessageCount(val value: Int)

/** Parsed KakaoTalk message before it is stored. */
data class ParsedKakaoMessage(
    val sourceFileName: KakaoSourceFileName,
    val sender: KakaoSenderName,
    val displayTime: String,
    val text: KakaoMessageText,
    val lineStart: KakaoLineNumber,
    val lineEnd: KakaoLineNumber,
    val fingerprint: KakaoMessageFingerprint,
)

/** KakaoTalk message row stored in the local import database. */
data class KakaoImportedMessage(
    val id: KakaoMessageId,
    val sourceFileName: KakaoSourceFileName,
    val sender: KakaoSenderName,
    val displayTime: String,
    val text: KakaoMessageText,
    val lineStart: KakaoLineNumber,
    val lineEnd: KakaoLineNumber,
    val fingerprint: KakaoMessageFingerprint,
)
