package com.homeassistant.app.slack

import java.util.concurrent.ConcurrentHashMap

class InMemorySlackTopicReviewSessionStore : SlackTopicReviewSessionStore {
    private val sessions = ConcurrentHashMap<String, SlackTopicReviewSession>()

    fun put(session: SlackTopicReviewSession) {
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
