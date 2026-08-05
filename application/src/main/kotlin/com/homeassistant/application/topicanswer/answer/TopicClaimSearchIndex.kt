package com.homeassistant.application.topicanswer.answer

import com.homeassistant.core.identity.HouseholdAccessScope
import com.homeassistant.domain.topicanalysis.Topic

data class TopicClaimSearchHit(
    val topicId: Int,
    val claimId: Int,
    val score: Double,
)

interface TopicClaimSearchIndex {
    fun index(topic: Topic)
    fun search(scope: HouseholdAccessScope, question: String, limit: Int): List<TopicClaimSearchHit>
}

private object UnavailableTopicClaimSearchIndex : TopicClaimSearchIndex {
    override fun index(topic: Topic) {
        // Indexing is skipped until a real embedding provider is wired.
    }

    override fun search(
        scope: HouseholdAccessScope,
        question: String,
        limit: Int,
    ): List<TopicClaimSearchHit> =
        throw TopicClaimSearchIndexUnavailableException("topic claim vector index is not configured")
}

object TopicClaimSearchIndexes {
    fun unavailable(): TopicClaimSearchIndex =
        UnavailableTopicClaimSearchIndex
}

class TopicClaimSearchIndexUnavailableException(message: String) : RuntimeException(message)
