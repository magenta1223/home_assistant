package com.homeassistant.application.port.output.topic

sealed interface TopicReference {
    data class Existing(val topicId: Int) : TopicReference {
        init { require(topicId > 0) { "topic id must be positive" } }
    }

    /** Refers to a creation declared in this batch, including a retry of an earlier creation. */
    data class Created(val creationKey: String) : TopicReference {
        init { require(creationKey.isNotBlank()) { "topic creation key is required" } }
    }
}

data class TopicCreation(val creationKey: String, val description: String) {
    init {
        require(creationKey.isNotBlank()) { "topic creation key is required" }
        require(description.isNotBlank()) { "topic description is required" }
    }
}

data class TopicMembershipAddition(val topic: TopicReference, val category: String, val memoryIds: List<Int>) {
    init {
        require(category.isNotBlank()) { "topic category is required" }
        require(memoryIds.isNotEmpty()) { "topic category must contain memories" }
        require(memoryIds.all { it > 0 }) { "memory ids must be positive" }
        require(memoryIds.distinct().size == memoryIds.size) { "category memory references must be unique" }
    }
}

data class TopicRelationAddition(val first: TopicReference, val second: TopicReference)

/** Replaces the description of an existing Topic, for example after it gained a memory. */
data class TopicDescriptionUpdate(val topicId: Int, val description: String) {
    init {
        require(topicId > 0) { "topic id must be positive" }
        require(description.isNotBlank()) { "topic description is required" }
    }
}

data class TopicWriteBatch(
    val creations: List<TopicCreation> = emptyList(),
    val memberships: List<TopicMembershipAddition> = emptyList(),
    val relations: List<TopicRelationAddition> = emptyList(),
    val descriptionUpdates: List<TopicDescriptionUpdate> = emptyList(),
) {
    init {
        require(creations.map { it.creationKey }.distinct().size == creations.size) { "topic creation keys must be unique in a batch" }
        require(descriptionUpdates.map { it.topicId }.distinct().size == descriptionUpdates.size) { "topic description updated twice in a batch" }
    }
}

data class TopicWriteResult(val createdTopicIds: Map<String, Int>)
