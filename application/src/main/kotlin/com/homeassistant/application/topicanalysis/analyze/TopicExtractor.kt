package com.homeassistant.application.topicanalysis.analyze

import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.topicanalysis.TopicProposal

interface TopicExtractor {
    suspend fun analyze(document: SourceDocument): List<TopicProposal>
}

class TopicExtractionException(message: String) : RuntimeException(message)
