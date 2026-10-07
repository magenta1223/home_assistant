package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.memory.MemoryRepository
import com.homeassistant.adapter.outbound.persistence.repo.memory.MemoryIndexingOutboxRepository
import com.homeassistant.adapter.outbound.persistence.repo.memoryconversation.MemoryConversationSessionRepository
import com.homeassistant.adapter.outbound.persistence.repo.identity.UserRepository
import com.homeassistant.adapter.outbound.persistence.repo.memoryanswer.PendingRegistrationQuestionRepository
import com.homeassistant.adapter.outbound.persistence.repo.topic.TopicRepository

object RepositoryFactory {
    fun create(dbPath: String): RepositoryStores {
        val db = DatabaseFactory.init(dbPath)
        val canonicalMemories = MemoryRepository(db)
        val topics = TopicRepository(db)
        return RepositoryStores(
            sourceRecords = SourceRecordRepositoryImpl(db),
            canonicalMemoryBatchWriter = canonicalMemories,
            memoryIndexingOutbox = MemoryIndexingOutboxRepository(db),
            canonicalMemories = canonicalMemories,
            memoryTree = canonicalMemories,
            topicMaintenance = topics,
            topicPlacement = topics,
            memoryConversationSessions = MemoryConversationSessionRepository(db),
            users = UserRepository(db),
            pendingRegistrationQuestions = PendingRegistrationQuestionRepository(db),
        )
    }
}
