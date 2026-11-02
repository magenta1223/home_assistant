package com.homeassistant.application.usecase.group

import com.homeassistant.application.port.output.group.*

/** Provides a Component's vector for Group organization, generating and storing it only when its version is not stored yet. */
class PrepareComponentEmbedding(
    private val generator: ComponentEmbeddingGenerator,
    private val store: ComponentEmbeddingStore,
) {
    /** The model and input format every prepared vector belongs to. */
    val space: EmbeddingSpace get() = generator.space

    /** The stored vector of [component]'s version in [space], generated and stored first when missing. */
    fun prepare(component: PendingComponent): ComponentEmbedding {
        val key = component.embeddingKey(space)
        return store.find(key) ?: store.save(ComponentEmbedding(key, generator.generate(component)))
    }
}
