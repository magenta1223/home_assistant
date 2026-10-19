package com.homeassistant.application.port.output.topic

/** An existing Topic offered to the model as a place for a memory. */
data class TopicCandidate(val topicId: Int, val description: String) {
    init { require(topicId > 0 && description.isNotBlank()) }
}

/** Trusted internal listing of every Topic; descriptions may summarize mixed-ACL memories. */
fun interface TopicCatalog {
    fun topics(): List<TopicCandidate>
}

data class TopicAssignmentRequest(val memory: PendingMemory, val candidates: List<TopicCandidate>)

/** The memory joins [topicId] under [category]; [revisedDescription] also covers the memory. */
data class TopicJoin(val topicId: Int, val category: String, val revisedDescription: String) {
    init { require(topicId > 0 && category.isNotBlank() && revisedDescription.isNotBlank()) }
}

data class NewTopic(val description: String, val category: String) {
    init { require(description.isNotBlank() && category.isNotBlank()) }
}

/** A memory may belong to several Topics; the decision places it in at least one. */
data class TopicAssignmentDecision(val joins: List<TopicJoin>, val creations: List<NewTopic>) {
    init {
        require(joins.isNotEmpty() || creations.isNotEmpty()) { "memory must be assigned to a topic" }
        require(joins.map { it.topicId }.distinct().size == joins.size) { "topic joined twice" }
    }
}

/** Decides which candidate Topics a memory belongs to and which new Topics it needs. */
fun interface TopicAssignmentGenerator {
    fun assign(request: TopicAssignmentRequest): TopicAssignmentDecision
}
