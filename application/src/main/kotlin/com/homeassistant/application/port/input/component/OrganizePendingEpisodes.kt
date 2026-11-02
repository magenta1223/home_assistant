package com.homeassistant.application.port.input.component

import com.homeassistant.application.port.input.group.GroupMergeOutcome
import com.homeassistant.application.port.input.group.GroupOrganizationResult
import com.homeassistant.application.port.input.group.GroupRefreshOutcome

/** Extracts the Components of each queued Episode, then organizes Components into Groups. */
interface OrganizePendingEpisodes {
    /** True when the services assignment calls are available; a caller skips [execute] otherwise. */
    fun isReady(): Boolean

    /** Processes up to [limit] ready Component-assignment work items, then organizes Groups. */
    fun execute(limit: Int): EpisodeOrganizationResult

    /** Queues Episodes that were never extracted nor queued; returns how many were queued. */
    fun enqueueUnassigned(): Int
}

sealed interface EpisodeAssignmentOutcome {
    /** The Components extracted from the episode; empty when it held no new long-term knowledge. */
    data class Extracted(val componentIds: List<Int>) : EpisodeAssignmentOutcome
    data class Failed(val reason: String) : EpisodeAssignmentOutcome
}

data class EpisodeAssignmentResult(val episodeId: Int, val outcome: EpisodeAssignmentOutcome)

data class EpisodeOrganizationResult(
    val episodes: List<EpisodeAssignmentResult>,
    val groupRefreshes: List<GroupRefreshOutcome>,
    val groups: GroupOrganizationResult,
    val groupMerges: List<GroupMergeOutcome>,
)
