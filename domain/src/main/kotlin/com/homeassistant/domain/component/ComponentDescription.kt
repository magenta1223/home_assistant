package com.homeassistant.domain.component

import com.homeassistant.domain.episode.EpisodeAccess

/**
 * Human-readable text for one [ComponentView], written from that view's episodes only. It is a regenerable
 * read model and never part of the Component. [versions] are the slice sizes the text was written from.
 */
data class ComponentDescription(val text: String, val versions: Map<EpisodeAccess, Int>) {
    init {
        require(text.isNotBlank()) { "component description must not be blank" }
        require(versions.isNotEmpty() && versions.values.all { it > 0 }) { "component description must cover episodes" }
    }
}

sealed interface ComponentDescriptionRefresh {
    data object Generate : ComponentDescriptionRefresh
    data class Reuse(val description: ComponentDescription) : ComponentDescriptionRefresh
}

/**
 * Keeps a view's previous description until its episodes grew by [growthThreshold] of those it was written from.
 * A view that lost episodes always regenerates, so the text never keeps content the view no longer covers.
 */
class ComponentDescriptionRefreshPolicy(private val growthThreshold: Double = 0.2) {
    init { require(growthThreshold.isFinite() && growthThreshold > 0.0) { "growthThreshold must be positive" } }

    fun decide(cached: ComponentDescription?, view: ComponentView): ComponentDescriptionRefresh {
        if (cached == null) return ComponentDescriptionRefresh.Generate
        val current = view.versions
        if (cached.versions.any { (slice, count) -> (current[slice] ?: 0) < count }) return ComponentDescriptionRefresh.Generate
        val described = cached.versions.values.sum()
        val added = current.values.sum() - described
        return if (added >= described * growthThreshold) ComponentDescriptionRefresh.Generate
        else ComponentDescriptionRefresh.Reuse(cached)
    }
}
