package com.homeassistant.app.services

import com.homeassistant.adapter.outbound.embedding.ollama.EmbeddingServerRuntime
import com.homeassistant.adapter.outbound.vector.qdrant.VectorServerRuntime
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysis
import com.homeassistant.application.port.input.memory.analysis.MemoryAnalysisRequest
import com.homeassistant.application.port.input.group.OrganizePendingTopics
import com.homeassistant.application.port.input.topic.OrganizePendingMemories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultApplicationServicesTest {
    @Test
    fun `starts and closes only managed HTTP application resources`() {
        val events = mutableListOf<String>()
        val vector = RecordingVectorRuntime(events)
        val embedding = RecordingEmbeddingRuntime(events)
        val indexing = RecordingIndexingWorker(events)
        val expiry = RecordingConversationExpiryWorker(events)
        val assignment = RecordingTopicAssignmentWorker(events)
        val codex = AutoCloseable { events += "codex.close" }
        val services = DefaultApplicationServices(
            memoryAnalysis = unusedMemoryAnalysis(),
            organizeTopics = OrganizePendingTopics { error("must only run on explicit invocation") },
            organizeMemories = unusedOrganizeMemories(),
            vectorRuntime = vector,
            embeddingRuntime = embedding,
            indexingWorker = indexing,
            conversationExpiryWorker = expiry,
            topicAssignmentWorker = assignment,
            conversationGateway = codex,
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
                "assignment.start",
                "expiry.start",
                "expiry.close",
                "assignment.close",
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
    fun `closes remaining resources when conversation expiry close fails`() {
        val events = mutableListOf<String>()
        val vector = RecordingVectorRuntime(events)
        val embedding = RecordingEmbeddingRuntime(events)
        val indexing = RecordingIndexingWorker(events)
        val expiry = object : ConversationExpiryWorker {
            override fun start() { events += "expiry.start" }
            override fun close() {
                events += "expiry.close"
                error("close failed")
            }
        }
        val codex = AutoCloseable { events += "codex.close" }
        val services = DefaultApplicationServices(
            memoryAnalysis = unusedMemoryAnalysis(),
            organizeTopics = OrganizePendingTopics { error("must only run on explicit invocation") },
            organizeMemories = unusedOrganizeMemories(),
            vectorRuntime = vector,
            embeddingRuntime = embedding,
            indexingWorker = indexing,
            conversationExpiryWorker = expiry,
            conversationGateway = codex,
        )
        services.start()

        assertFailsWith<IllegalStateException> { services.close() }

        assertEquals(
            listOf(
                "vector.start",
                "embedding.start",
                "indexing.start",
                "expiry.start",
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

    private fun unusedOrganizeMemories() = object : OrganizePendingMemories {
        override fun isReady() = error("must only run from the topic assignment worker")
        override fun execute(limit: Int) = error("must only run from the topic assignment worker")
        override fun enqueueUnassigned() = error("must only run from the topic assignment worker")
    }

    private class RecordingTopicAssignmentWorker(
        private val events: MutableList<String>,
    ) : TopicAssignmentWorker {
        override fun start() {
            events += "assignment.start"
        }

        override fun close() {
            events += "assignment.close"
        }
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
