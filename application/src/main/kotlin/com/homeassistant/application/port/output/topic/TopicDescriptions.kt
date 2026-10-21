package com.homeassistant.application.port.output.topic

import com.homeassistant.domain.topic.TopicDescription
import com.homeassistant.domain.topic.TopicViewKey

/** Writes a short human-readable description from the given memory contents, newest first, and nothing else. */
fun interface TopicDescriptionGenerator {
    suspend fun generate(memories: List<String>): String
}

/** Regenerable cache of descriptions, one per Topic view. Losing it only costs regeneration. */
interface TopicDescriptionStore {
    fun find(key: TopicViewKey): TopicDescription?

    /** Replaces the description stored for [key]. */
    fun save(key: TopicViewKey, description: TopicDescription)
}
