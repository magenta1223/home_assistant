package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.slack.SlackRuntime
import com.homeassistant.application.memory.answer.AnswerFromMemoriesUseCase
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysis
import com.homeassistant.application.topicanalysis.save.SaveAnalyzedTopicsUseCase

/** Provides the application's top-level use cases and managed runtimes. */
interface ApplicationServices : AutoCloseable {
    /** Provides the topic-analysis use case. */
    val topicAnalysis: TopicAnalysis

    /** Provides the use case that saves reviewed topic proposals. */
    val saveAnalyzedTopics: SaveAnalyzedTopicsUseCase

    /** Provides the use case that answers questions from canonical memories. */
    val memoryAnswer: AnswerFromMemoriesUseCase

    /** Provides the optional Slack runtime when Slack is configured. */
    val slackRuntime: SlackRuntime?

    /** Starts managed application runtimes. */
    fun start()
}
