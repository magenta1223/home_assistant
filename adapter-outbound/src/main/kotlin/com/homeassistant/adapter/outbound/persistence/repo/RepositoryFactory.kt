package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.adapter.outbound.persistence.db.DatabaseFactory
import com.homeassistant.adapter.outbound.persistence.repo.source.SourceRecordRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.episode.EpisodeRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.episodeconversation.ChatSessionRepository
import com.homeassistant.adapter.outbound.persistence.repo.identity.UserCredentialRepository
import com.homeassistant.adapter.outbound.persistence.repo.identity.UserRepository
import com.homeassistant.adapter.outbound.persistence.repo.identity.UserSessionRepository
import com.homeassistant.adapter.outbound.persistence.repo.component.EpisodeEmbeddingRepository
import com.homeassistant.adapter.outbound.persistence.repo.component.ComponentAssignmentOutboxRepository
import com.homeassistant.adapter.outbound.persistence.repo.component.ComponentRepositoryImpl
import com.homeassistant.adapter.outbound.persistence.repo.group.GroupRepository
import com.homeassistant.adapter.outbound.persistence.repo.group.ComponentEmbeddingRepository

object RepositoryFactory {
    fun create(dbPath: String): RepositoryStores {
        val db = DatabaseFactory.init(dbPath)
        val groups = GroupRepository(db)
        return RepositoryStores(
            sourceRecords = SourceRecordRepositoryImpl(db),
            episodes = EpisodeRepositoryImpl(db),
            components = ComponentRepositoryImpl(db),
            pendingComponents = groups,
            componentEmbeddings = ComponentEmbeddingRepository(db),
            groups = groups,
            groupSnapshots = groups,
            groupChanges = groups,
            componentAssignmentOutbox = ComponentAssignmentOutboxRepository(db),
            episodeEmbeddings = EpisodeEmbeddingRepository(db),
            episodeConversationSessions = ChatSessionRepository(db),
            users = UserRepository(db),
            userCredentials = UserCredentialRepository(db),
            userSessions = UserSessionRepository(db),
        )
    }
}
