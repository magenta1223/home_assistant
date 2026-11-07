package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.application.port.outbound.source.SourceRecordRepository
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeRepository
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionRepository
import com.homeassistant.application.port.outbound.identity.UserRepository
import com.homeassistant.application.port.outbound.identity.UserSessionRepository
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentAssignmentOutbox
import com.homeassistant.application.port.outbound.memoryextraction.component.ComponentRepository
import com.homeassistant.application.port.outbound.embedding.EpisodeEmbeddingRepository
import com.homeassistant.application.port.outbound.embedding.ComponentEmbeddingRepository
import com.homeassistant.application.port.outbound.memoryextraction.group.GroupRepository

data class RepositoryStores(
    val sourceRecords: SourceRecordRepository,
    val episodes: EpisodeRepository,
    val components: ComponentRepository,
    val componentEmbeddings: ComponentEmbeddingRepository,
    val groups: GroupRepository,
    val componentAssignmentOutbox: ComponentAssignmentOutbox,
    val episodeEmbeddings: EpisodeEmbeddingRepository,
    val chatSessions: ChatSessionRepository,
    val users: UserRepository,
    val userSessions: UserSessionRepository,
)
