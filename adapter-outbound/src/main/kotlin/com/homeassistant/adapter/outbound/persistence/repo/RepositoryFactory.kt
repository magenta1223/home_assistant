package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.memory.MemoryRepository
import com.homeassistant.adapter.outbound.persistence.repo.memory.MemoryIndexingOutboxRepository
import com.homeassistant.adapter.outbound.persistence.repo.memoryconversation.MemoryConversationSessionRepository
import com.homeassistant.adapter.outbound.persistence.repo.identity.HouseholdMemberRepository

object RepositoryFactory {
    fun create(dbPath: String): RepositoryStores {
        val db = DatabaseFactory.init(dbPath)
        val canonicalMemories = MemoryRepository(db)
        return RepositoryStores(
            sourceRecords = SourceRecordRepositoryImpl(db),
            canonicalMemoryBatchWriter = canonicalMemories,
            memoryIndexingOutbox = MemoryIndexingOutboxRepository(db),
            canonicalMemories = canonicalMemories,
            memoryTree = canonicalMemories,
            memoryConversationSessions = MemoryConversationSessionRepository(db),
            householdMembers = HouseholdMemberRepository(db),
        )
    }
}
