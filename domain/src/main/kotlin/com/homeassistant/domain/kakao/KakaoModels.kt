package com.homeassistant.domain.kakao

/** Parsed KakaoTalk message before it is stored. */
data class ParsedKakaoMessage(
    val sourceFileName: String,
    val sender: String,
    val displayTime: String,
    val text: String,
    val lineStart: Int,
    val lineEnd: Int,
    val fingerprint: String,
)

/** KakaoTalk message row stored in the local import database. */
data class KakaoMessage(
    val id: Int,
    val sourceFileName: String,
    val sender: String,
    val displayTime: String,
    val text: String,
    val lineStart: Int,
    val lineEnd: Int,
    val fingerprint: String,
)
