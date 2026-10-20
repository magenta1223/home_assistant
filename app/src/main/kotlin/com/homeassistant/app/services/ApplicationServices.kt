package com.homeassistant.app.services

import com.homeassistant.application.port.input.identity.UserRegistry
import com.homeassistant.application.port.input.group.OrganizePendingTopics
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.conversation.MemoryConversation
import com.homeassistant.application.port.input.topic.DescribeTopic
import com.homeassistant.application.port.input.topic.OrganizePendingMemories

/** Provides the application's top-level use cases and managed runtimes. */
interface ApplicationServices : AutoCloseable {
    /** Provides the memory-analysis use case. */
    val memoryAnalysis: MemoryAnalysis

    /** Provides memory-backed conversations when the selected agent backend CLI is available. */
    val memoryConversation: MemoryConversation?

    /** Registered application users that may be selected as knowledge viewers. */
    val users: UserRegistry

    /** Explicit trusted maintenance; starting the application does not run this use case. */
    val organizeTopics: OrganizePendingTopics

    /** Memory → Topic assignment from the outbox, then Topic → Group; the started worker runs it periodically. */
    val organizeMemories: OrganizePendingMemories

    /** Describes a Topic per viewer on demand; nothing is generated ahead of a request. */
    val describeTopic: DescribeTopic

    /** Reports whether required managed runtimes are available. */
    val isReady: Boolean

    /** Starts managed application runtimes. */
    fun start()
}
