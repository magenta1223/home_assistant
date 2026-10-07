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

data class TopicWriteBatch(
    val creations: List<TopicCreation> = emptyList(),
    val memberships: List<TopicMembershipAddition> = emptyList(),
    val relations: List<TopicRelationAddition> = emptyList(),
) {
    init {
        require(creations.map { it.creationKey }.distinct().size == creations.size) { "topic creation keys must be unique in a batch" }
    }
}

data class TopicWriteResult(val createdTopicIds: Map<String, Int>)
