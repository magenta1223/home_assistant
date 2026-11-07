package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.episodeconversation.ChatReceiptRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.episodeconversation.ChatSessionRepository
import com.homeassistant.adapter.outbound.persistence.repo.identity.UserRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.identity.UserSessionRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.embedding.EpisodeEmbeddingRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.component.ComponentAssignmentOutboxRepository
import com.homeassistant.adapter.outbound.persistence.repo.component.ComponentRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.group.GroupRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.embedding.ComponentEmbeddingRepositoryImpl

object RepositoryFactory {
    fun create(dbPath: String): RepositoryStores {
        val db = DatabaseFactory.init(dbPath)
        return RepositoryStores(
            sourceRecords = SourceRecordRepositoryImpl(db),
            episodes = EpisodeRepositoryImpl(db),
            components = ComponentRepositoryImpl(db),
            componentEmbeddings = ComponentEmbeddingRepositoryImpl(db),
            groups = GroupRepositoryImpl(db),
            componentAssignmentOutbox = ComponentAssignmentOutboxRepository(db),
            episodeEmbeddings = EpisodeEmbeddingRepositoryImpl(db),
            chatReceipts = ChatReceiptRepositoryImpl(db),
            chatSessions = ChatSessionRepository(db),
            users = UserRepositoryImpl(db),
            userSessions = UserSessionRepositoryImpl(db),
        )
    }
}
