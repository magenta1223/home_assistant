package com.homeassistant.application.port.outbound.memoryextraction.component

/** Extracts long-term knowledge from an episode, as xMemory extracts components with prediction and correction. */
fun interface ComponentKnowledgeExtractor {
    /**
     * Statements of long-term knowledge in [episode] that [known] does not already state; empty when there is none.
     * With [known] empty, every long-term statement in the episode is returned.
     */
    fun extract(episode: PendingEpisode, known: List<String>): List<String>
}
