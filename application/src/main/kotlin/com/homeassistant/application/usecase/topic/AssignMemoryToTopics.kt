package com.homeassistant.application.usecase.topic

import com.homeassistant.application.port.input.topic.MemoryAssignmentOutcome
import com.homeassistant.application.port.output.topic.*
import com.homeassistant.domain.topic.TopicCentroid
import com.homeassistant.domain.topic.TopicPlacement
import com.homeassistant.domain.topic.TopicPlacementPolicy

/**
 * Places one memory into one Topic, as xMemory attaches a component: the memory joins the Topic whose
 * centroid is nearest when the policy accepts it, and starts a new Topic otherwise.
 */
class AssignMemoryToTopics(
    private val store: TopicMaintenanceStore,
    private val memoryEmbeddings: PrepareMemoryEmbedding,
    private val policy: TopicPlacementPolicy = TopicPlacementPolicy(),
) {
    /** True when the memory embedding can be called now. */
    fun isReady(): Boolean = memoryEmbeddings.isAvailable()

    fun execute(memory: PendingMemory): MemoryAssignmentOutcome.Assigned {
        val vector = memoryEmbeddings.prepare(memory).vector
        val centroids = store.centroids()
        return when (val placement = policy.decide(vector, centroids.mapValues { it.value.direction })) {
            is TopicPlacement.Join -> {
                val current = centroids.getValue(placement.topicId)
                check(store.join(placement.topicId, memory.id, current, current + vector)) { "topic changed during assignment" }
                MemoryAssignmentOutcome.Assigned(placement.topicId, created = false)
            }
            TopicPlacement.Create -> MemoryAssignmentOutcome.Assigned(store.create(memory.id, TopicCentroid.of(vector)), created = true)
        }
    }
}
