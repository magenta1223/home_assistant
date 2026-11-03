package com.homeassistant.application.usecase.episode.conversation

import com.homeassistant.application.port.inbound.component.SearchComponents
import com.homeassistant.application.port.inbound.component.ComponentMatch
import com.homeassistant.application.port.inbound.episode.search.EpisodeMatch
import com.homeassistant.application.port.inbound.episode.search.SearchEpisodes
import com.homeassistant.domain.identity.UserId
import java.time.Instant

/** Supplies the episode reference a conversation answers from. */
fun interface EpisodeConversationContextSource {
    /** The episodes [userId] can read that relate to [question], as a reference block. */
    fun context(userId: UserId, question: String): EpisodeConversationContext
}

/**
 * The reference holds one block per matched Component, then one block of the nearest episodes not already under a
 * Component, all with only the episodes the user can read. Whole lines are kept in search order up to
 * [MAX_CONTEXT_CHARS], and a header is never left without an episode under it.
 */
class EpisodeConversationContextProvider(
    private val componentSearch: SearchComponents,
    private val episodeSearch: SearchEpisodes,
) : EpisodeConversationContextSource {
    override fun context(userId: UserId, question: String): EpisodeConversationContext {
        val components = componentSearch.search(userId, question)
        val listed = components.flatMap { match -> match.episodes.map { it.episodeId } }.toSet()
        val episodes = episodeSearch.search(userId, question).filter { it.episodeId !in listed }
        val lines = components.flatMap(::componentReferenceLines) + episodeReferenceLines(episodes)
        val reference = withinBudget(lines).joinToString("\n")
        return EpisodeConversationContext(reference = reference, hasMatches = reference.isNotEmpty())
    }

    companion object {
        const val MAX_CONTEXT_CHARS = 8_000
    }
}

internal fun componentReferenceLines(match: ComponentMatch): List<String> =
    listOf("## Component ${match.componentId} [score=${match.score}]") + match.episodes.map { "- [savedAt=${Instant.ofEpochMilli(it.createdAt)}] ${it.content}" }

internal fun episodeReferenceLines(episodes: List<EpisodeMatch>): List<String> =
    if (episodes.isEmpty()) emptyList()
    else listOf("## Episodes") + episodes.map { "- [savedAt=${Instant.ofEpochMilli(it.createdAt)}] ${it.content}" }

/** The leading lines whose newline-joined length fits the budget, without a trailing header. */
private fun withinBudget(lines: List<String>): List<String> {
    val joinedLengths = lines.runningFold(-1) { length, line -> length + 1 + line.length }.drop(1)
    return lines.take(joinedLengths.count { it <= EpisodeConversationContextProvider.MAX_CONTEXT_CHARS })
        .dropLastWhile { it.startsWith("## ") }
}

/** [reference] is the text given to the provider; [hasMatches] is false when it holds no episode. */
data class EpisodeConversationContext(
    val reference: String,
    val hasMatches: Boolean,
)
