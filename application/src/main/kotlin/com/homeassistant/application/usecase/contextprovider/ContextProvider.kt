package com.homeassistant.application.usecase.contextprovider

import com.homeassistant.application.usecase.contextprovider.component.ComponentSearcher
import com.homeassistant.application.usecase.contextprovider.episode.EpisodeSearcher
import com.homeassistant.domain.component.Component
import com.homeassistant.domain.episode.Episode
import com.homeassistant.domain.identity.UserId
import java.time.Instant

/**
 * The reference holds, as xMemory gives it to the reader, a block of the matched Components' statements, then a block of
 * the nearest episodes with the time each was saved, all with only what the user can read. Whole lines are kept in
 * search order up to [MAX_CONTEXT_CHARS], and a header is never left without a line under it.
 */
class ContextProvider(
    private val componentSearch: ComponentSearcher,
    private val episodeSearch: EpisodeSearcher,
) : ContextSource {
    override fun context(userId: UserId, question: String): Context {
        val lines = componentReferenceLines(componentSearch.search(userId, question)) +
            episodeReferenceLines(episodeSearch.search(userId, question))
        val reference = withinBudget(lines).joinToString("\n")
        return Context(reference = reference, hasMatches = reference.isNotEmpty())
    }

    private fun componentReferenceLines(components: List<Component>): List<String> =
        if (components.isEmpty()) emptyList()
        else listOf("## Components") + components.map { "- ${it.statement}" }

    private fun episodeReferenceLines(episodes: List<Episode>): List<String> =
        if (episodes.isEmpty()) emptyList()
        else listOf("## Episodes") + episodes.map { "- [savedAt=${Instant.ofEpochMilli(it.createdAt)}] ${it.content}" }

    /** The leading lines whose newline-joined length fits the budget, without a trailing header. */
    private fun withinBudget(lines: List<String>): List<String> {
        val joinedLengths = lines.runningFold(-1) { length, line -> length + 1 + line.length }.drop(1)
        return lines.take(joinedLengths.count { it <= ContextProvider.MAX_CONTEXT_CHARS })
            .dropLastWhile { it.startsWith("## ") }
    }

    companion object {
        const val MAX_CONTEXT_CHARS = 8_000
    }
}
