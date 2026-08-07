package com.homeassistant.application.topicanalysis.save

import com.homeassistant.application.memory.index.SemanticMemoryIndexWriter
import com.homeassistant.application.memory.read.CanonicalMemoryReader
import com.homeassistant.domain.identity.UserId
import com.homeassistant.domain.source.SourceDescriptor
import com.homeassistant.domain.topicanalysis.Topic
import com.homeassistant.domain.topicanalysis.TopicProposal

/** Persists analyzed topic proposals immediately without an intermediate review. */
class SaveTopicProposals(
    private val topicCreator: TopicCreator,
    memoryIndexWriter: SemanticMemoryIndexWriter,
    indexingOutbox: IndexingOutboxStore,
    canonicalMemoryReader: CanonicalMemoryReader,
) : TopicProposalSaver {
    private val memoryIndexing = MemoryIndexingCoordinator(
        canonicalMemoryReader,
        memoryIndexWriter,
        indexingOutbox,
    )

    override fun save(
        userId: UserId,
        source: SourceDescriptor,
        proposals: List<TopicProposal>,
    ): List<Topic> {
        if (proposals.isEmpty()) return emptyList()

        val savedTopics = proposals.map { proposal ->
            topicCreator.create(proposal, userId, source)
        }
        savedTopics.forEach(memoryIndexing::index)
        memoryIndexing.retryPending(
            savedTopics.flatMapTo(mutableSetOf()) { topic -> topic.memories.map { it.id } },
        )
        return savedTopics
    }
}
