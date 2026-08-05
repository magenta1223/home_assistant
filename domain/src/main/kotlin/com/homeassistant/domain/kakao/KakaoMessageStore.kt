package com.homeassistant.domain.kakao

interface KakaoMessageStore {
    fun findExistingFingerprints(fingerprints: Set<String>): Set<String>
    fun importMessages(messages: List<ParsedKakaoMessage>): List<KakaoMessage>
    fun listMessages(sourceFileName: String): List<KakaoMessage>
}
