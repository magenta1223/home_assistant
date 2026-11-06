package com.homeassistant.application.usecase.memoryextraction.group

import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentRepository
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbedding
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingStore
import com.homeassistant.application.port.outbound.memoryextraction.group.PendingComponent
import com.homeassistant.application.port.outbound.memoryextraction.group.descriptionFingerprint
import com.homeassistant.domain.component.Component

/**
 * Provides a Component's vector for Group organization, generating and storing it only when its version is not stored yet.
 * The vector embeds the Component's statement as a document, so Components compare with episodes and questions.
 */
class PrepareComponentEmbedding(
    private val components: ComponentRepository,
    private val generator: EmbeddingGenerator,
    private val store: ComponentEmbeddingStore,
) {
    /** The model and input format every prepared vector belongs to. */
    val space: EmbeddingSpace get() = generator.space

    /** The stored vector of [component]'s version in [space], generated and stored first when missing. */
    fun prepare(component: PendingComponent): ComponentEmbedding {
        val key = component.embeddingKey(space)
        return store.find(key) ?: store.save(ComponentEmbedding(key, generator.embedDocument(statementOf(component))))
    }

    private fun statementOf(component: PendingComponent): String =
        requireNotNull(components.findByIds(listOf(component.id)).singleOrNull()) { "component missing" }.statement
}

/** A statement never changes, so its fingerprint is the only vector version a Component has. */
fun Component.pending(): PendingComponent = PendingComponent(id, descriptionFingerprint(statement))
