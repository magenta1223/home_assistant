package com.homeassistant.app.services

import com.homeassistant.adapter.inbound.slack.SlackRuntime
import com.homeassistant.adapter.outbound.embedding.ollama.EmbeddingServerRuntime
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultApplicationServicesTest {
    @Test
    fun `starts embedding before inbound runtime and closes it last`() {
        val events = mutableListOf<String>()
        val embedding = RecordingEmbeddingRuntime(events)
        val indexing = RecordingIndexingWorker(events)
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
            embeddingRuntime = embedding,
            indexingWorker = indexing,
        )

        assertFalse(services.isReady)
        services.start()
        assertTrue(services.isReady)
        services.close()

        assertEquals(
            listOf(
                "embedding.start",
                "indexing.start",
                "slack.start",
                "slack.close",
                "indexing.close",
                "embedding.close",
            ),
            events,
        )
        assertFalse(services.isReady)
    }

    @Test
    fun `closes embedding runtime when inbound runtime close fails`() {
        val events = mutableListOf<String>()
        val embedding = RecordingEmbeddingRuntime(events)
        val indexing = RecordingIndexingWorker(events)
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
            embeddingRuntime = embedding,
            indexingWorker = indexing,
        )
        services.start()

        assertFailsWith<IllegalStateException> { services.close() }

        assertEquals(
            listOf(
                "embedding.start",
                "indexing.start",
                "slack.close",
                "indexing.close",
                "embedding.close",
            ),
            events,
        )
        assertFalse(embedding.isReady)
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
}
