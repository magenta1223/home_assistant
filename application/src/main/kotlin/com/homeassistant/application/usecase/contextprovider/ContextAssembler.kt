package com.homeassistant.application.usecase.contextprovider

import com.homeassistant.domain.identity.UserId

/**
 * Joins the sections of every provider into one reference, earlier providers first. Whole lines are kept in that order
 * up to [MAX_CONTEXT_CHARS], and a title is never left without an entry under it.
 */
class ContextAssembler(private val providers: List<ContextProvider>) : ContextSource {
    override fun context(userId: UserId, question: String): ChatContext {
        val lines = providers.flatMap { it.sections(userId, question) }.flatMap(::referenceLines)
        val reference = withinBudget(lines).joinToString("\n")
        return ChatContext(reference = reference, hasMatches = reference.isNotEmpty())
    }

    private fun referenceLines(section: ContextSection): List<String> =
        listOf("$TITLE_MARK${section.title}") + section.entries.map { "- $it" }

    /** The leading lines whose newline-joined length fits the budget, without a trailing title. */
    private fun withinBudget(lines: List<String>): List<String> {
        val joinedLengths = lines.runningFold(-1) { length, line -> length + 1 + line.length }.drop(1)
        return lines.take(joinedLengths.count { it <= MAX_CONTEXT_CHARS })
            .dropLastWhile { it.startsWith(TITLE_MARK) }
    }

    companion object {
        const val MAX_CONTEXT_CHARS = 8_000
        private const val TITLE_MARK = "## "
    }
}
