package com.homeassistant.adapter.inbound.slack

import java.util.concurrent.ConcurrentHashMap

internal class InMemorySlackTopicReviewSessionStore : SlackTopicReviewSessionStore {
    private val sessions = ConcurrentHashMap<String, SlackTopicReviewSession>()

    override fun save(session: SlackTopicReviewSession) {
        sessions[session.previewId] = session
    }

    override fun find(previewId: String): SlackTopicReviewSession? =
        sessions[previewId]

    override fun markCompleted(previewId: String) {
        sessions.computeIfPresent(previewId) { _, session ->
            session.copy(status = SlackTopicReviewStatus.COMPLETED)
        }
    }
}

object SlackTopicReviewSessionStoreFactory {
    fun inMemory(): SlackTopicReviewSessionStore =
        InMemorySlackTopicReviewSessionStore()
}
