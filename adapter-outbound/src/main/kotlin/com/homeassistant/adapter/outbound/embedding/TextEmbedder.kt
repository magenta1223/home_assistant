package com.homeassistant.adapter.outbound.embedding

/** Converts text into a vector representation for semantic retrieval. */
interface TextEmbedder {
    /** Embeds text as a numeric vector. */
    fun embed(text: String): List<Float>
}
