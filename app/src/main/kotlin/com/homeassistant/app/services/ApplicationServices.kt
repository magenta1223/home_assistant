package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.slack.SlackRuntime
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.answer.MemoryAnswer
import com.homeassistant.domain.identity.UserId

/** Provides the application's top-level use cases and managed runtimes. */
interface ApplicationServices : AutoCloseable {
    /** Provides the memory-analysis use case. */
    val memoryAnalysis: MemoryAnalysis

    /** Provides the use case that answers questions from canonical memories. */
    val memoryGroundedChatbot: MemoryAnswer

    /** Provides the optional Slack runtime when Slack is configured. */
    val slackRuntime: SlackRuntime?

    /** Users that may be selected as knowledge viewers. */
    val memberUserIds: Set<UserId>

    /** Reports whether required managed runtimes are available. */
    val isReady: Boolean

    /** Starts managed application runtimes. */
    fun start()
}
