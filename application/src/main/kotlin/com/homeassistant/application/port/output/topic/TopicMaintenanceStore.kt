package com.homeassistant.application.port.output.topic

import com.homeassistant.domain.topic.Topic
import com.homeassistant.domain.topic.TopicCentroid

/**
 * Centroids and slices cover memories of every access. A user-facing caller must keep only the
 * slices the requester can read before anything leaves the use case.
 */
interface TopicReader {
    /** Every Topic's centroid. */
    fun centroids(): Map<Int, TopicCentroid>

    /** Slices group members by each memory's current access. */
    fun read(topicIds: Collection<Int>): List<Topic>
}

/** Trusted ingestion/maintenance writes. This port does not grant end users mutation access. */
interface TopicMaintenanceStore : TopicReader {
    /**
     * Adds the memory and replaces the centroid with [updated], only while the Topic still has
     * [expected]'s memory count. Returns false when another join changed the Topic first.
     */
    fun join(topicId: Int, memoryId: Int, expected: TopicCentroid, updated: TopicCentroid): Boolean

    /** Creates a Topic whose only member is [memoryId]; returns its id. */
    fun create(memoryId: Int, centroid: TopicCentroid): Int
}
