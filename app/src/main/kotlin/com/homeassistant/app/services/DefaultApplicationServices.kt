package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.slack.SlackRuntime
import com.homeassistant.adapter.outbound.embedding.ollama.EmbeddingServerRuntime
import com.homeassistant.adapter.outbound.vector.qdrant.VectorServerRuntime
import com.homeassistant.application.port.input.identity.HouseholdMembers
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis

internal class DefaultApplicationServices(
    override val memoryAnalysis: MemoryAnalysis,
    override val slackRuntime: SlackRuntime?,
    override val householdMembers: HouseholdMembers = HouseholdMembers.NONE,
    private val vectorRuntime: VectorServerRuntime,
    private val embeddingRuntime: EmbeddingServerRuntime,
    private val indexingWorker: IndexingWorker = IndexingWorker.NONE,
) : ApplicationServices {
    override val isReady: Boolean
        get() = vectorRuntime.isReady && embeddingRuntime.isReady

    override fun start() {
        vectorRuntime.start()
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
                try {
                    embeddingRuntime.close()
                } finally {
                    vectorRuntime.close()
                }
            }
        }
    }
}
