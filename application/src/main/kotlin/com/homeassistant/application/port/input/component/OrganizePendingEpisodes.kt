package com.homeassistant.application.port.input.component

import com.homeassistant.application.port.input.group.GroupMergeOutcome
import com.homeassistant.application.port.input.group.GroupOrganizationResult
import com.homeassistant.application.port.input.group.GroupRefreshOutcome

/** Assigns each queued Episode to its Components, then organizes Components into Groups. */
interface OrganizePendingEpisodes {
    /** True when the services assignment calls are available; a caller skips [execute] otherwise. */
    fun isReady(): Boolean

    /** Processes up to [limit] ready Component-assignment work items, then organizes Groups. */
    fun execute(limit: Int): EpisodeOrganizationResult

    /** Queues Episodes that belong to no Component and were never queued; returns how many were queued. */
    fun enqueueUnassigned(): Int
}

sealed interface EpisodeAssignmentOutcome {
    /** The episode joined [componentIds], nearest first, or the one Component it [created] when no Component was near enough. */
    data class Assigned(val componentIds: List<Int>, val created: Boolean) : EpisodeAssignmentOutcome {
        init { require(componentIds.isNotEmpty() && (!created || componentIds.size == 1)) }
    }
    data class Failed(val reason: String) : EpisodeAssignmentOutcome
}

data class EpisodeAssignmentResult(val episodeId: Int, val outcome: EpisodeAssignmentOutcome)

data class EpisodeOrganizationResult(
    val episodes: List<EpisodeAssignmentResult>,
    val groupRefreshes: List<GroupRefreshOutcome>,
    val groups: GroupOrganizationResult,
    val groupMerges: List<GroupMergeOutcome>,
)
