package com.homeassistant.application.port.input.group

import com.homeassistant.domain.group.GroupSplit

/** Organizes Topics not yet placed in a Group. */
fun interface OrganizePendingTopics {
    /**
     * Places up to [limit] pending Topics in Topic id order and returns each Topic's outcome.
     * Stops with [GroupOrganizationResult.RebuildRequired] when stored Group vectors must be rebuilt first.
     */
    fun execute(limit: Int): GroupOrganizationResult
}

sealed interface TopicOrganizationOutcome {
    data class Organized(val groupIds: List<Int>, val split: GroupSplit) : TopicOrganizationOutcome
    data object AlreadyOrganized : TopicOrganizationOutcome
    data object ConflictExhausted : TopicOrganizationOutcome
    data class Failed(val reason: String) : TopicOrganizationOutcome
}

data class TopicOrganizationResult(val topicId: Int, val outcome: TopicOrganizationOutcome)

sealed interface GroupOrganizationResult {
    val topics: List<TopicOrganizationResult>
    data class Completed(override val topics: List<TopicOrganizationResult>) : GroupOrganizationResult
    data class RebuildRequired(override val topics: List<TopicOrganizationResult>, val reason: String) : GroupOrganizationResult
}

sealed interface GroupRefreshOutcome {
    val groupId: Int
    data class Refreshed(override val groupId: Int) : GroupRefreshOutcome
    data class ConflictExhausted(override val groupId: Int) : GroupRefreshOutcome
}

sealed interface GroupMergeOutcome {
    /** [absorbedGroupId]'s Topics joined [survivorGroupId] and [absorbedGroupId] was deleted. */
    data class Merged(val survivorGroupId: Int, val absorbedGroupId: Int) : GroupMergeOutcome
    /** Concurrent changes kept merges from being written; the next round decides again. */
    data object ConflictExhausted : GroupMergeOutcome
}
