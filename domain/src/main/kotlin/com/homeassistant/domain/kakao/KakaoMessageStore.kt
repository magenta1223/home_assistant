package com.homeassistant.domain.kakao

import com.homeassistant.datamodel.kakao.KakaoMessage

interface KakaoMessageStore {
    fun findExistingFingerprints(fingerprints: Set<String>): Set<String>
    fun importMessages(messages: List<ParsedKakaoMessage>): List<KakaoMessage>
    fun listMessages(sourceFileName: String): List<KakaoMessage>
}
