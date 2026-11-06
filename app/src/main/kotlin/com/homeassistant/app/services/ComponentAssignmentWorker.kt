package com.homeassistant.app.services

import com.homeassistant.application.port.inbound.memoryextraction.ComponentPlacementOutcome
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAssignmentOutcome
import com.homeassistant.application.port.inbound.memoryextraction.GroupMergeOutcome
import com.homeassistant.application.port.inbound.memoryextraction.MemoryHierarchyUpdate
import com.homeassistant.application.port.inbound.memoryextraction.UpdateMemoryHierarchy
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal interface ComponentAssignmentWorker : AutoCloseable {
    fun start()

    companion object {
        val NONE = object : ComponentAssignmentWorker {
            override fun start() = Unit
            override fun close() = Unit
        }
    }
}

/**
 * Runs [UpdateMemoryHierarchy] every [intervalSeconds] and logs what each round changed.
 * TODO: To inbound-adapter
 */
internal class EpisodeComponentAssignmentWorker(
    private val updateHierarchy: UpdateMemoryHierarchy,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val intervalSeconds: Long = DEFAULT_INTERVAL_SECONDS,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "component-assignment-outbox").apply { isDaemon = true }
    },
) : ComponentAssignmentWorker {
    override fun start() {
        executor.scheduleWithFixedDelay(::runRound, 0, intervalSeconds, TimeUnit.SECONDS)
    }

    /** Contains every failure: an exception escaping a scheduled task would cancel later rounds. */
    internal fun runRound() {
        runCatching { updateHierarchy.execute(batchSize) }
            .onSuccess(::log)
            .onFailure { log.warn("Memory hierarchy update failed", it) }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private fun log(update: MemoryHierarchyUpdate) {
        when (update) {
            MemoryHierarchyUpdate.Skipped -> log.debug("Memory hierarchy update skipped: assignment services are not ready")
            is MemoryHierarchyUpdate.Completed -> if (update.episodes.isNotEmpty() || update.components.isNotEmpty()) {
                log.info(
                    "Memory hierarchy updated episodes={} episodeFailures={} components={} componentFailures={} groupMerges={}",
                    update.episodes.size, update.episodes.count { it.outcome is EpisodeAssignmentOutcome.Failed },
                    update.components.size, update.components.count { it.outcome is ComponentPlacementOutcome.Failed },
                    update.merges.count { it is GroupMergeOutcome.Merged },
                )
            }
            is MemoryHierarchyUpdate.RebuildRequired -> log.warn("Memory hierarchy update stopped: {}", update.reason)
        }
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 20
        const val DEFAULT_INTERVAL_SECONDS = 30L
        val log = LoggerFactory.getLogger(EpisodeComponentAssignmentWorker::class.java)
    }
}
