package com.homeassistant.app.services

import com.homeassistant.application.port.input.topic.placement.TopicPlacement
import com.homeassistant.application.port.input.topic.placement.TopicPlacementRunResult
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

internal interface PlacementWorker : AutoCloseable {
    fun start()

    companion object {
        val NONE = object : PlacementWorker {
            override fun start() = Unit
            override fun close() = Unit
        }
    }
}

/** Resumes durable unplaced memories, including records committed before a previous process stopped. */
internal class TopicPlacementWorker(
    private val placement: TopicPlacement,
    private val intervalMillis: Long = 30_000,
) : PlacementWorker {
    init { require(intervalMillis > 0) }

    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val job = scope.launch(start = CoroutineStart.LAZY) {
        while (isActive) {
            processSafely()
            delay(intervalMillis)
        }
    }

    override fun start() { job.start() }
    override fun close() { scope.cancel() }

    private suspend fun processSafely() {
        try {
            when (val result = placement.processAvailable()) {
                is TopicPlacementRunResult.Placed -> log.info("Topic placement completed memoryCount={}", result.memoryCount)
                is TopicPlacementRunResult.Failed -> log.warn("Topic placement deferred stage={}", result.stage)
                else -> Unit
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.warn("Topic placement worker failed category={}", error.javaClass.simpleName)
        }
    }
}
