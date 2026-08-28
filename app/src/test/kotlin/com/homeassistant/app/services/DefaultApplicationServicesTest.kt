package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.slack.SlackRuntime
import com.homeassistant.adapter.outbound.embedding.ollama.EmbeddingServerRuntime
import com.homeassistant.adapter.outbound.vector.qdrant.VectorServerRuntime
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultApplicationServicesTest {
    @Test
    fun `starts managed runtimes before inbound runtime and closes them afterward`() {
        val events = mutableListOf<String>()
        val vector = RecordingVectorRuntime(events)
        val embedding = RecordingEmbeddingRuntime(events)
        val indexing = RecordingIndexingWorker(events)
        val expiry = RecordingConversationExpiryWorker(events)
        val codex = AutoCloseable { events += "codex.close" }
        val slack = object : SlackRuntime {
            override fun startAsync() {
                events += "slack.start"
            }

            override fun close() {
                events += "slack.close"
            }
        }
        val services = DefaultApplicationServices(
            memoryAnalysis = unusedMemoryAnalysis(),
            slackRuntime = slack,
            vectorRuntime = vector,
            embeddingRuntime = embedding,
            indexingWorker = indexing,
            conversationExpiryWorker = expiry,
            codexRuntime = codex,
        )

        assertFalse(services.isReady)
        services.start()
        assertTrue(services.isReady)
        services.close()

        assertEquals(
            listOf(
                "vector.start",
                "embedding.start",
                "indexing.start",
                "expiry.start",
                "slack.start",
                "slack.close",
                "expiry.close",
                "codex.close",
                "indexing.close",
                "embedding.close",
                "vector.close",
            ),
            events,
        )
        assertFalse(services.isReady)
    }

    @Test
    fun `closes managed runtimes when inbound runtime close fails`() {
        val events = mutableListOf<String>()
        val vector = RecordingVectorRuntime(events)
        val embedding = RecordingEmbeddingRuntime(events)
        val indexing = RecordingIndexingWorker(events)
        val expiry = RecordingConversationExpiryWorker(events)
        val codex = AutoCloseable { events += "codex.close" }
        val slack = object : SlackRuntime {
            override fun startAsync() = Unit

            override fun close() {
                events += "slack.close"
                error("close failed")
            }
        }
        val services = DefaultApplicationServices(
            memoryAnalysis = unusedMemoryAnalysis(),
            slackRuntime = slack,
            vectorRuntime = vector,
            embeddingRuntime = embedding,
            indexingWorker = indexing,
            conversationExpiryWorker = expiry,
            codexRuntime = codex,
        )
        services.start()

        assertFailsWith<IllegalStateException> { services.close() }

        assertEquals(
            listOf(
                "vector.start",
                "embedding.start",
                "indexing.start",
                "expiry.start",
                "slack.close",
                "expiry.close",
                "codex.close",
                "indexing.close",
                "embedding.close",
                "vector.close",
            ),
            events,
        )
        assertFalse(embedding.isReady)
        assertFalse(vector.isReady)
    }

    private fun unusedMemoryAnalysis() = object : MemoryAnalysis {
        override suspend fun execute(request: MemoryAnalysisRequest) = error("unused")
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

    private class RecordingVectorRuntime(
        private val events: MutableList<String>,
    ) : VectorServerRuntime {
        override var isReady: Boolean = false
            private set

        override fun start() {
            events += "vector.start"
            isReady = true
        }

        override fun close() {
            events += "vector.close"
            isReady = false
        }
    }

    private class RecordingIndexingWorker(
        private val events: MutableList<String>,
    ) : IndexingWorker {
        override fun start() {
            events += "indexing.start"
        }

        override fun close() {
            events += "indexing.close"
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
