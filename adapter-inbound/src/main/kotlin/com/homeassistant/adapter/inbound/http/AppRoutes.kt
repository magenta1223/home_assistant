package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.memory.answer.AnswerFromMemoriesUseCase
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysisUseCase
import com.homeassistant.application.topicanalysis.save.SaveAnalyzedTopicsUseCase
import com.homeassistant.domain.identity.UserId
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing

fun Application.configureRoutes(
    topicAnalysis: TopicAnalysisUseCase,
    saveAnalyzedTopics: SaveAnalyzedTopicsUseCase,
    memoryAnswer: AnswerFromMemoriesUseCase? = null,
    httpApiKeys: Map<String, UserId> = emptyMap(),
) {
    configureHttpAuthentication(httpApiKeys)
    routing {
        healthRoutes()
        authenticate(HTTP_AUTHENTICATION_NAME) {
            kakaoTopicAnalysisRoutes(topicAnalysis, saveAnalyzedTopics)
            memoryAnswerRoutes(memoryAnswer)
        }
    }
}
