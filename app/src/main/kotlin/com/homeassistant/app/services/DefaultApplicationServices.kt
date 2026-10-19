package com.homeassistant.app.services

import com.homeassistant.adapter.outbound.embedding.ollama.EmbeddingServerRuntime
import com.homeassistant.adapter.outbound.vector.qdrant.VectorServerRuntime
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
    private val vectorRuntime: VectorServerRuntime,
    private val embeddingRuntime: EmbeddingServerRuntime,
    private val indexingWorker: IndexingWorker = IndexingWorker.NONE,
    private val conversationExpiryWorker: ConversationExpiryWorker = ConversationExpiryWorker.NONE,
    private val conversationGateway: AutoCloseable? = null,
) : ApplicationServices {
    override val isReady: Boolean
        get() = vectorRuntime.isReady && embeddingRuntime.isReady

    override fun start() {
        vectorRuntime.start()
        embeddingRuntime.start()
        indexingWorker.start()
        conversationExpiryWorker.start()
    }

    override fun close() {
        try {
            conversationExpiryWorker.close()
        } finally {
            try {
                conversationGateway?.close()
            } finally {
                try {
                    indexingWorker.close()
                } finally {
                    try {
                        embeddingRuntime.close()
                    } finally {
                        vectorRuntime.close()
                    }
                }
            }
        }
    }
}
