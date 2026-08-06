package com.homeassistant.adapter.inbound.http

import com.homeassistant.application.memory.answer.MemoryAnswerUseCase
import com.homeassistant.application.topicanalysis.analyze.AnalyzeSourceUseCase
import com.homeassistant.application.topicanalysis.save.SaveTopicCandidatesUseCase
import io.ktor.server.application.Application
import io.ktor.server.routing.routing

fun Application.configureRoutes(
    analyzeSource: AnalyzeSourceUseCase,
    saveTopicCandidates: SaveTopicCandidatesUseCase,
    memoryAnswer: MemoryAnswerUseCase? = null,
) {
    routing {
        healthRoutes()
        kakaoTopicAnalysisRoutes(analyzeSource, saveTopicCandidates)
        memoryAnswerRoutes(memoryAnswer)
    }
}
