package com.homeassistant.nlp.topicanalysis.api

import com.homeassistant.datamodel.topicanalysis.Topic
import com.homeassistant.datamodel.topicanalysis.TopicCandidate
import kotlinx.serialization.Serializable

@Serializable
data class TopicAnalysisRequest(
    val sourceType: String,
    val sourceName: String,
    val text: String,
)


@Serializable
data class TopicAnalysisResult(
    val previewId: String,
    val sourceType: String,
    val sourceName: String,
    val importedRecordCount: Int,
    val topics: List<TopicCandidate>,
)

@Serializable
data class TopicAnalysisSaveRequest(
    val previewId: String
)

@Serializable
data class TopicAnalysisSaveResult(
    val previewId: String,
    val topics: List<Topic>,
)

@Serializable
data class TopicAnalysisSelectionSaveRequest(
    val previewId: String,
    val selectedTopicIndices: Set<Int>,
)

@Serializable
data class TopicAnalysisModelEvalRunResult(
    val outputPath: String,
    val sourceName: String,
    val results: List<TopicAnalysisModelEvalResult>,
)

@Serializable
data class TopicAnalysisModelEvalRequest(
    val models: List<String>? = null,
)

@Serializable
data class TopicAnalysisModelEvalResult(
    val model: String,
    val parseSucceeded: Boolean,
    val errorMessage: String?,
    val rawResponseCount: Int,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
)

