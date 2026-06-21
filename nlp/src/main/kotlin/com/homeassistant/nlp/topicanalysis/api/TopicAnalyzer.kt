package com.homeassistant.nlp.topicanalysis.api

import com.homeassistant.core.source.SourceDocument
import com.homeassistant.domain.topicanalysis.TopicAnalysisResult

interface TopicAnalyzer {

    suspend fun analyze(document: SourceDocument): TopicAnalysisResult
}
