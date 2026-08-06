package com.homeassistant.adapter.inbound.slack

import java.util.concurrent.ConcurrentHashMap

internal class InMemorySlackReviewContextStore : SlackReviewContextStore {
    private val contexts = ConcurrentHashMap<String, SlackReviewContext>()

    override fun save(context: SlackReviewContext) {
        contexts[context.reviewId] = context
    }

    override fun find(reviewId: String): SlackReviewContext? = contexts[reviewId]

    override fun markCompleted(previewId: String) {
        contexts.computeIfPresent(previewId) { _, context ->
            context.copy(status = SlackReviewStatus.COMPLETED)
        }
    }
}

object SlackReviewContextStoreFactory {
    fun inMemory(): SlackReviewContextStore = InMemorySlackReviewContextStore()
}
