package com.homeassistant.application.topicanalysis.save

import com.homeassistant.domain.indexing.IndexTargetType
import com.homeassistant.domain.indexing.IndexingOutboxStore
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicAnalysisQueryStore
import com.homeassistant.domain.topicanswer.TopicClaimSearchIndex
import org.slf4j.LoggerFactory

internal interface TopicIndexingCoordinator {
    fun index(topic: Topic): Boolean
    fun retryPending(currentTopicIds: Set<Int>)
}

internal object TopicIndexingCoordinatorFactory {
    fun create(
        topicStore: TopicAnalysisQueryStore,
        searchIndex: TopicClaimSearchIndex,
        outbox: IndexingOutboxStore,
    ): TopicIndexingCoordinator = DefaultTopicIndexingCoordinator(topicStore, searchIndex, outbox)
}

private class DefaultTopicIndexingCoordinator(
    private val topicStore: TopicAnalysisQueryStore,
    private val searchIndex: TopicClaimSearchIndex,
    private val outbox: IndexingOutboxStore,
) : TopicIndexingCoordinator {
    override fun index(topic: Topic): Boolean =
        try {
            searchIndex.index(topic)
            outbox.markIndexed(IndexTargetType.TOPIC, topic.id)
            true
        } catch (error: Exception) {
            log.warn("Topic vector indexing deferred topicId=${topic.id}", error)
            runCatching {
                outbox.markFailed(
                    IndexTargetType.TOPIC,
                    topic.id,
                    error.message ?: error::class.simpleName.orEmpty(),
                )
            }
            false
        }

    override fun retryPending(currentTopicIds: Set<Int>) {
        runCatching {
            val pending = outbox.pending(IndexTargetType.TOPIC).filterNot(currentTopicIds::contains)
            topicStore.getApprovedTopicsForIndexing(pending).forEach(::index)
        }.onFailure { error ->
            log.warn("Failed to dispatch pending topic indexes", error)
        }
    }
}

private val log = LoggerFactory.getLogger(TopicIndexingCoordinator::class.java)
