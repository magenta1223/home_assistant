package com.homeassistant.application.port.output.topic

import com.homeassistant.domain.topic.Topic
import com.homeassistant.domain.topic.TopicCentroid

/**
 * Trusted ingestion/maintenance only. Centroids and slices cover memories of every access, so reads
 * must never be used as user-facing projections or answer context. This port does not grant end users mutation access.
 */
interface TopicMaintenanceStore {
    /** Every Topic's centroid, for placing a new memory. */
    fun centroids(): Map<Int, TopicCentroid>

    /** Slices group members by each memory's current access. */
    fun readForMaintenance(topicIds: Collection<Int>): List<Topic>

    /**
     * Adds the memory and replaces the centroid with [updated], only while the Topic still has
     * [expected]'s memory count. Returns false when another join changed the Topic first.
     */
    fun join(topicId: Int, memoryId: Int, expected: TopicCentroid, updated: TopicCentroid): Boolean

    /** Creates a Topic whose only member is [memoryId]; returns its id. */
    fun create(memoryId: Int, centroid: TopicCentroid): Int
}
