package com.homeassistant.application.topicanalysis.analyze

import com.homeassistant.domain.kakao.ParsedKakaoMessage

fun interface SourceTextParser {
    fun parse(sourceName: String, text: String): List<ParsedKakaoMessage>
}
