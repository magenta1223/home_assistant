package com.homeassistant.application.port.inbound.memoryextraction

import com.homeassistant.domain.group.GroupSplit

/** Grows the Episode → Component → Group hierarchy from the Episodes saved since the last update. */
fun interface UpdateMemoryHierarchy {
    /**
     * Assigns up to [batchSize] waiting Episodes to Components, places up to [batchSize] unplaced Components in Groups,
     * and then merges small Groups. Nothing runs when the assignment services are unavailable, so no attempt is used up.
     */
    fun execute(batchSize: Int): MemoryHierarchyUpdate
}

sealed interface MemoryHierarchyUpdate {
    /** The assignment services were unavailable; nothing was read or written. */
    data object Skipped : MemoryHierarchyUpdate

    /** Every step ran. */
    data class Completed(
        val episodes: List<EpisodeAssignmentResult>,
        val components: List<ComponentPlacementResult>,
        val merges: List<GroupMergeOutcome>,
    ) : MemoryHierarchyUpdate

    /** Placement stopped because stored Group vectors must be rebuilt first, so no Group was merged. */
    data class RebuildRequired(
        val episodes: List<EpisodeAssignmentResult>,
        val components: List<ComponentPlacementResult>,
        val reason: String,
    ) : MemoryHierarchyUpdate
}

data class EpisodeAssignmentResult(val episodeId: Int, val outcome: EpisodeAssignmentOutcome)

sealed interface EpisodeAssignmentOutcome {
    /** The Components created for the Episode; empty when it held no new long-term knowledge. */
    data class Assigned(val componentIds: List<Int>) : EpisodeAssignmentOutcome
    data class Failed(val reason: String) : EpisodeAssignmentOutcome
}

data class ComponentPlacementResult(val componentId: Int, val outcome: ComponentPlacementOutcome)

sealed interface ComponentPlacementOutcome {
    data class Placed(val organization: ComponentOrganizationOutcome) : ComponentPlacementOutcome
    data class Failed(val reason: String) : ComponentPlacementOutcome
}

sealed interface ComponentOrganizationOutcome {
    data class Organized(val groupIds: List<Int>, val split: GroupSplit) : ComponentOrganizationOutcome
    data object AlreadyOrganized : ComponentOrganizationOutcome
    data object ConflictExhausted : ComponentOrganizationOutcome
}

sealed interface GroupMergeOutcome {
    /** [absorbedGroupId]'s Components joined [survivorGroupId] and [absorbedGroupId] was deleted. */
    data class Merged(val survivorGroupId: Int, val absorbedGroupId: Int) : GroupMergeOutcome
    /** Concurrent changes kept merges from being written; the next round decides again. */
    data object ConflictExhausted : GroupMergeOutcome
}
