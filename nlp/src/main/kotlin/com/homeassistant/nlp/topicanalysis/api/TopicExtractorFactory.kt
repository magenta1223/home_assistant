package com.homeassistant.nlp.topicanalysis.api

import com.homeassistant.application.topicanalysis.analyze.TopicExtractor
import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.nlp.topicanalysis.impl.LlmTopicAnalyzer

object TopicExtractorFactory {
    fun create(backend: LlmBackend): TopicExtractor = LlmTopicAnalyzer(backend)
}
