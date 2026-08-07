package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.slack.SlackRuntime
import com.homeassistant.application.memory.answer.AnswerFromMemoriesUseCase
import com.homeassistant.application.topicanalysis.analyze.TopicAnalysis

internal class DefaultApplicationServices(
    override val topicAnalysis: TopicAnalysis,
    override val memoryAnswer: AnswerFromMemoriesUseCase,
    override val slackRuntime: SlackRuntime?,
) : ApplicationServices {
    override fun start() {
        slackRuntime?.startAsync()
    }

    override fun close() {
        slackRuntime?.close()
    }
}
