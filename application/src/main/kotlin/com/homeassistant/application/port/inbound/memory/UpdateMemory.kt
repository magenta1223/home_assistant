package com.homeassistant.application.port.inbound.memory

import com.homeassistant.domain.memory.xmemory.group.GroupSplit
import com.homeassistant.domain.source.SourceDescriptor

/** Grows the Episode → Component → Group hierarchy from the source records stored since the last update. */
fun interface UpdateMemory {
    /**
     * Extracts Episodes from the stored records of every source waiting for extraction, assigns every waiting Episode
     * to Components, places every unplaced Component in Groups, and then merges small Groups, reading [batchSize]
     * Episodes and Components at a time. Each source, Episode and Component is tried at most once per call; one that
     * fails waits for the next call. Nothing runs when the assignment services are unavailable, so no attempt is used up.
     */
    suspend fun execute(batchSize: Int): MemoryUpdateResult
}

sealed interface MemoryUpdateResult {
    /** The assignment services were unavailable; nothing was read or written. */
    data object Skipped : MemoryUpdateResult

    /** Every step ran. */
    data class Completed(
        val sources: List<EpisodeExtractionResult>,
        val episodes: List<EpisodeAssignmentResult>,
        val components: List<ComponentPlacementResult>,
        val merges: List<GroupMergeOutcome>,
    ) : MemoryUpdateResult

    /** Placement stopped because stored Group vectors must be rebuilt first, so no Group was merged. */
    data class RebuildRequired(
        val sources: List<EpisodeExtractionResult>,
        val episodes: List<EpisodeAssignmentResult>,
        val components: List<ComponentPlacementResult>,
        val reason: String,
    ) : MemoryUpdateResult
}

/** How Episode extraction from the waiting records of [source] ended. */
data class EpisodeExtractionResult(val source: SourceDescriptor, val outcome: EpisodeExtractionOutcome)

sealed interface EpisodeExtractionOutcome {
    /** [episodeCount] Episodes were stored; zero when the records held none. */
    data class Extracted(val episodeCount: Int) : EpisodeExtractionOutcome
    data class Failed(val reason: String) : EpisodeExtractionOutcome
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
