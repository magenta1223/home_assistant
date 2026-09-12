package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.slack.SlackRuntime
import com.homeassistant.application.port.input.identity.UserRegistry
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.conversation.MemoryConversation
import com.homeassistant.application.port.input.memory.tree.VisibleMemoryTree

/** Provides the application's top-level use cases and managed runtimes. */
interface ApplicationServices : AutoCloseable {
    /** Provides the memory-analysis use case. */
    val memoryAnalysis: MemoryAnalysis

    /** Provides memory-backed conversations when the local Codex runtime is available. */
    val memoryConversation: MemoryConversation?

    /** Provides the read-only canonical-memory tree visible to one user. */
    val visibleMemoryTree: VisibleMemoryTree

    /** Provides the optional Slack runtime when Slack is configured. */
    val slackRuntime: SlackRuntime?

    /** Registered application users that may be selected as knowledge viewers. */
    val users: UserRegistry

    /** Reports whether required managed runtimes are available. */
    val isReady: Boolean

    /** Starts managed application runtimes. */
    fun start()
}
