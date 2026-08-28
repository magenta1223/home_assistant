package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.slack.SlackRuntime
import com.homeassistant.adapter.outbound.embedding.ollama.EmbeddingServerRuntime
import com.homeassistant.adapter.outbound.vector.qdrant.VectorServerRuntime
import com.homeassistant.application.port.input.identity.UserRegistry
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis

internal class DefaultApplicationServices(
    override val memoryAnalysis: MemoryAnalysis,
    override val slackRuntime: SlackRuntime?,
    override val users: UserRegistry = UserRegistry.NONE,
    private val vectorRuntime: VectorServerRuntime,
    private val embeddingRuntime: EmbeddingServerRuntime,
    private val indexingWorker: IndexingWorker = IndexingWorker.NONE,
    private val conversationExpiryWorker: ConversationExpiryWorker = ConversationExpiryWorker.NONE,
    private val codexRuntime: AutoCloseable? = null,
) : ApplicationServices {
    override val isReady: Boolean
        get() = vectorRuntime.isReady && embeddingRuntime.isReady

    override fun start() {
        vectorRuntime.start()
        embeddingRuntime.start()
        indexingWorker.start()
        conversationExpiryWorker.start()
        slackRuntime?.startAsync()
    }

    override fun close() {
        try {
            slackRuntime?.close()
        } finally {
            try {
                conversationExpiryWorker.close()
            } finally {
                try {
                    codexRuntime?.close()
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
}
