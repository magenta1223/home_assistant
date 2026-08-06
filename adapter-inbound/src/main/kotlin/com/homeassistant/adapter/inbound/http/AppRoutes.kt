package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.memory.answer.AnswerFromMemoriesUseCase
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisUseCase
import com.homeassistant.application.topicanalysis.save.SaveAnalyzedTopicsUseCase
import io.ktor.server.application.Application
import io.ktor.server.routing.routing

fun Application.configureRoutes(
    topicAnalysis: TopicAnalysisUseCase,
    saveAnalyzedTopics: SaveAnalyzedTopicsUseCase,
    memoryAnswer: AnswerFromMemoriesUseCase? = null,
) {
    routing {
        healthRoutes()
        kakaoTopicAnalysisRoutes(topicAnalysis, saveAnalyzedTopics)
        memoryAnswerRoutes(memoryAnswer)
    }
}
