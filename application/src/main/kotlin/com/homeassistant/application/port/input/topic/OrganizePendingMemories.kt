package com.homeassistant.application.port.input.topic

import com.homeassistant.application.port.input.group.GroupOrganizationResult
import com.homeassistant.application.port.input.group.GroupRefreshOutcome

/** Assigns unassigned Memories to one or more Topics, then organizes Topics into Groups. */
fun interface OrganizePendingMemories {
    fun execute(limit: Int): MemoryOrganizationResult
}

sealed interface MemoryAssignmentOutcome {
    /** [joinedTopicIds] gained the memory and a revised description; [createdTopicIds] start with it. */
    data class Assigned(val joinedTopicIds: List<Int>, val createdTopicIds: List<Int>) : MemoryAssignmentOutcome
    data class Failed(val reason: String) : MemoryAssignmentOutcome
}

data class MemoryAssignmentResult(val memoryId: Int, val outcome: MemoryAssignmentOutcome)

data class MemoryOrganizationResult(
    val memories: List<MemoryAssignmentResult>,
    val groupRefreshes: List<GroupRefreshOutcome>,
    val groups: GroupOrganizationResult,
)
