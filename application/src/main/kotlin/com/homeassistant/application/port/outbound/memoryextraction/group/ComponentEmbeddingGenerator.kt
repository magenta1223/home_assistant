package com.homeassistant.application.port.outbound.memoryextraction.group

import com.homeassistant.domain.group.ComponentVector

interface ComponentEmbeddingGenerator {
    val space: EmbeddingSpace
    /** The vector of [component]; the store rejects it if the Component is no longer at [PendingComponent.version]. */
    fun generate(component: PendingComponent): ComponentVector
}