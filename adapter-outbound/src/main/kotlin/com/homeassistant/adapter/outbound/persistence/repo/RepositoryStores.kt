package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.application.port.outbound.source.SourceRecordRepository
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeRepository
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionStore
import com.homeassistant.application.port.outbound.identity.UserCredentialStore
import com.homeassistant.application.port.outbound.identity.UserSessionStore
import com.homeassistant.application.port.outbound.identity.UserStore
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentAssignmentOutbox
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentRepository
import com.homeassistant.application.port.outbound.memoryextraction.component.EpisodeEmbeddingStore
import com.homeassistant.application.port.outbound.memoryextraction.group.ComponentEmbeddingStore
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupRepository
import com.homeassistant.application.port.outbound.memoryextraction.group.PendingComponentSource

data class RepositoryStores(
    val sourceRecords: SourceRecordRepository,
    val episodes: EpisodeRepository,
    val components: ComponentRepository,
    val pendingComponents: PendingComponentSource,
    val componentEmbeddings: ComponentEmbeddingStore,
    val groups: GroupRepository,
    val componentAssignmentOutbox: ComponentAssignmentOutbox,
    val episodeEmbeddings: EpisodeEmbeddingStore,
    val episodeConversationSessions: ChatSessionStore,
    val users: UserStore,
    val userCredentials: UserCredentialStore,
    val userSessions: UserSessionStore,
)
