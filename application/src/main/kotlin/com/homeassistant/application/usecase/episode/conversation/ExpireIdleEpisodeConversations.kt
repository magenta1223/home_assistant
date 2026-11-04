package com.homeassistant.application.usecase.episode.conversation

import com.homeassistant.application.port.outbound.episode.conversation.ChatGateway
import com.homeassistant.application.port.outbound.episode.conversation.EpisodeConversationSessionStore
import org.slf4j.LoggerFactory
import java.time.Clock

/** Expires idle user sessions and releases their provider conversations. */
class ExpireIdleEpisodeConversations(
    private val sessions: EpisodeConversationSessionStore,
    private val chatGateway: ChatGateway,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Expires sessions idle for [HandleConversation.SESSION_IDLE_TIMEOUT_MILLIS] and returns how many expired. */
    fun execute(): Int {
        val expired = sessions.expireIdle(
            beforeInclusive = clock.millis() - HandleConversation.SESSION_IDLE_TIMEOUT_MILLIS,
        )
        expired.forEach { session ->
            runCatching { chatGateway.end(session.chatId) }
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
