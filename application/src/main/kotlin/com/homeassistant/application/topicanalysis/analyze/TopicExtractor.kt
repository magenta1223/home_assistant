package com.homeassistant.application.topicanalysis.analyze

import com.homeassistant.domain.source.SourceDocument
import com.homeassistant.domain.topicanalysis.TopicAnalysisResult

interface TopicExtractor {
    suspend fun analyze(document: SourceDocument): TopicAnalysisResult
}
