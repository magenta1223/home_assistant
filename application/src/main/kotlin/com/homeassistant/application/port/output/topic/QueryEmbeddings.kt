package com.homeassistant.application.port.output.topic

import com.homeassistant.domain.group.TopicVector

/** Embeds a search question into the memory embedding space, so it compares with Topic centroids. */
fun interface QueryEmbeddingGenerator {
    fun generate(query: String): TopicVector
}
