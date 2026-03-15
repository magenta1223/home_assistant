package com.homeassistant.session

import com.github.benmanes.caffeine.cache.Caffeine
import com.homeassistant.constants.AppConfig
import com.homeassistant.models.ConversationMessage
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger(SessionManager::class.java)

/**
 * Session key that uniquely identifies a conversation across platforms.
 * DM on Slack: SessionKey("slack", "DM-U123456")
 * Family channel: SessionKey("slack", "C-CHANNEL1")
 * Discord: SessionKey("discord", "SERVER_ID-CHANNEL_ID")
 */
data class SessionKey(val platform: String, val conversationId: String)

/**
 * In-memory session store with Caffeine TTL.
 * Each entry expires 10 minutes after the last access, mirroring the TypeScript SessionManager.
 */
class SessionManager(timeoutMinutes: Long = AppConfig.SESSION_TIMEOUT_MINUTES) {

    private val cache = Caffeine.newBuilder()
        .expireAfterAccess(timeoutMinutes, TimeUnit.MINUTES)
        .build<SessionKey, CopyOnWriteArrayList<ConversationMessage>>()

    /** Returns a snapshot of the conversation history, or empty list if expired/missing. */
    fun getMessages(key: SessionKey): List<ConversationMessage> =
        cache.getIfPresent(key)?.toList() ?: emptyList()

    /** Appends a message to the session, creating the session if needed. */
    fun addMessage(key: SessionKey, role: String, content: String) {
        val messages = cache.get(key) { CopyOnWriteArrayList() }
        messages.add(ConversationMessage(role, content))
        log.debug("Session [$key] +$role (total=${messages.size})")
    }

    /** Clears the session (called after a command is successfully executed). */
    fun resetSession(key: SessionKey) {
        cache.invalidate(key)
        log.info("Session [$key] reset")
    }
}
