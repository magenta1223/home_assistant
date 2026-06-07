package com.homeassistant.domain.kakao

@JvmInline value class KakaoExportText(val value: String)
@JvmInline value class KakaoSourceFileName(val value: String)
@JvmInline value class KakaoSenderName(val value: String)
@JvmInline value class KakaoMessageText(val value: String)
@JvmInline value class KakaoLineNumber(val value: Int)
@JvmInline value class KakaoMessageFingerprint(val value: String)
@JvmInline value class KakaoMessageId(val value: Int)
@JvmInline value class ImportedMessageCount(val value: Int)

data class ParsedKakaoMessage(
    val sourceFileName: KakaoSourceFileName,
    val sender: KakaoSenderName,
    val displayTime: String,
    val text: KakaoMessageText,
    val lineStart: KakaoLineNumber,
    val lineEnd: KakaoLineNumber,
    val fingerprint: KakaoMessageFingerprint,
)

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
