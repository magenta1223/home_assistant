package com.homeassistant.app.services

import com.homeassistant.adapter.outbound.embedding.ollama.EmbeddingServerRuntime
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysis
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultApplicationServicesTest {
    @Test
    fun `starts and closes only managed HTTP application resources`() {
        val events = mutableListOf<String>()
        val embedding = RecordingEmbeddingRuntime(events)
        val expiry = RecordingConversationExpiryWorker(events)
        val assignment = RecordingComponentAssignmentWorker(events)
        val codex = AutoCloseable { events += "codex.close" }
        val services = DefaultApplicationServices(
            episodeAnalysis = unusedEpisodeAnalysis(),
            embeddingRuntime = embedding,
            conversationExpiryWorker = expiry,
            componentAssignmentWorker = assignment,
            conversationGateway = codex,
        )

        assertFalse(services.isReady)
        services.start()
        assertTrue(services.isReady)
        services.close()

        assertEquals(
            listOf(
                "embedding.start",
                "assignment.start",
                "expiry.start",
                "expiry.close",
                "assignment.close",
                "codex.close",
                "embedding.close",
            ),
            events,
        )
        assertFalse(services.isReady)
    }

    @Test
    fun `closes remaining resources when conversation expiry close fails`() {
        val events = mutableListOf<String>()
        val embedding = RecordingEmbeddingRuntime(events)
        val expiry = object : ConversationExpiryWorker {
            override fun start() { events += "expiry.start" }
            override fun close() {
                events += "expiry.close"
                error("close failed")
            }
        }
        val codex = AutoCloseable { events += "codex.close" }
        val services = DefaultApplicationServices(
            episodeAnalysis = unusedEpisodeAnalysis(),
            embeddingRuntime = embedding,
            conversationExpiryWorker = expiry,
            conversationGateway = codex,
        )
        services.start()

        assertFailsWith<IllegalStateException> { services.close() }

        assertEquals(
            listOf(
                "embedding.start",
                "expiry.start",
                "expiry.close",
                "codex.close",
                "embedding.close",
            ),
            events,
        )
        assertFalse(embedding.isReady)
    }

    private class RecordingComponentAssignmentWorker(
        private val events: MutableList<String>,
    ) : ComponentAssignmentWorker {
        override fun start() {
            events += "assignment.start"
        }

        override fun close() {
            events += "assignment.close"
        }
    }

    private fun unusedEpisodeAnalysis() = object : EpisodeAnalysis {
        override suspend fun execute(request: EpisodeAnalysisRequest) = error("unused")
    }

    private class RecordingEmbeddingRuntime(
        private val events: MutableList<String>,
    ) : EmbeddingServerRuntime {
        override var isReady: Boolean = false
            private set

        override fun start() {
            events += "embedding.start"
            isReady = true
        }

        override fun close() {
            events += "embedding.close"
            isReady = false
        }
    }

    private class RecordingConversationExpiryWorker(
        private val events: MutableList<String>,
    ) : ConversationExpiryWorker {
        override fun start() {
            events += "expiry.start"
        }

        override fun close() {
            events += "expiry.close"
        }
    }
}
