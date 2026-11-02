package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.application.port.output.source.SourceRecordRepository
import com.homeassistant.application.port.output.episode.read.EpisodeReader
import com.homeassistant.application.port.output.episode.write.CanonicalEpisodeBatchWriter
import com.homeassistant.application.port.output.episode.conversation.EpisodeConversationSessionStore
import com.homeassistant.application.port.output.identity.UserCredentialStore
import com.homeassistant.application.port.output.identity.UserSessionStore
import com.homeassistant.application.port.output.identity.UserStore
import com.homeassistant.application.port.output.component.*
import com.homeassistant.application.port.output.group.*

data class RepositoryStores(
    val sourceRecords: SourceRecordRepository,
    val canonicalEpisodeBatchWriter: CanonicalEpisodeBatchWriter,
    val canonicalEpisodes: EpisodeReader,
    val componentMaintenance: ComponentMaintenanceStore,
    val pendingComponents: PendingComponentSource,
    val componentEmbeddings: ComponentEmbeddingStore,
    val groups: GroupReader,
    val groupSnapshots: GroupSnapshotReader,
    val groupChanges: GroupChangeWriter,
    val componentAssignmentOutbox: ComponentAssignmentOutbox,
    val episodeEmbeddings: EpisodeEmbeddingStore,
    val episodeConversationSessions: EpisodeConversationSessionStore,
    val users: UserStore,
    val userCredentials: UserCredentialStore,
    val userSessions: UserSessionStore,
)
