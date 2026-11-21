package com.homeassistant.feature.chat.usecase

import com.homeassistant.application.port.inbound.chat.ExpireIdleChats
import com.homeassistant.feature.chat.port.outbound.ChatGateway
import com.homeassistant.feature.chat.port.outbound.ChatSessionDeactivation
import com.homeassistant.feature.chat.port.outbound.ChatSessionRepository
import org.slf4j.LoggerFactory
import java.time.Clock

/** Expires idle user sessions and releases their provider conversations. */
class ExpireIdleChatsService(
    private val sessions: ChatSessionRepository,
    private val chatGateway: ChatGateway,
    private val clock: Clock = Clock.systemUTC(),
) : ExpireIdleChats {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Expires sessions idle for [ChatService.SESSION_IDLE_TIMEOUT_MILLIS] and returns how many expired. */
    override fun execute(): Int {
        val expired = sessions.save(
            ChatSessionDeactivation.IdleSince(clock.millis() - ChatService.SESSION_IDLE_TIMEOUT_MILLIS),
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
