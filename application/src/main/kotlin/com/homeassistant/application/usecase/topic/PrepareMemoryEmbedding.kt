package com.homeassistant.application.usecase.topic

import com.homeassistant.application.port.output.group.EmbeddingSpace
import com.homeassistant.application.port.output.topic.*

class PrepareMemoryEmbedding(
    private val generator: MemoryEmbeddingGenerator,
    private val store: MemoryEmbeddingStore,
) {
    val space: EmbeddingSpace get() = generator.space

    fun isAvailable(): Boolean = generator.isAvailable()

    fun prepare(memory: PendingMemory): MemoryEmbedding {
        val key = memory.embeddingKey(space)
        return store.find(key) ?: store.save(MemoryEmbedding(key, generator.generate(memory.content)))
    }
}
