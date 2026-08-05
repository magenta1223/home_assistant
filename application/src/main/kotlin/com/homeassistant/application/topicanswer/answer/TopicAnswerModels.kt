package com.homeassistant.application.topicanswer.answer

import com.homeassistant.domain.identity.FamilyId
import com.homeassistant.domain.identity.HouseholdAccessScope
import com.homeassistant.domain.identity.UserId
import kotlinx.serialization.Serializable

@Serializable
data class TopicAnswerRequest(
    val userId: String,
    val familyId: String,
    val question: String,
    val limit: Int = 5,
) {
    fun scope(): HouseholdAccessScope =
        HouseholdAccessScope(UserId(userId), FamilyId(familyId))
}

@Serializable
data class TopicAnswerResult(
    val question: String,
    val answer: String,
    val matches: List<TopicAnswerMatch>,
)

@Serializable
data class TopicAnswerMatch(
    val topicId: Int,
    val title: String,
    val summary: String,
    val claims: List<String>,
    val evidenceRefs: List<Int>,
)
