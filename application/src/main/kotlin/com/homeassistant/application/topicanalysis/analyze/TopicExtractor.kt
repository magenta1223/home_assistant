package com.homeassistant.application.topicanalysis.analyze

import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.topicanalysis.TopicProposal

/** Converts a source document into proposed topics. */
interface TopicExtractor {
    /** Extracts topic proposals from the supplied source document. */
    suspend fun analyze(document: SourceDocument): List<TopicProposal>
}

class TopicExtractionException(message: String) : RuntimeException(message)
