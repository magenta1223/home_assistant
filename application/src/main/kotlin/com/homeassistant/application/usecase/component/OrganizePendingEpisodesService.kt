package com.homeassistant.application.usecase.component

import com.homeassistant.application.port.input.group.GroupOrganizationResult
import com.homeassistant.application.port.input.group.OrganizePendingComponents
import com.homeassistant.application.port.input.component.*
import com.homeassistant.application.port.output.component.ComponentAssignmentOutbox
import com.homeassistant.application.port.output.component.ComponentAssignmentTask
import com.homeassistant.application.usecase.group.MergeSmallGroups
import java.time.Clock
import java.util.concurrent.CancellationException

/** Episode → Component extraction from the durable outbox, then Component → Group organization and Group merge (xMemory component → group). */
class OrganizePendingEpisodesService(
    private val outbox: ComponentAssignmentOutbox,
    private val extractor: ExtractComponents,
    private val groups: OrganizePendingComponents,
    private val groupMerge: MergeSmallGroups,
    private val clock: Clock = Clock.systemUTC(),
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
    private val processingLeaseMillis: Long = DEFAULT_PROCESSING_LEASE_MILLIS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) : OrganizePendingEpisodes {
    init {
        require(retryDelayMillis >= 0) { "retryDelayMillis must not be negative" }
        require(processingLeaseMillis > 0) { "processingLeaseMillis must be positive" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    override fun isReady(): Boolean = extractor.isReady()

    override fun execute(limit: Int): EpisodeOrganizationResult {
        require(limit > 0) { "limit must be positive" }
        val now = clock.millis()
        val tasks = outbox.claimReady(limit, now, now - retryDelayMillis, now - processingLeaseMillis, maxAttempts)
        val episodes = tasks.map { EpisodeAssignmentResult(it.episode.id, extract(it)) }
        val organized = groups.execute(limit)
        // Merging only follows a round that changed Components and placed every one of them.
        val merged = if (episodes.isNotEmpty() && organized is GroupOrganizationResult.Completed) groupMerge.execute() else emptyList()
        return EpisodeOrganizationResult(episodes, organized, merged)
    }

    override fun enqueueUnassigned(): Int = outbox.enqueueUnassigned(clock.millis())

    private fun extract(task: ComponentAssignmentTask): EpisodeAssignmentOutcome = try {
        extractor.execute(task.episode).also { outbox.markCompleted(task.outboxId, task.attempt, clock.millis()) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        val reason = failure.message ?: failure.javaClass.simpleName
        outbox.markFailed(task.outboxId, task.attempt, reason, clock.millis())
        EpisodeAssignmentOutcome.Failed(reason)
    }

    companion object {
        const val DEFAULT_RETRY_DELAY_MILLIS = 60_000L
        const val DEFAULT_PROCESSING_LEASE_MILLIS = 10 * 60_000L
        const val DEFAULT_MAX_ATTEMPTS = 5
    }
}
