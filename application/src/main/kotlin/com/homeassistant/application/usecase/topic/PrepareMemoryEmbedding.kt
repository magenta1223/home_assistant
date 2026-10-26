package com.homeassistant.application.usecase.topic

import com.homeassistant.application.port.output.group.EmbeddingSpace
import com.homeassistant.application.port.output.topic.*

/** Provides a memory's vector for Topic placement, generating and storing it only when its content is not stored yet. */
class PrepareMemoryEmbedding(
    private val generator: MemoryEmbeddingGenerator,
    private val store: MemoryEmbeddingStore,
) {
    /** The model and input format every prepared vector belongs to. */
    val space: EmbeddingSpace get() = generator.space

    /** True when the embedding generator can be called now. */
    fun isAvailable(): Boolean = generator.isAvailable()

    /** The stored vector of [memory]'s content in [space], generated and stored first when missing. */
    fun prepare(memory: PendingMemory): MemoryEmbedding {
        val key = memory.embeddingKey(space)
        return store.find(key) ?: store.save(MemoryEmbedding(key, generator.generate(memory.content)))
    }
}
