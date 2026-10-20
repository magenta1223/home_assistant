package com.homeassistant.application.usecase.topic

import com.homeassistant.application.port.input.group.GroupOrganizationResult
import com.homeassistant.application.port.input.group.OrganizePendingTopics
import com.homeassistant.application.port.input.topic.*
import com.homeassistant.application.port.output.group.GroupRebuildRequired
import com.homeassistant.application.port.output.topic.TopicAssignmentOutbox
import com.homeassistant.application.port.output.topic.TopicAssignmentTask
import com.homeassistant.application.usecase.group.RefreshGroupedTopicEmbeddings
import java.time.Clock
import java.util.concurrent.CancellationException

/** Memory → Topic assignment from the durable outbox, then Topic → Group organization (xMemory component → group). */
class OrganizePendingMemoriesService(
    private val outbox: TopicAssignmentOutbox,
    private val assigner: AssignMemoryToTopics,
    private val groupRefresh: RefreshGroupedTopicEmbeddings,
    private val groups: OrganizePendingTopics,
    private val clock: Clock = Clock.systemUTC(),
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
    private val processingLeaseMillis: Long = DEFAULT_PROCESSING_LEASE_MILLIS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) : OrganizePendingMemories {
    init {
        require(retryDelayMillis >= 0) { "retryDelayMillis must not be negative" }
        require(processingLeaseMillis > 0) { "processingLeaseMillis must be positive" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    override fun isReady(): Boolean = assigner.isReady()

    override fun execute(limit: Int): MemoryOrganizationResult {
        require(limit > 0) { "limit must be positive" }
        val now = clock.millis()
        val tasks = outbox.claimReady(limit, now, now - retryDelayMillis, now - processingLeaseMillis, maxAttempts)
        val memories = tasks.map { MemoryAssignmentResult(it.memory.id, assign(it)) }
        // Joined Topics have moved centroids, so their Group vectors are refreshed before new Topics are placed.
        val refreshed = try {
            groupRefresh.execute()
        } catch (rebuild: GroupRebuildRequired) {
            return MemoryOrganizationResult(memories, emptyList(),
                GroupOrganizationResult.RebuildRequired(emptyList(), rebuild.message.orEmpty()))
        }
        return MemoryOrganizationResult(memories, refreshed, groups.execute(limit))
    }

    override fun enqueueUnassigned(): Int = outbox.enqueueUnassigned(clock.millis())

    private fun assign(task: TopicAssignmentTask): MemoryAssignmentOutcome = try {
        assigner.execute(task.memory).also { outbox.markCompleted(task.outboxId, task.attempt, clock.millis()) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        val reason = failure.message ?: failure.javaClass.simpleName
        outbox.markFailed(task.outboxId, task.attempt, reason, clock.millis())
        MemoryAssignmentOutcome.Failed(reason)
    }

    companion object {
        const val DEFAULT_RETRY_DELAY_MILLIS = 60_000L
        const val DEFAULT_PROCESSING_LEASE_MILLIS = 10 * 60_000L
        const val DEFAULT_MAX_ATTEMPTS = 5
    }
}
