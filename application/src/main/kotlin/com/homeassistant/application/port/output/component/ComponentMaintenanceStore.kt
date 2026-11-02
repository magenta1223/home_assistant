package com.homeassistant.application.port.output.component

import com.homeassistant.domain.component.Component
import com.homeassistant.domain.component.ComponentCentroid

/**
 * Centroids and slices cover episodes of every access. A user-facing caller must keep only the
 * slices the requester can read before anything leaves the use case.
 */
interface ComponentReader {
    /** Every Component's centroid. */
    fun centroids(): Map<Int, ComponentCentroid>

    /** Slices group members by each episode's current access. */
    fun read(componentIds: Collection<Int>): List<Component>
}

/** One Component an episode joins: its centroid as read, and after adding the episode. */
data class ComponentJoin(val componentId: Int, val expected: ComponentCentroid, val updated: ComponentCentroid) {
    init { require(updated.count == expected.count + 1) { "a join adds one episode" } }
}

/** Trusted ingestion/maintenance writes. This port does not grant end users mutation access. */
interface ComponentMaintenanceStore : ComponentReader {
    /**
     * Adds the episode to every Component in [joins] and replaces their centroids in one write, only while each Component
     * still has its expected episode count. Returns false and writes nothing when another join changed any first.
     */
    fun join(episodeId: Int, joins: List<ComponentJoin>): Boolean

    /** Creates a Component whose only member is [episodeId]; returns its id. */
    fun create(episodeId: Int, centroid: ComponentCentroid): Int
}
