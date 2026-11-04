package com.homeassistant.application.usecase.memoryextraction.component

import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentReader
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeEmbeddingGenerator
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingGenerator
import com.homeassistant.application.port.outbound.memoryextraction.group.PendingComponent
import com.homeassistant.application.port.outbound.memoryextraction.group.descriptionFingerprint
import com.homeassistant.domain.component.Component
import com.homeassistant.domain.group.ComponentVector

/** Embeds each Component's statement in the episode embedding space, so Components compare with episodes and questions. */
class ComponentStatementVectors(
    private val components: ComponentReader,
    private val texts: EpisodeEmbeddingGenerator,
) : ComponentEmbeddingGenerator {
    override val space = texts.space

    override fun generate(component: PendingComponent): ComponentVector =
        texts.generate(requireNotNull(components.read(listOf(component.id)).singleOrNull()) { "component missing" }.statement)
}

/** A statement never changes, so its fingerprint is the only vector version a Component has. */
fun Component.pending(): PendingComponent = PendingComponent(id, descriptionFingerprint(statement))
