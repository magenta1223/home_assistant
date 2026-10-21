package com.homeassistant.domain.topic

import com.homeassistant.domain.memory.MemoryAccess

/**
 * Human-readable text for one [TopicView], written from that view's memories only. It is a regenerable
 * read model and never part of the Topic. [versions] are the slice sizes the text was written from.
 */
data class TopicDescription(val text: String, val versions: Map<MemoryAccess, Int>) {
    init {
        require(text.isNotBlank()) { "topic description must not be blank" }
        require(versions.isNotEmpty() && versions.values.all { it > 0 }) { "topic description must cover memories" }
    }
}

sealed interface TopicDescriptionRefresh {
    data object Generate : TopicDescriptionRefresh
    data class Reuse(val description: TopicDescription) : TopicDescriptionRefresh
}

/**
 * Keeps a view's previous description until its memories grew by [growthThreshold] of those it was written from.
 * A view that lost memories always regenerates, so the text never keeps content the view no longer covers.
 */
class TopicDescriptionRefreshPolicy(private val growthThreshold: Double = 0.2) {
    init { require(growthThreshold.isFinite() && growthThreshold > 0.0) { "growthThreshold must be positive" } }

    fun decide(cached: TopicDescription?, view: TopicView): TopicDescriptionRefresh {
        if (cached == null) return TopicDescriptionRefresh.Generate
        val current = view.versions
        if (cached.versions.any { (slice, count) -> (current[slice] ?: 0) < count }) return TopicDescriptionRefresh.Generate
        val described = cached.versions.values.sum()
        val added = current.values.sum() - described
        return if (added >= described * growthThreshold) TopicDescriptionRefresh.Generate
        else TopicDescriptionRefresh.Reuse(cached)
    }
}
