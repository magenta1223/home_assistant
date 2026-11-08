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

class ApplicationServicesTest {
    @Test
    fun `starts runtimes in order and stops them in reverse order`() {
        val events = mutableListOf<String>()
        val services = ApplicationServices(
            episodeAnalysis = unusedEpisodeAnalysis(),
            runtimes = listOf(
                RecordingRuntime("embedding", events),
                RecordingRuntime("codex", events),
                RecordingRuntime("assignment", events),
                RecordingRuntime("expiry", events),
            ),
        )

        assertFalse(services.isReady)
        services.start()
        assertTrue(services.isReady)
        services.close()

        assertEquals(
            listOf(
                "embedding.start",
                "codex.start",
                "assignment.start",
                "expiry.start",
                "expiry.stop",
                "assignment.stop",
                "codex.stop",
                "embedding.stop",
            ),
            events,
        )
        assertFalse(services.isReady)
    }

    @Test
    fun `stops remaining runtimes when one fails to stop`() {
        val events = mutableListOf<String>()
        val embedding = RecordingRuntime("embedding", events)
        val services = ApplicationServices(
            episodeAnalysis = unusedEpisodeAnalysis(),
            runtimes = listOf(
                embedding,
                RecordingRuntime("expiry", events, stopFailure = IllegalStateException("stop failed")),
            ),
        )
        services.start()

        assertFailsWith<IllegalStateException> { services.close() }

        assertEquals(
            listOf("embedding.start", "expiry.start", "expiry.stop", "embedding.stop"),
            events,
        )
        assertEquals(RuntimeState.TERMINATED, embedding.state)
    }

    private fun unusedEpisodeAnalysis() = object : EpisodeAnalysis {
        override fun submit(request: EpisodeAnalysisRequest) = error("unused")
        override fun findJob(userId: String, jobId: Int) = error("unused")
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
