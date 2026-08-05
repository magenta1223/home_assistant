package com.homeassistant.application.topicanalysis

import com.homeassistant.application.topicanalysis.analyze.AnalyzeSourceUseCase
import com.homeassistant.application.topicanalysis.save.SaveTopicCandidatesUseCase

data class TopicAnalysisUseCases(
    val analyzeSource: AnalyzeSourceUseCase,
    val saveTopicCandidates: SaveTopicCandidatesUseCase,
)
