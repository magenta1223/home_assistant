package com.homeassistant.application.usecase.component

import com.homeassistant.application.port.input.component.EpisodeAssignmentOutcome
import com.homeassistant.application.port.output.component.*
import com.homeassistant.domain.component.ComponentCentroid
import com.homeassistant.domain.component.ComponentPlacement
import com.homeassistant.domain.component.ComponentPlacementPolicy

/**
 * Places one episode into Components, as xMemory attaches a component: the episode joins every Component whose centroid
 * the policy accepts, all in one write, and starts a new Component when it accepts none.
 */
class AssignEpisodeToComponents(
    private val store: ComponentMaintenanceStore,
    private val episodeEmbeddings: PrepareEpisodeEmbedding,
    private val policy: ComponentPlacementPolicy = ComponentPlacementPolicy(),
) {
    /** True when the episode embedding can be called now. */
    fun isReady(): Boolean = episodeEmbeddings.isAvailable()

    /**
     * Places [episode] and returns the Components it joined, nearest first, or the one Component created for it.
     * @throws IllegalStateException when a concurrent join changed a chosen Component; nothing is written then.
     */
    fun execute(episode: PendingEpisode): EpisodeAssignmentOutcome.Assigned {
        val vector = episodeEmbeddings.prepare(episode).vector
        val centroids = store.centroids()
        return when (val placement = policy.decide(vector, centroids.mapValues { it.value.direction })) {
            is ComponentPlacement.Join -> {
                val joins = placement.componentIds.map { ComponentJoin(it, centroids.getValue(it), centroids.getValue(it) + vector) }
                check(store.join(episode.id, joins)) { "component changed during assignment" }
                EpisodeAssignmentOutcome.Assigned(placement.componentIds, created = false)
            }
            ComponentPlacement.Create ->
                EpisodeAssignmentOutcome.Assigned(listOf(store.create(episode.id, ComponentCentroid.of(vector))), created = true)
        }
    }
}
