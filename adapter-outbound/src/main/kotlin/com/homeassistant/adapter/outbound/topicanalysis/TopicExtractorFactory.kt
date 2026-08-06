package com.homeassistant.adapter.outbound.topicanalysis

import com.homeassistant.adapter.outbound.codex.CodexCliClient
import com.homeassistant.application.topicanalysis.analyze.TopicExtractor

object TopicExtractorFactory {
    fun create(): TopicExtractor = CodexTopicExtractor(CodexCliClient())
}
