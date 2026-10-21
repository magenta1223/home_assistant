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

/** One Topic a memory joins: its centroid as read, and after adding the memory. */
data class TopicJoin(val topicId: Int, val expected: TopicCentroid, val updated: TopicCentroid) {
    init { require(updated.count == expected.count + 1) { "a join adds one memory" } }
}

/** Trusted ingestion/maintenance writes. This port does not grant end users mutation access. */
interface TopicMaintenanceStore : TopicReader {
    /**
     * Adds the memory to every Topic in [joins] and replaces their centroids in one write, only while each Topic
     * still has its expected memory count. Returns false and writes nothing when another join changed any first.
     */
    fun join(memoryId: Int, joins: List<TopicJoin>): Boolean

    /** Creates a Topic whose only member is [memoryId]; returns its id. */
    fun create(memoryId: Int, centroid: TopicCentroid): Int
}
