package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.memory.MemoryRepository
import com.homeassistant.adapter.outbound.persistence.repo.memory.MemoryIndexingOutboxRepository
import com.homeassistant.adapter.outbound.persistence.repo.memoryconversation.MemoryConversationSessionRepository
import com.homeassistant.adapter.outbound.persistence.repo.identity.UserRepository
import com.homeassistant.adapter.outbound.persistence.repo.topic.MemoryEmbeddingRepository
import com.homeassistant.adapter.outbound.persistence.repo.topic.TopicOrganizationRepository
import com.homeassistant.adapter.outbound.persistence.repo.topic.TopicRepository
import com.homeassistant.adapter.outbound.persistence.repo.group.GroupRepository
import com.homeassistant.adapter.outbound.persistence.repo.group.TopicEmbeddingRepository

object RepositoryFactory {
    fun create(dbPath: String): RepositoryStores {
        val db = DatabaseFactory.init(dbPath)
        val canonicalMemories = MemoryRepository(db)
        val topics = TopicRepository(db)
        val groups = GroupRepository(db)
        val topicOrganization = TopicOrganizationRepository(db)
        return RepositoryStores(
            sourceRecords = SourceRecordRepositoryImpl(db),
            canonicalMemoryBatchWriter = canonicalMemories,
            memoryIndexingOutbox = MemoryIndexingOutboxRepository(db),
            canonicalMemories = canonicalMemories,
            topicMaintenance = topics,
            pendingTopics = groups,
            topicEmbeddings = TopicEmbeddingRepository(db),
            groupSnapshots = groups,
            groupChanges = groups,
            pendingMemories = topicOrganization,
            memoryEmbeddings = MemoryEmbeddingRepository(db),
            topicOrganizationSnapshots = topicOrganization,
            topicOrganizationChanges = topicOrganization,
            pendingTopicDescriptions = topicOrganization,
            memoryConversationSessions = MemoryConversationSessionRepository(db),
            users = UserRepository(db),
        )
    }
}
