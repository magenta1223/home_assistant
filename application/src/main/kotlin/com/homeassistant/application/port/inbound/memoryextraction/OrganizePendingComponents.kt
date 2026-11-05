package com.homeassistant.application.port.inbound.memoryextraction

import com.homeassistant.domain.group.GroupSplit

/** Organizes Components not yet placed in a Group.
 * TODO: RENAME. Pending은 구현에 의존하는 이름. 입출력이 명확하지 않음. 출력물이 뭔지 이름에서 드러나지 않음.
 * */
fun interface OrganizePendingComponents {
    /**
     * Places up to [limit] pending Components in Component id order and returns each Component's outcome.
     * Stops with [GroupOrganizationResult.RebuildRequired] when stored Group vectors must be rebuilt first.
     */
    fun execute(limit: Int): GroupOrganizationResult
}

sealed interface ComponentOrganizationOutcome {
    data class Organized(val groupIds: List<Int>, val split: GroupSplit) : ComponentOrganizationOutcome
    data object AlreadyOrganized : ComponentOrganizationOutcome
    data object ConflictExhausted : ComponentOrganizationOutcome
    data class Failed(val reason: String) : ComponentOrganizationOutcome
}

data class ComponentOrganizationResult(val componentId: Int, val outcome: ComponentOrganizationOutcome)

sealed interface GroupOrganizationResult {
    val components: List<ComponentOrganizationResult>
    data class Completed(override val components: List<ComponentOrganizationResult>) : GroupOrganizationResult
    data class RebuildRequired(override val components: List<ComponentOrganizationResult>, val reason: String) : GroupOrganizationResult
}

sealed interface GroupMergeOutcome {
    /** [absorbedGroupId]'s Components joined [survivorGroupId] and [absorbedGroupId] was deleted. */
    data class Merged(val survivorGroupId: Int, val absorbedGroupId: Int) : GroupMergeOutcome
    /** Concurrent changes kept merges from being written; the next round decides again. */
    data object ConflictExhausted : GroupMergeOutcome
}
