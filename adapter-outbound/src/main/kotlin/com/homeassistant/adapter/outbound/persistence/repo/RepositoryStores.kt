package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.application.port.outbound.source.SourceRecordRepository
import com.homeassistant.application.port.outbound.episode.EpisodeReader
import com.homeassistant.application.port.outbound.episode.EpisodeWriter
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionStore
import com.homeassistant.application.port.outbound.identity.UserCredentialStore
import com.homeassistant.application.port.outbound.identity.UserSessionStore
import com.homeassistant.application.port.outbound.identity.UserStore
import com.homeassistant.application.port.outbound.component.*
import com.homeassistant.application.port.outbound.group.*

data class RepositoryStores(
    val sourceRecords: SourceRecordRepository,
    val episodeWriter: EpisodeWriter,
    val canonicalEpisodes: EpisodeReader,
    val componentMaintenance: ComponentMaintenanceStore,
    val pendingComponents: PendingComponentSource,
    val componentEmbeddings: ComponentEmbeddingStore,
    val groups: GroupReader,
    val groupSnapshots: GroupSnapshotReader,
    val groupChanges: GroupChangeWriter,
    val componentAssignmentOutbox: ComponentAssignmentOutbox,
    val episodeEmbeddings: EpisodeEmbeddingStore,
    val episodeConversationSessions: ChatSessionStore,
    val users: UserStore,
    val userCredentials: UserCredentialStore,
    val userSessions: UserSessionStore,
)
