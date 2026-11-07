package com.homeassistant.app.services

import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysis
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAnalysisRequest
import com.homeassistant.infrastructure.runtime.AbstractRuntime
import com.homeassistant.infrastructure.runtime.RuntimeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultApplicationServicesTest {
    @Test
    fun `starts runtimes in order and stops them in reverse order before the conversation gateway`() {
        val events = mutableListOf<String>()
        val services = DefaultApplicationServices(
            episodeAnalysis = unusedEpisodeAnalysis(),
            runtimes = listOf(
                RecordingRuntime("embedding", events),
                RecordingRuntime("assignment", events),
                RecordingRuntime("expiry", events),
            ),
            conversationGateway = AutoCloseable { events += "codex.close" },
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
                "expiry.stop",
                "assignment.stop",
                "embedding.stop",
                "codex.close",
            ),
            events,
        )
        assertFalse(services.isReady)
    }

    @Test
    fun `releases remaining resources when a runtime fails to stop`() {
        val events = mutableListOf<String>()
        val embedding = RecordingRuntime("embedding", events)
        val services = DefaultApplicationServices(
            episodeAnalysis = unusedEpisodeAnalysis(),
            runtimes = listOf(
                embedding,
                RecordingRuntime("expiry", events, stopFailure = IllegalStateException("stop failed")),
            ),
            conversationGateway = AutoCloseable { events += "codex.close" },
        )
        services.start()

        assertFailsWith<IllegalStateException> { services.close() }

        assertEquals(
            listOf("embedding.start", "expiry.start", "expiry.stop", "embedding.stop", "codex.close"),
            events,
        )
        assertEquals(RuntimeState.TERMINATED, embedding.state)
    }

    private fun unusedEpisodeAnalysis() = object : EpisodeAnalysis {
        override suspend fun execute(request: EpisodeAnalysisRequest) = error("unused")
    }

    private class RecordingRuntime(
        private val name: String,
        private val events: MutableList<String>,
        private val stopFailure: Throwable? = null,
    ) : AbstractRuntime() {
        override fun onStart() {
            events += "$name.start"
        }

        override fun onStop() {
            events += "$name.stop"
            stopFailure?.let { throw it }
        }
    }
}
