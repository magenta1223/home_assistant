package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.slack.SlackRuntime
import com.homeassistant.application.memory.answer.AnswerFromMemoriesUseCase
import com.homeassistant.application.memory.analysis.MemoryAnalysis

internal class DefaultApplicationServices(
    override val memoryAnalysis: MemoryAnalysis,
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
