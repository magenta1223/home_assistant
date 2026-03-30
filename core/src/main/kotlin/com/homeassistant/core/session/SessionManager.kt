package com.homeassistant.core.session

import com.github.benmanes.caffeine.cache.Caffeine
import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.models.ConversationMessage
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit


class SessionManager(timeoutMinutes: Long = AppConfig.SESSION_TIMEOUT_MINUTES) {
    private val log = LoggerFactory.getLogger(SessionManager::class.java)

    private val cache = Caffeine.newBuilder()
        .expireAfterAccess(timeoutMinutes, TimeUnit.MINUTES)
        .build<SessionKey, CopyOnWriteArrayList<ConversationMessage>>()

    fun getMessages(key: SessionKey): List<ConversationMessage> =
        cache.getIfPresent(key)?.toList() ?: emptyList()

    fun addMessage(key: SessionKey, role: String, content: String) {
        val messages = cache.get(key) { CopyOnWriteArrayList() }
        messages.add(ConversationMessage(role, content))
        log.debug("Session [{}] +{} (total={})", key, role, messages.size)
    }

    fun resetSession(key: SessionKey) {
        cache.invalidate(key)
        log.info("Session [$key] reset")
    }
}
