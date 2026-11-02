package com.homeassistant.application.usecase.component

import com.homeassistant.application.port.output.component.ComponentReader
import com.homeassistant.application.port.output.component.EpisodeEmbeddingGenerator
import com.homeassistant.application.port.output.group.ComponentEmbeddingGenerator
import com.homeassistant.application.port.output.group.PendingComponent
import com.homeassistant.application.port.output.group.descriptionFingerprint
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
