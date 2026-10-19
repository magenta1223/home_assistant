package com.homeassistant.application.usecase.topic

import com.homeassistant.application.port.input.topic.MemoryAssignmentOutcome
import com.homeassistant.application.port.output.group.PendingTopic
import com.homeassistant.application.port.output.topic.*
import com.homeassistant.application.usecase.group.PrepareTopicEmbedding
import com.homeassistant.domain.group.GroupGeometry

/**
 * Places one memory into every Topic it belongs to. Embeddings narrow the existing Topics to a few
 * candidates; the model chooses among them, revises the joined descriptions or proposes new Topics.
 */
class AssignMemoryToTopics(
    private val catalog: TopicCatalog,
    private val memoryEmbeddings: PrepareMemoryEmbedding,
    private val topicEmbeddings: PrepareTopicEmbedding,
    private val generator: TopicAssignmentGenerator,
    private val store: TopicMaintenanceStore,
    private val candidateLimit: Int = 5,
) {
    init { require(candidateLimit > 0) }

    fun execute(memory: PendingMemory): MemoryAssignmentOutcome.Assigned {
        val candidates = nearestTopics(memory)
        val decision = generator.assign(TopicAssignmentRequest(memory, candidates))
        val candidateIds = candidates.map { it.topicId }.toSet()
        require(decision.joins.all { it.topicId in candidateIds }) { "model joined a topic that was not a candidate" }
        val keys = creationKeys(memory, decision)
        val created = store.apply(batchFor(memory, decision, keys)).createdTopicIds
        return MemoryAssignmentOutcome.Assigned(decision.joins.map { it.topicId }, keys.map { created.getValue(it) })
    }

    private fun nearestTopics(memory: PendingMemory): List<TopicCandidate> {
        val topics = catalog.topics()
        if (topics.isEmpty()) return emptyList()
        val vector = memoryEmbeddings.prepare(memory).vector
        return topics.associateWith { topic ->
            GroupGeometry.cosine(vector, topicEmbeddings.prepare(PendingTopic(topic.topicId, topic.description)).vector)
        }.entries.sortedByDescending { it.value }.take(candidateLimit).map { it.key }
    }

    private fun batchFor(memory: PendingMemory, decision: TopicAssignmentDecision, keys: List<String>): TopicWriteBatch =
        TopicWriteBatch(
            creations = decision.creations.zip(keys) { topic, key -> TopicCreation(key, topic.description) },
            memberships = decision.joins.map { TopicMembershipAddition(TopicReference.Existing(it.topicId), it.category, listOf(memory.id)) } +
                decision.creations.zip(keys) { topic, key -> TopicMembershipAddition(TopicReference.Created(key), topic.category, listOf(memory.id)) },
            descriptionUpdates = decision.joins.map { TopicDescriptionUpdate(it.topicId, it.revisedDescription) },
        )

    /** Creation keys derive from the memory, so a retried write reuses the Topics it already created. */
    private fun creationKeys(memory: PendingMemory, decision: TopicAssignmentDecision): List<String> =
        decision.creations.indices.map { "memory:${memory.id}:$it" }
}
