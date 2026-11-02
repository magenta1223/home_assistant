package com.homeassistant.application.usecase.component

import com.homeassistant.application.port.output.group.EmbeddingSpace
import com.homeassistant.application.port.output.group.PendingComponent
import com.homeassistant.application.port.output.group.ComponentEmbeddingGenerator
import com.homeassistant.application.port.output.component.ComponentReader
import com.homeassistant.domain.group.ComponentVector

/**
 * Gives Groups each Component's centroid direction. Centroids live in the episode embedding [space];
 * the embedding store rejects a vector whose Component moved past the requested version.
 */
class ComponentCentroidVectors(
    private val components: ComponentReader,
    override val space: EmbeddingSpace,
) : ComponentEmbeddingGenerator {
    override fun generate(component: PendingComponent): ComponentVector =
        requireNotNull(components.read(listOf(component.id)).singleOrNull()) { "component missing" }.centroid.direction
}
