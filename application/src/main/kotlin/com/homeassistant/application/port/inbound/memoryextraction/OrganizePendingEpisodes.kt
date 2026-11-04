package com.homeassistant.application.port.inbound.memoryextraction

/** Extracts the Components of each queued Episode, then organizes Components into Groups.
 * TODO: RENAME. Pending은 구현에 의존하는 이름. 입출력이 명확하지 않음. 출력물이 뭔지 이름에서 드러나지 않음. Group관련과 구현 분리 필요
 * */
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
    val groups: GroupOrganizationResult,
    val groupMerges: List<GroupMergeOutcome>,
)
