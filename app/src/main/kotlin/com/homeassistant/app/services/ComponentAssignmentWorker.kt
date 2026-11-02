package com.homeassistant.app.services

import com.homeassistant.application.port.input.group.GroupMergeOutcome
import com.homeassistant.application.port.input.component.EpisodeAssignmentOutcome
import com.homeassistant.application.port.input.component.OrganizePendingEpisodes
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
 * Drains the Component-assignment outbox. A round runs only when [OrganizePendingEpisodes.isReady] reports
 * that the services assignment calls are available, so a missing service never uses up assignment attempts.
 */
internal class EpisodeComponentAssignmentWorker(
    private val organize: OrganizePendingEpisodes,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val intervalSeconds: Long = DEFAULT_INTERVAL_SECONDS,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "component-assignment-outbox").apply { isDaemon = true }
    },
) : ComponentAssignmentWorker {
    override fun start() {
        executor.execute(::enqueueUnassigned)
        executor.scheduleWithFixedDelay(::runRound, 0, intervalSeconds, TimeUnit.SECONDS)
    }

    internal fun enqueueUnassigned() {
        runCatching { organize.enqueueUnassigned() }
            .onSuccess { if (it > 0) log.info("Queued {} unassigned episodes for Component assignment", it) }
            .onFailure { log.warn("Queuing unassigned episodes failed", it) }
    }

    /** Contains every failure: an exception escaping a scheduled task would cancel later rounds. */
    internal fun runRound() {
        runCatching {
            if (!organize.isReady()) {
                log.debug("Component assignment skipped: assignment services are not ready")
                return
            }
            organize.execute(batchSize)
        }
            .onSuccess { result ->
                val failed = result.episodes.count { it.outcome is EpisodeAssignmentOutcome.Failed }
                if (result.episodes.isNotEmpty()) {
                    log.info("Component assignment processed={} failed={} groupMerges={}", result.episodes.size, failed,
                        result.groupMerges.count { it is GroupMergeOutcome.Merged })
                }
            }
            .onFailure { log.warn("Component assignment round failed", it) }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 20
        const val DEFAULT_INTERVAL_SECONDS = 30L
        val log = LoggerFactory.getLogger(EpisodeComponentAssignmentWorker::class.java)
    }
}
