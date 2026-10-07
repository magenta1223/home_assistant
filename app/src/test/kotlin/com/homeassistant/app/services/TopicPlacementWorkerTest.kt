package com.homeassistant.app.services

import com.homeassistant.application.port.input.topic.placement.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TopicPlacementWorkerTest {
    @Test
    fun `worker retries failed placement without a new import and closes active work`() = runBlocking {
        val calls = AtomicInteger()
        val retryStarted = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val worker = TopicPlacementWorker(TopicPlacement {
            if (calls.incrementAndGet() == 1) {
                TopicPlacementRunResult.Failed(TopicPlacementFailureStage.PROPOSE)
            } else {
                retryStarted.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }, intervalMillis = 10)
        try {
            worker.start()
            withTimeout(5_000) { retryStarted.await() }
        } finally {
            worker.close()
        }
        withTimeout(5_000) { cancelled.await() }
        assertEquals(2, calls.get())
    }
}
