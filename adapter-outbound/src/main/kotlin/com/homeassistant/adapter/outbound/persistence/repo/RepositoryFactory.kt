package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.memory.xmemory.episode.EpisodeExtractionQueueRepository
import com.homeassistant.adapter.outbound.persistence.repo.memory.xmemory.episode.EpisodeRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeAnalysisJobQueueRepository
import com.homeassistant.adapter.outbound.persistence.repo.episodeconversation.ChatSessionRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.identity.UserRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.identity.UserSessionRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.memory.xmemory.embedding.EpisodeEmbeddingRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.memory.xmemory.component.ComponentAssignmentOutboxRepository
import com.homeassistant.adapter.outbound.persistence.repo.memory.xmemory.component.ComponentRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.memory.xmemory.group.GroupRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.memory.xmemory.embedding.ComponentEmbeddingRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.finance.FinanceTransactionRepositoryImpl

object RepositoryFactory {
    fun create(dbPath: String): RepositoryStores {
        val db = DatabaseFactory.init(dbPath)
        return RepositoryStores(
            sourceRecords = SourceRecordRepositoryImpl(db),
            episodes = EpisodeRepositoryImpl(db),
            episodeExtractionQueue = EpisodeExtractionQueueRepository(db),
            episodeAnalysisJobQueue = EpisodeAnalysisJobQueueRepository(db),
            components = ComponentRepositoryImpl(db),
            componentEmbeddings = ComponentEmbeddingRepositoryImpl(db),
            groups = GroupRepositoryImpl(db),
            componentAssignmentOutbox = ComponentAssignmentOutboxRepository(db),
            episodeEmbeddings = EpisodeEmbeddingRepositoryImpl(db),
            chatSessions = ChatSessionRepositoryImpl(db),
            users = UserRepositoryImpl(db),
            userSessions = UserSessionRepositoryImpl(db),
            financeTransactions = FinanceTransactionRepositoryImpl(db),
        )
    }
}
