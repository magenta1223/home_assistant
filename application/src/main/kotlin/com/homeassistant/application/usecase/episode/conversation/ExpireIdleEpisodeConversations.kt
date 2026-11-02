package com.homeassistant.application.usecase.episode.conversation

import com.homeassistant.application.port.output.episode.conversation.ConversationGateway
import com.homeassistant.application.port.output.episode.conversation.EpisodeConversationSessionStore
import org.slf4j.LoggerFactory
import java.time.Clock

/** Expires idle user sessions and releases their provider conversations. */
class ExpireIdleEpisodeConversations(
    private val sessions: EpisodeConversationSessionStore,
    private val conversationGateway: ConversationGateway,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Expires sessions idle for [HandleEpisodeConversation.SESSION_IDLE_TIMEOUT_MILLIS] and returns how many expired. */
    fun execute(): Int {
        val expired = sessions.expireIdle(
            beforeInclusive = clock.millis() - HandleEpisodeConversation.SESSION_IDLE_TIMEOUT_MILLIS,
        )
        expired.forEach { session ->
            runCatching { conversationGateway.end(session.conversationId) }
                .onFailure {
                    log.warn(
                        "Failed to release expired conversation category={}",
                        it.javaClass.simpleName,
                    )
                }
        }
        if (expired.isNotEmpty()) {
            log.info("Expired idle episode conversations count={}", expired.size)
        }
        return expired.size
    }
}
