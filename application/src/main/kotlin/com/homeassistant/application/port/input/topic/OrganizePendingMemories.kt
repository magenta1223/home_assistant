package com.homeassistant.application.port.input.topic

import com.homeassistant.application.port.input.group.GroupOrganizationResult
import com.homeassistant.application.port.input.group.GroupRefreshOutcome
import com.homeassistant.domain.group.GroupSplit

/** Organizes unorganized Memories into Topics, describes changed Topics, then organizes Topics into Groups. */
fun interface OrganizePendingMemories {
    fun execute(limit: Int): MemoryOrganizationResult
}

sealed interface MemoryOrganizationOutcome {
    data class Organized(val topicIds: List<Int>, val split: GroupSplit) : MemoryOrganizationOutcome
    data object AlreadyOrganized : MemoryOrganizationOutcome
    data object ConflictExhausted : MemoryOrganizationOutcome
    data class Failed(val reason: String) : MemoryOrganizationOutcome
}

data class MemoryOrganizationEntry(val memoryId: Int, val outcome: MemoryOrganizationOutcome)

sealed interface TopicDescriptionOutcome {
    val topicId: Int
    data class Described(override val topicId: Int) : TopicDescriptionOutcome
    /** Membership changed after the summary was requested; the next run describes the new revision. */
    data class Superseded(override val topicId: Int) : TopicDescriptionOutcome
    data class Failed(override val topicId: Int, val reason: String) : TopicDescriptionOutcome
}

sealed interface MemoryOrganizationResult {
    val memories: List<MemoryOrganizationEntry>

    data class Completed(
        override val memories: List<MemoryOrganizationEntry>,
        val descriptions: List<TopicDescriptionOutcome>,
        val groupRefreshes: List<GroupRefreshOutcome>,
        val groups: GroupOrganizationResult,
    ) : MemoryOrganizationResult

    /** The memory vector model, input format or dimension changed; nothing after the memory stage ran. */
    data class RebuildRequired(override val memories: List<MemoryOrganizationEntry>, val reason: String) : MemoryOrganizationResult
}
