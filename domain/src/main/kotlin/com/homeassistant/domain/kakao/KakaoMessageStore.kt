package com.homeassistant.domain.kakao

import com.homeassistant.datamodel.kakao.KakaoMessage

interface KakaoMessageStore {
    fun importMessages(messages: List<ParsedKakaoMessage>): List<KakaoMessage>
    fun listMessages(sourceFileName: String): List<KakaoMessage>
}
