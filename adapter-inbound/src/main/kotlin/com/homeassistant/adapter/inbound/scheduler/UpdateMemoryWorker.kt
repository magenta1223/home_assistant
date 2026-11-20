package com.homeassistant.adapter.inbound.scheduler

import com.homeassistant.application.port.inbound.memory.MemoryUpdateResult
import com.homeassistant.application.port.inbound.memory.SourceMemorizationOutcome
import com.homeassistant.application.port.inbound.memory.SourceMemorizationResult
import com.homeassistant.application.port.inbound.memory.UpdateMemory
import com.homeassistant.infrastructure.runtime.AbstractRuntime
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Runs [UpdateMemory] every [intervalSeconds] and logs what each round changed.
 */
class UpdateMemoryWorker(
    private val updateMemory: UpdateMemory,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val intervalSeconds: Long = DEFAULT_INTERVAL_SECONDS,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "memory-update").apply { isDaemon = true }
    },
) : AbstractRuntime() {
    override fun onStart() {
        executor.scheduleWithFixedDelay(::runRound, 0, intervalSeconds, TimeUnit.SECONDS)
    }

    /** Contains every failure: an exception escaping a scheduled task would cancel later rounds. */
    internal fun runRound() {
        runCatching { runBlocking { updateMemory.execute(batchSize) } }
            .onSuccess(::log)
            .onFailure { log.warn("Memory update failed", it) }
    }

    override fun onStop() {
        executor.shutdownNow()
    }

    private fun log(update: MemoryUpdateResult) {
        when (update) {
            MemoryUpdateResult.Skipped -> log.debug("Memory update skipped: the memory services are not ready")
            is MemoryUpdateResult.Completed -> if (update.sources.isNotEmpty()) {
                log.info("Memory updated sources={} sourceFailures={}", update.sources.size, failures(update.sources))
            }
            is MemoryUpdateResult.Stopped -> log.warn(
                "Memory update stopped sources={} sourceFailures={} reason={}",
                update.sources.size, failures(update.sources), update.reason,
            )
        }
    }

    private fun failures(sources: List<SourceMemorizationResult>): Int =
        sources.count { it.outcome is SourceMemorizationOutcome.Failed }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 20
        const val DEFAULT_INTERVAL_SECONDS = 60L
        val log = LoggerFactory.getLogger(UpdateMemoryWorker::class.java)
    }
}
