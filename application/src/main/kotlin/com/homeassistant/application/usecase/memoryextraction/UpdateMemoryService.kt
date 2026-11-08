package com.homeassistant.application.usecase.memoryextraction

import com.homeassistant.application.port.inbound.memoryextraction.ComponentPlacementOutcome
import com.homeassistant.application.port.inbound.memoryextraction.ComponentPlacementResult
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAssignmentOutcome
import com.homeassistant.application.port.inbound.memoryextraction.EpisodeAssignmentResult
import com.homeassistant.application.port.inbound.memoryextraction.MemoryUpdateResult
import com.homeassistant.application.port.inbound.memoryextraction.UpdateMemory
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentAssignmentOutbox
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentAssignmentTask
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentRepository
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupRebuildRequired
import com.homeassistant.application.usecase.memoryextraction.component.AssignEpisodeToComponents
import com.homeassistant.application.usecase.memoryextraction.group.MergeSmallGroups
import com.homeassistant.application.usecase.memoryextraction.group.OrganizeComponentIntoGroups
import com.homeassistant.domain.component.Component
import java.time.Clock
import java.util.concurrent.CancellationException

/**
 * Relays the Component-assignment outbox to [AssignEpisodeToComponents], places the unplaced Components with
 * [OrganizeComponentIntoGroups], and then runs [MergeSmallGroups]. One failed Episode or Component never stops the others;
 * a [GroupRebuildRequired] stops placement and skips merging, since merge scores from outdated Group vectors would be wrong.
 */
class UpdateMemoryService(
    private val outbox: ComponentAssignmentOutbox,
    private val components: ComponentRepository,
    private val assignEpisode: AssignEpisodeToComponents,
    private val organizeComponent: OrganizeComponentIntoGroups,
    private val mergeSmallGroups: MergeSmallGroups,
    private val clock: Clock = Clock.systemUTC(),
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
    private val processingLeaseMillis: Long = DEFAULT_PROCESSING_LEASE_MILLIS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) : UpdateMemory {
    init {
        require(retryDelayMillis >= 0) { "retryDelayMillis must not be negative" }
        require(processingLeaseMillis > 0) { "processingLeaseMillis must be positive" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    override fun execute(batchSize: Int): MemoryUpdateResult {
        require(batchSize > 0) { "batchSize must be positive" }
        if (!assignEpisode.isReady()) return MemoryUpdateResult.Skipped
        val episodes = assignWaitingEpisodes(batchSize, roundStartedAt = clock.millis())
        return when (val placement = placeUnplacedComponents(batchSize)) {
            is Placement.Completed -> MemoryUpdateResult.Completed(episodes, placement.components, mergeSmallGroups.execute())
            is Placement.Stopped -> MemoryUpdateResult.RebuildRequired(episodes, placement.components, placement.reason)
        }
    }

    /** Places every unplaced Component, stopping at the first [GroupRebuildRequired]. */
    private fun placeUnplacedComponents(batchSize: Int): Placement {
        val placed = mutableListOf<ComponentPlacementResult>()
        return try {
            unplacedComponents(batchSize).mapTo(placed) { ComponentPlacementResult(it.id, place(it)) }
            Placement.Completed(placed)
        } catch (rebuild: GroupRebuildRequired) {
            Placement.Stopped(placed, rebuild.message.orEmpty())
        }
    }

    /**
     * Every unplaced Component in id order, read [batchSize] at a time after the last one read, so a Component that
     * fails placement is not read again in this round.
     */
    private fun unplacedComponents(batchSize: Int): Sequence<Component> =
        generateSequence(components.findUngroupedAfter(FIRST_COMPONENT_CURSOR, batchSize)) { previous ->
            components.findUngroupedAfter(previous.last().id, batchSize)
        }.takeWhile { it.isNotEmpty() }.flatten()

    /**
     * Queues Episodes saved before the outbox existed, then assigns claimed Episodes [batchSize] at a time until none
     * is ready. Readiness is judged at [roundStartedAt], so an Episode that fails in this round waits for the next one.
     */
    private fun assignWaitingEpisodes(batchSize: Int, roundStartedAt: Long): List<EpisodeAssignmentResult> {
        outbox.enqueueUnassigned(roundStartedAt)
        return generateSequence { claimReady(batchSize, roundStartedAt) }
            .takeWhile { it.isNotEmpty() }
            .flatten()
            .map { EpisodeAssignmentResult(it.episode.id, assign(it)) }
            .toList()
    }

    /** Up to [batchSize] Episodes that were ready when the round started at [roundStartedAt]. */
    private fun claimReady(batchSize: Int, roundStartedAt: Long): List<ComponentAssignmentTask> =
        outbox.claimReady(
            limit = batchSize,
            now = clock.millis(),
            retryBefore = roundStartedAt - retryDelayMillis,
            staleProcessingBefore = roundStartedAt - processingLeaseMillis,
            maxAttempts = maxAttempts,
        )

    /** Assigns [task]'s Episode and records the outcome in the outbox. */
    private fun assign(task: ComponentAssignmentTask): EpisodeAssignmentOutcome {
        return runCatching { assignEpisode.assign(task.episode) }
            .fold(
                onSuccess = { componentIds ->
                    outbox.markCompleted(task.outboxId, task.attempt, clock.millis())
                    EpisodeAssignmentOutcome.Assigned(componentIds)
                },
                onFailure = { failure ->
                    if (failure is CancellationException) throw failure
                    val reason = failure.message ?: failure.javaClass.simpleName
                    outbox.markFailed(task.outboxId, task.attempt, reason, clock.millis())
                    EpisodeAssignmentOutcome.Failed(reason)
                },
            )
    }

    /** Places [component]; a [GroupRebuildRequired] is rethrown because it affects every Component. */
    private fun place(component: Component): ComponentPlacementOutcome {
        return runCatching { organizeComponent.organize(component) }
            .fold(
                onSuccess = { organization -> ComponentPlacementOutcome.Placed(organization) },
                onFailure = { failure ->
                    if (failure is CancellationException || failure is GroupRebuildRequired) throw failure
                    ComponentPlacementOutcome.Failed(failure.message ?: failure.javaClass.simpleName)
                },
            )
    }

    /** How a placement batch ended: every Component tried, or stopped because Group vectors must be rebuilt first. */
    private sealed interface Placement {
        val components: List<ComponentPlacementResult>
        data class Completed(override val components: List<ComponentPlacementResult>) : Placement
        data class Stopped(override val components: List<ComponentPlacementResult>, val reason: String) : Placement
    }

    companion object {
        private const val FIRST_COMPONENT_CURSOR = 0
        const val DEFAULT_RETRY_DELAY_MILLIS = 60_000L
        const val DEFAULT_PROCESSING_LEASE_MILLIS = 10 * 60_000L
        const val DEFAULT_MAX_ATTEMPTS = 5
    }
}
