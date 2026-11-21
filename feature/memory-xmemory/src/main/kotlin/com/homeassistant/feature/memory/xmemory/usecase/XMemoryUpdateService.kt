package com.homeassistant.feature.memory.xmemory.usecase

import com.homeassistant.application.port.inbound.memory.MemoryUpdateResult
import com.homeassistant.application.port.inbound.memory.SourceMemorizationOutcome
import com.homeassistant.application.port.inbound.memory.SourceMemorizationResult
import com.homeassistant.application.port.inbound.memory.UpdateMemory
import com.homeassistant.feature.memory.xmemory.port.outbound.component.ComponentAssignmentOutbox
import com.homeassistant.feature.memory.xmemory.port.outbound.component.ComponentAssignmentTask
import com.homeassistant.feature.memory.xmemory.port.outbound.component.ComponentRepository
import com.homeassistant.feature.memory.xmemory.port.outbound.episode.EpisodeExtractionClaim
import com.homeassistant.feature.memory.xmemory.port.outbound.episode.EpisodeExtractionQueue
import com.homeassistant.feature.memory.xmemory.port.outbound.group.GroupRebuildRequired
import com.homeassistant.feature.memory.xmemory.usecase.component.AssignEpisodeToComponents
import com.homeassistant.feature.memory.xmemory.usecase.episode.ExtractEpisodes
import com.homeassistant.feature.memory.xmemory.usecase.group.MergeSmallGroups
import com.homeassistant.feature.memory.xmemory.usecase.group.OrganizeComponentIntoGroups
import com.homeassistant.domain.memory.xmemory.component.Component
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.CancellationException

/**
 * Updates memory the xMemory way: relays the [EpisodeExtractionQueue] to [ExtractEpisodes], relays the
 * Component-assignment outbox to [AssignEpisodeToComponents], places the unplaced Components with
 * [OrganizeComponentIntoGroups], and then runs [MergeSmallGroups]. One failed source, Episode or Component never stops
 * the others: a failed source or Episode is recorded on its queue and logged, and a failed Component is logged and
 * stays unplaced for the next round. A [GroupRebuildRequired] stops placement and skips merging, since merge scores
 * from outdated Group vectors would be wrong.
 */
class XMemoryUpdateService(
    private val extractionQueue: EpisodeExtractionQueue,
    private val extractEpisodes: ExtractEpisodes,
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

    override suspend fun execute(batchSize: Int): MemoryUpdateResult {
        require(batchSize > 0) { "batchSize must be positive" }
        if (!assignEpisode.isReady()) return MemoryUpdateResult.Skipped
        val roundStartedAt = clock.millis()
        val sources = extractWaitingSources(roundStartedAt)
        assignWaitingEpisodes(batchSize, roundStartedAt)
        return when (val placement = placeUnplacedComponents(batchSize)) {
            Placement.Completed -> {
                mergeSmallGroups.execute()
                MemoryUpdateResult.Completed(sources)
            }
            is Placement.Stopped -> MemoryUpdateResult.Stopped(sources, placement.reason)
        }
    }

    /**
     * Extracts Episodes from every source whose records were ready when the round started at [roundStartedAt], one
     * claimed source at a time, so a source that fails in this round waits for the next one.
     */
    private suspend fun extractWaitingSources(roundStartedAt: Long): List<SourceMemorizationResult> = buildList {
        while (true) {
            val claim = claimSource(roundStartedAt) ?: break
            add(SourceMemorizationResult(claim.document.source, extract(claim)))
        }
    }

    /** The waiting records of the next source that was ready when the round started at [roundStartedAt]. */
    private fun claimSource(roundStartedAt: Long): EpisodeExtractionClaim? =
        extractionQueue.claimNext(
            now = clock.millis(),
            retryBefore = roundStartedAt - retryDelayMillis,
            staleProcessingBefore = roundStartedAt - processingLeaseMillis,
            maxAttempts = maxAttempts,
        )

    /** Extracts [claim]'s Episodes, memorizing every claimed record; a failure is recorded on the claim. */
    private suspend fun extract(claim: EpisodeExtractionClaim): SourceMemorizationOutcome {
        return runCatching { extractEpisodes.extract(claim) }
            .fold(
                onSuccess = { SourceMemorizationOutcome.Memorized(claim.document.records.size) },
                onFailure = { failure ->
                    if (failure is CancellationException) throw failure
                    extractionQueue.markFailed(claim.claimId, failure.reason(), clock.millis())
                    SourceMemorizationOutcome.Failed(failure.reason())
                },
            )
    }

    /** Places every unplaced Component, stopping at the first [GroupRebuildRequired]. */
    private fun placeUnplacedComponents(batchSize: Int): Placement =
        try {
            unplacedComponents(batchSize).forEach(::place)
            Placement.Completed
        } catch (rebuild: GroupRebuildRequired) {
            Placement.Stopped(rebuild.message.orEmpty())
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
    private fun assignWaitingEpisodes(batchSize: Int, roundStartedAt: Long) {
        outbox.enqueueUnassigned(roundStartedAt)
        generateSequence { claimReady(batchSize, roundStartedAt) }
            .takeWhile { it.isNotEmpty() }
            .flatten()
            .forEach(::assign)
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

    /** Assigns [task]'s Episode and records the outcome in the outbox; a failure is also logged. */
    private fun assign(task: ComponentAssignmentTask) {
        runCatching { assignEpisode.assign(task.episode) }
            .fold(
                onSuccess = { outbox.markCompleted(task.outboxId, task.attempt, clock.millis()) },
                onFailure = { failure ->
                    if (failure is CancellationException) throw failure
                    log.warn("Episode {} was not assigned to Components: {}", task.episode.id, failure.reason())
                    outbox.markFailed(task.outboxId, task.attempt, failure.reason(), clock.millis())
                },
            )
    }

    /**
     * Places [component]; a failure is logged and leaves it unplaced for the next round, and a [GroupRebuildRequired]
     * is rethrown because it affects every Component.
     */
    private fun place(component: Component) {
        runCatching { organizeComponent.organize(component) }
            .onFailure { failure ->
                if (failure is CancellationException || failure is GroupRebuildRequired) throw failure
                log.warn("Component {} was not placed in Groups: {}", component.id, failure.reason())
            }
    }

    private fun Throwable.reason(): String = message ?: javaClass.simpleName

    /** How placement ended: every Component tried, or stopped because Group vectors must be rebuilt first. */
    private sealed interface Placement {
        data object Completed : Placement
        data class Stopped(val reason: String) : Placement
    }

    companion object {
        private const val FIRST_COMPONENT_CURSOR = 0
        private val log = LoggerFactory.getLogger(XMemoryUpdateService::class.java)
        const val DEFAULT_RETRY_DELAY_MILLIS = 60_000L
        const val DEFAULT_PROCESSING_LEASE_MILLIS = 10 * 60_000L
        const val DEFAULT_MAX_ATTEMPTS = 5
    }
}
