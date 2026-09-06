package com.homeassistant.application.usecase.memory.conversation

import com.homeassistant.application.port.output.memory.conversation.ConversationThreadLifecycle
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSessionStore
import org.slf4j.LoggerFactory
import java.time.Clock

/** Expires idle user sessions and releases their live Codex threads. */
class ExpireIdleMemoryConversations(
    private val sessions: MemoryConversationSessionStore,
    private val threadLifecycle: ConversationThreadLifecycle,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun execute(): Int {
        val expired = sessions.expireIdle(
            beforeInclusive = clock.millis() - HandleMemoryConversation.SESSION_IDLE_TIMEOUT_MILLIS,
        )
        expired.forEach { session ->
            runCatching { threadLifecycle.end(session.conversationThreadId) }
                .onFailure {
                    log.warn(
                        "Failed to release expired Codex thread category={}",
                        it.javaClass.simpleName,
                    )
                }
        }
        if (expired.isNotEmpty()) {
            log.info("Expired idle memory conversations count={}", expired.size)
        }
        return expired.size
    }
}
