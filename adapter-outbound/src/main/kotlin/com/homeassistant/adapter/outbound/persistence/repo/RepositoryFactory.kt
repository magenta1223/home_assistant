package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.indexing.IndexingOutboxRepository
import com.homeassistant.adapter.outbound.persistence.repo.topicanalysis.TopicRepository
import com.homeassistant.adapter.outbound.persistence.repo.memory.MemoryRepository
import com.homeassistant.adapter.outbound.persistence.repo.slackconversation.SlackCodexSessionRepository

object RepositoryFactory {
    fun create(dbPath: String): RepositoryStores {
        val db = DatabaseFactory.init(dbPath)
        val canonicalMemories = MemoryRepository(db)
        return RepositoryStores(
            sourceRecords = SourceRecordRepositoryImpl(db),
            topicCreator = TopicRepository(db),
            canonicalMemories = canonicalMemories,
            indexingOutbox = IndexingOutboxRepository(db),
            slackCodexSessions = SlackCodexSessionRepository(db),
        )
    }
}
