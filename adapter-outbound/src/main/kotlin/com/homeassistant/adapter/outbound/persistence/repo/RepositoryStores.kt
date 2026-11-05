package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.application.port.outbound.source.SourceRecordRepository
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeReader
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeWriter
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionStore
import com.homeassistant.application.port.outbound.identity.UserCredentialStore
import com.homeassistant.application.port.outbound.identity.UserSessionStore
import com.homeassistant.application.port.outbound.identity.UserStore
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentAssignmentOutbox
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentReader
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentWriter
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeEmbeddingStore
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingStore
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupChangeWriter
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupReader
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupSnapshotReader
import com.homeassistant.application.port.outbound.memoryextraction.group.PendingComponentSource

data class RepositoryStores(
    val sourceRecords: SourceRecordRepository,
    val episodeWriter: EpisodeWriter,
    val episodeReader: EpisodeReader,
    val componentReader: ComponentReader,
    val componentWriter: ComponentWriter,
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
