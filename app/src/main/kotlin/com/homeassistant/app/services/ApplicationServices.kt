package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.slack.SlackRuntime
import com.homeassistant.application.memory.analysis.MemoryAnalysis
import com.homeassistant.application.memory.memorygroundedchat.MemoryGroundedChatbot

/** Provides the application's top-level use cases and managed runtimes. */
interface ApplicationServices : AutoCloseable {
    /** Provides the memory-analysis use case. */
    val memoryAnalysis: MemoryAnalysis

    /** Provides the use case that answers questions from canonical memories. */
    val memoryGroundedChatbot: MemoryGroundedChatbot

    /** Provides the optional Slack runtime when Slack is configured. */
    val slackRuntime: SlackRuntime?

    /** Starts managed application runtimes. */
    fun start()
}
