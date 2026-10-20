package com.homeassistant.app.services

import com.homeassistant.application.port.input.topic.MemoryAssignmentOutcome
import com.homeassistant.application.port.input.topic.OrganizePendingMemories
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal interface TopicAssignmentWorker : AutoCloseable {
    fun start()

    companion object {
        val NONE = object : TopicAssignmentWorker {
            override fun start() = Unit
            override fun close() = Unit
        }
    }
}

/**
 * Drains the Topic-assignment outbox. A round runs only when [OrganizePendingMemories.isReady] reports
 * that the services assignment calls are available, so a missing service never uses up assignment attempts.
 */
internal class MemoryTopicAssignmentWorker(
    private val organize: OrganizePendingMemories,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val intervalSeconds: Long = DEFAULT_INTERVAL_SECONDS,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "topic-assignment-outbox").apply { isDaemon = true }
    },
) : TopicAssignmentWorker {
    override fun start() {
        executor.execute(::enqueueUnassigned)
        executor.scheduleWithFixedDelay(::runRound, 0, intervalSeconds, TimeUnit.SECONDS)
    }

    internal fun enqueueUnassigned() {
        runCatching { organize.enqueueUnassigned() }
            .onSuccess { if (it > 0) log.info("Queued {} unassigned memories for Topic assignment", it) }
            .onFailure { log.warn("Queuing unassigned memories failed", it) }
    }

    /** Contains every failure: an exception escaping a scheduled task would cancel later rounds. */
    internal fun runRound() {
        runCatching {
            if (!organize.isReady()) {
                log.debug("Topic assignment skipped: assignment services are not ready")
                return
            }
            organize.execute(batchSize)
        }
            .onSuccess { result ->
                val failed = result.memories.count { it.outcome is MemoryAssignmentOutcome.Failed }
                if (result.memories.isNotEmpty()) {
                    log.info("Topic assignment processed={} failed={}", result.memories.size, failed)
                }
            }
            .onFailure { log.warn("Topic assignment round failed", it) }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 20
        const val DEFAULT_INTERVAL_SECONDS = 30L
        val log = LoggerFactory.getLogger(MemoryTopicAssignmentWorker::class.java)
    }
}
