package com.homeassistant.adapter.outbound.codex

import com.homeassistant.application.topicanalysis.analyze.TopicExtractor

object CodexTopicExtractorFactory {
    fun create(): TopicExtractor = CodexTopicExtractor(CodexCliClient())
}
