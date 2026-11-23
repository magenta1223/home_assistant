package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.memory.xmemory.episode.EpisodeExtractionQueueRepository
import com.homeassistant.adapter.outbound.persistence.repo.memory.xmemory.episode.EpisodeRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.sourceimport.SourceImportJobQueueRepository
import com.homeassistant.adapter.outbound.persistence.repo.episodeconversation.ChatSessionRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.identity.HouseholdRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.identity.UserRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.identity.UserSessionRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.memory.xmemory.embedding.EpisodeEmbeddingRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.memory.xmemory.component.ComponentAssignmentOutboxRepository
import com.homeassistant.adapter.outbound.persistence.repo.memory.xmemory.component.ComponentRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.memory.xmemory.group.GroupRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.memory.xmemory.embedding.ComponentEmbeddingRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.finance.FinanceTransactionRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.finance.PaymentMethodSettingRepositoryImpl

object RepositoryFactory {
    fun create(dbPath: String): RepositoryStores {
        val db = DatabaseFactory.init(dbPath)
        val households = HouseholdRepositoryImpl(db)
        return RepositoryStores(
            sourceRecords = SourceRecordRepositoryImpl(db),
            episodes = EpisodeRepositoryImpl(db),
            episodeExtractionQueue = EpisodeExtractionQueueRepository(db),
            sourceImportJobQueue = SourceImportJobQueueRepository(db),
            components = ComponentRepositoryImpl(db),
            componentEmbeddings = ComponentEmbeddingRepositoryImpl(db),
            groups = GroupRepositoryImpl(db),
            componentAssignmentOutbox = ComponentAssignmentOutboxRepository(db),
            episodeEmbeddings = EpisodeEmbeddingRepositoryImpl(db),
            chatSessions = ChatSessionRepositoryImpl(db),
            users = UserRepositoryImpl(db),
            userSessions = UserSessionRepositoryImpl(db),
            households = households,
            financeTransactions = FinanceTransactionRepositoryImpl(db),
            householdMembers = households,
            paymentMethodSettings = PaymentMethodSettingRepositoryImpl(db),
        )
    }
}
