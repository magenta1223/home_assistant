package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.slack.SlackRuntime
import com.homeassistant.adapter.outbound.embedding.ollama.EmbeddingServerRuntime
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.answer.MemoryAnswer
import com.homeassistant.domain.identity.UserId

internal class DefaultApplicationServices(
    override val memoryAnalysis: MemoryAnalysis,
    override val memoryGroundedChatbot: MemoryAnswer,
    override val slackRuntime: SlackRuntime?,
    override val memberUserIds: Set<UserId> = emptySet(),
    private val embeddingRuntime: EmbeddingServerRuntime,
    private val indexingWorker: IndexingWorker = IndexingWorker.NONE,
) : ApplicationServices {
    override val isReady: Boolean
        get() = embeddingRuntime.isReady

    override fun start() {
        embeddingRuntime.start()
        indexingWorker.start()
        slackRuntime?.startAsync()
    }

    override fun close() {
        try {
            slackRuntime?.close()
        } finally {
            try {
                indexingWorker.close()
            } finally {
                embeddingRuntime.close()
            }
        }
    }
}
