package com.homeassistant.application.port.input.group

import com.homeassistant.domain.group.GroupSplit

fun interface OrganizePendingTopics {
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
