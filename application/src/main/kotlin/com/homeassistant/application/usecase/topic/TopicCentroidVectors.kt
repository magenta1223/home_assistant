package com.homeassistant.application.usecase.topic

import com.homeassistant.application.port.output.group.EmbeddingSpace
import com.homeassistant.application.port.output.group.PendingTopic
import com.homeassistant.application.port.output.group.TopicEmbeddingGenerator
import com.homeassistant.application.port.output.topic.TopicMaintenanceStore
import com.homeassistant.domain.group.TopicVector

/**
 * Gives Groups each Topic's centroid direction. Centroids live in the memory embedding [space];
 * the embedding store rejects a vector whose Topic moved past the requested version.
 */
class TopicCentroidVectors(
    private val topics: TopicMaintenanceStore,
    override val space: EmbeddingSpace,
) : TopicEmbeddingGenerator {
    override fun generate(topic: PendingTopic): TopicVector =
        requireNotNull(topics.readForMaintenance(listOf(topic.id)).singleOrNull()) { "topic missing" }.centroid.direction
}
