package com.homeassistant.application.port.outbound.embedding

import com.homeassistant.domain.group.ComponentVector

/** The embedding model and input format a stored vector was made with; vectors compare only within one space. */
data class EmbeddingSpace(val model: String, val inputFormat: String) {
    init { require(model.isNotBlank() && inputFormat.isNotBlank()) }
}

/**
 * One embedding model whose document and query vectors share [space], so a question compares with stored text.
 */
interface EmbeddingGenerator {
    val space: EmbeddingSpace

    /** True when the embedding service can be called now. */
    fun isAvailable(): Boolean

    /** The normalized vector of stored text such as an episode or a Component statement. */
    fun embedDocument(text: String): ComponentVector

    /** The normalized vector of a search question. */
    fun embedQuery(text: String): ComponentVector
}
