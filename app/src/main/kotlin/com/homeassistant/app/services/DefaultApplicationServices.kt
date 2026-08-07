package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.slack.SlackRuntime
import com.homeassistant.application.memory.analysis.MemoryAnalysis
import com.homeassistant.application.memory.memorygroundedchat.MemoryGroundedChatbot

internal class DefaultApplicationServices(
    override val memoryAnalysis: MemoryAnalysis,
    override val memoryGroundedChatbot: MemoryGroundedChatbot,
    override val slackRuntime: SlackRuntime?,
) : ApplicationServices {
    override fun start() {
        slackRuntime?.startAsync()
    }

    override fun close() {
        slackRuntime?.close()
    }
}
