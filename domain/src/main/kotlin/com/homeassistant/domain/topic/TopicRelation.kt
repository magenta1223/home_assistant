package com.homeassistant.domain.topic

/** One undirected relation. Canonical endpoint order also makes reverse proposals equal. */
data class TopicRelation(
    val firstTopicId: Int,
    val secondTopicId: Int,
) {
    init {
        require(firstTopicId > 0 && secondTopicId > 0) { "topic ids must be positive" }
        require(firstTopicId < secondTopicId) { "topic relation endpoints must be distinct and ordered" }
    }

    companion object {
        fun between(topicId: Int, relatedTopicId: Int): TopicRelation =
            TopicRelation(minOf(topicId, relatedTopicId), maxOf(topicId, relatedTopicId))
    }
}
