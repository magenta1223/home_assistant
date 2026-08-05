package com.homeassistant.application.topicanalysis

import com.homeassistant.application.topicanalysis.analyze.AnalyzeSource
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisRequest
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisResult
import com.homeassistant.application.topicanalysis.save.SaveTopicCandidates
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSaveRequest
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSaveResult
import com.homeassistant.application.topicanalysis.save.TopicAnalysisSelectionSaveRequest

internal class DefaultTopicAnalysisUseCase(
    private val analyzeSource: AnalyzeSource,
    private val saveTopicCandidates: SaveTopicCandidates,
) : TopicAnalysisUseCase {
    override suspend fun analyze(request: TopicAnalysisRequest): TopicAnalysisResult =
        analyzeSource.execute(request)

    override suspend fun saveAnalysis(request: TopicAnalysisSaveRequest): TopicAnalysisSaveResult =
        saveTopicCandidates.saveAll(request)

    override suspend fun saveSelectedAnalysis(request: TopicAnalysisSelectionSaveRequest): TopicAnalysisSaveResult =
        saveTopicCandidates.saveSelected(request)
}
