package com.homeassistant.core.session

import com.github.benmanes.caffeine.cache.Caffeine
import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.models.Message
import com.homeassistant.core.nlp.MessageRole
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit


class SessionManager(timeoutMinutes: Long = AppConfig.SESSION_TIMEOUT_MINUTES) {
    private val log = LoggerFactory.getLogger(SessionManager::class.java)

    private val cache = Caffeine.newBuilder()
        .expireAfterAccess(timeoutMinutes, TimeUnit.MINUTES)
        .build<SessionKey, CopyOnWriteArrayList<Message>>()

    fun getMessages(key: SessionKey): List<Message> =
        cache.getIfPresent(key)?.toList() ?: emptyList()

    fun addMessage(key: SessionKey, role: MessageRole, content: String) {
        val messages = cache.get(key) { CopyOnWriteArrayList() }
        messages.add(Message(role, content))
        log.debug("Session [{}] +{} (total={})", key, role, messages.size)
    }

    fun resetSession(key: SessionKey) {
        cache.invalidate(key)
        log.info("Session [$key] reset")
    }
}
