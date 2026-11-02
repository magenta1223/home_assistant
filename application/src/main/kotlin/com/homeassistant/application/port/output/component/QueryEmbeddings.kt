package com.homeassistant.application.port.output.component

import com.homeassistant.domain.group.ComponentVector

/** Embeds a search question into the episode embedding space, so it compares with Component statements. */
fun interface QueryEmbeddingGenerator {
    fun generate(query: String): ComponentVector
}
