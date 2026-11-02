package com.homeassistant.application.port.output.component

import com.homeassistant.domain.component.ComponentDescription
import com.homeassistant.domain.component.ComponentViewKey

/** Writes a short human-readable description from the given episode contents, newest first, and nothing else. */
fun interface ComponentDescriptionGenerator {
    suspend fun generate(episodes: List<String>): String
}

/** Regenerable cache of descriptions, one per Component view. Losing it only costs regeneration. */
interface ComponentDescriptionStore {
    fun find(key: ComponentViewKey): ComponentDescription?

    /** Replaces the description stored for [key]. */
    fun save(key: ComponentViewKey, description: ComponentDescription)
}
