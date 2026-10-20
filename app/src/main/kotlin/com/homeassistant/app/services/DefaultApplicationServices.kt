package com.homeassistant.app.services

import com.homeassistant.adapter.outbound.embedding.ollama.EmbeddingServerRuntime
import com.homeassistant.application.port.input.identity.UserRegistry
import com.homeassistant.application.port.input.group.OrganizePendingTopics
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.conversation.MemoryConversation
import com.homeassistant.application.port.input.topic.OrganizePendingMemories

internal class DefaultApplicationServices(
    override val memoryAnalysis: MemoryAnalysis,
    override val organizeTopics: OrganizePendingTopics,
    override val organizeMemories: OrganizePendingMemories,
    override val memoryConversation: MemoryConversation? = null,
    override val users: UserRegistry = UserRegistry.NONE,
    private val embeddingRuntime: EmbeddingServerRuntime,
    private val conversationExpiryWorker: ConversationExpiryWorker = ConversationExpiryWorker.NONE,
    private val topicAssignmentWorker: TopicAssignmentWorker = TopicAssignmentWorker.NONE,
    private val conversationGateway: AutoCloseable? = null,
) : ApplicationServices {
    override val isReady: Boolean
        get() = embeddingRuntime.isReady

    override fun start() {
        embeddingRuntime.start()
        topicAssignmentWorker.start()
        conversationExpiryWorker.start()
    }

    override fun close() {
        try {
            conversationExpiryWorker.close()
        } finally {
            try {
                topicAssignmentWorker.close()
            } finally {
                try {
                    conversationGateway?.close()
                } finally {
                    embeddingRuntime.close()
                }
            }
        }
    }
}
