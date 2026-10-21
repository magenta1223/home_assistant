package com.homeassistant.application.port.input.topic

import com.homeassistant.application.port.input.group.GroupOrganizationResult
import com.homeassistant.application.port.input.group.GroupRefreshOutcome

/** Assigns each queued Memory to its Topics, then organizes Topics into Groups. */
interface OrganizePendingMemories {
    /** True when the services assignment calls are available; a caller skips [execute] otherwise. */
    fun isReady(): Boolean

    /** Processes up to [limit] ready Topic-assignment work items, then organizes Groups. */
    fun execute(limit: Int): MemoryOrganizationResult

    /** Queues Memories that belong to no Topic and were never queued; returns how many were queued. */
    fun enqueueUnassigned(): Int
}

sealed interface MemoryAssignmentOutcome {
    /** The memory joined [topicIds], nearest first, or the one Topic it [created] when no Topic was near enough. */
    data class Assigned(val topicIds: List<Int>, val created: Boolean) : MemoryAssignmentOutcome {
        init { require(topicIds.isNotEmpty() && (!created || topicIds.size == 1)) }
    }
    data class Failed(val reason: String) : MemoryAssignmentOutcome
}

data class MemoryAssignmentResult(val memoryId: Int, val outcome: MemoryAssignmentOutcome)

data class MemoryOrganizationResult(
    val memories: List<MemoryAssignmentResult>,
    val groupRefreshes: List<GroupRefreshOutcome>,
    val groups: GroupOrganizationResult,
)
