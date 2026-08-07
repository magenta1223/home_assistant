package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.slack.SlackRuntime
import com.homeassistant.application.memory.answer.AnswerFromMemoriesUseCase
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisUseCase
import com.homeassistant.application.topicanalysis.save.SaveAnalyzedTopicsUseCase

interface ApplicationServices : AutoCloseable {
    val topicAnalysis: TopicAnalysisUseCase
    val saveAnalyzedTopics: SaveAnalyzedTopicsUseCase
    val memoryAnswer: AnswerFromMemoriesUseCase
    val slackRuntime: SlackRuntime?
    fun start()
}