package com.homeassistant.app.services

import com.homeassistant.application.usecase.memory.write.MemoryIndexingOutboxProcessor
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal interface IndexingWorker : AutoCloseable {
    fun start()

    companion object {
        val NONE = object : IndexingWorker {
            override fun start() = Unit
            override fun close() = Unit
        }
    }
}

/** Periodically drains durable semantic-index work after the embedding runtime is ready. */
internal class MemoryIndexingWorker(
    private val processor: MemoryIndexingOutboxProcessor,
    private val intervalSeconds: Long = 30,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "memory-indexing-outbox").apply { isDaemon = true }
    },
) : IndexingWorker {
    override fun start() {
        executor.scheduleWithFixedDelay(::processSafely, 0, intervalSeconds, TimeUnit.SECONDS)
    }

    private fun processSafely() {
        runCatching { processor.processAvailable() }
            .onFailure { error -> log.warn("Memory indexing outbox processing failed", error) }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private companion object {
        val log = LoggerFactory.getLogger(MemoryIndexingWorker::class.java)
    }
}
