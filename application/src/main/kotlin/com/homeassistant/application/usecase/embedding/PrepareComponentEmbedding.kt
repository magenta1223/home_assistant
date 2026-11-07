package com.homeassistant.application.usecase.embedding

import com.homeassistant.application.port.outbound.embedding.Embedding
import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace
import com.homeassistant.application.port.outbound.embedding.ComponentEmbeddingKey
import com.homeassistant.application.port.outbound.embedding.ComponentEmbeddingRepository
import com.homeassistant.domain.component.Component

/**
 * Provides a Component's vector for Group organization, generating and storing it only when its statement is not stored yet.
 * The vector embeds the Component's statement as a document, so Components compare with episodes and questions.
 */
class PrepareComponentEmbedding(
    private val generator: EmbeddingGenerator,
    private val store: ComponentEmbeddingRepository,
) {
    /** The model and input format every prepared vector belongs to. */
    val space: EmbeddingSpace get() = generator.space

    /** The stored vector of [component]'s statement in [space], generated and stored first when missing. */
    fun prepare(component: Component): Embedding<ComponentEmbeddingKey> {
        val key = ComponentEmbeddingKey.of(component.id, component.statement, space)
        return store.find(key) ?: store.save(Embedding(key, generator.embedDocument(component.statement)))
    }
}
