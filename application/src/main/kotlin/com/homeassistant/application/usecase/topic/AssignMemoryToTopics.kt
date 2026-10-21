package com.homeassistant.application.usecase.topic

import com.homeassistant.application.port.input.topic.MemoryAssignmentOutcome
import com.homeassistant.application.port.output.topic.*
import com.homeassistant.domain.topic.TopicCentroid
import com.homeassistant.domain.topic.TopicPlacement
import com.homeassistant.domain.topic.TopicPlacementPolicy

/**
 * Places one memory into Topics, as xMemory attaches a component: the memory joins every Topic whose centroid
 * the policy accepts, all in one write, and starts a new Topic when it accepts none.
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
                val joins = placement.topicIds.map { TopicJoin(it, centroids.getValue(it), centroids.getValue(it) + vector) }
                check(store.join(memory.id, joins)) { "topic changed during assignment" }
                MemoryAssignmentOutcome.Assigned(placement.topicIds, created = false)
            }
            TopicPlacement.Create ->
                MemoryAssignmentOutcome.Assigned(listOf(store.create(memory.id, TopicCentroid.of(vector))), created = true)
        }
    }
}
