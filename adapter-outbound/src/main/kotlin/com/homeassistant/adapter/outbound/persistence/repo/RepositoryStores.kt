package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.application.port.outbound.source.SourceRecordRepository
import com.homeassistant.application.port.outbound.memory.xmemory.episode.EpisodeRepository
import com.homeassistant.application.port.outbound.memoryextraction.episode.EpisodeAnalysisJobQueue
import com.homeassistant.application.port.outbound.chatgateway.ChatSessionRepository
import com.homeassistant.application.port.outbound.identity.UserRepository
import com.homeassistant.application.port.outbound.identity.UserSessionRepository
import com.homeassistant.application.port.outbound.memory.xmemory.component.ComponentAssignmentOutbox
import com.homeassistant.application.port.outbound.memory.xmemory.component.ComponentRepository
import com.homeassistant.application.port.outbound.memory.xmemory.embedding.EpisodeEmbeddingRepository
import com.homeassistant.application.port.outbound.memory.xmemory.embedding.ComponentEmbeddingRepository
import com.homeassistant.application.port.outbound.memory.xmemory.group.GroupRepository
import com.homeassistant.application.port.outbound.finance.FinanceTransactionRepository

data class RepositoryStores(
    val sourceRecords: SourceRecordRepository,
    val episodes: EpisodeRepository,
    val episodeAnalysisJobQueue: EpisodeAnalysisJobQueue,
    val components: ComponentRepository,
    val componentEmbeddings: ComponentEmbeddingRepository,
    val groups: GroupRepository,
    val componentAssignmentOutbox: ComponentAssignmentOutbox,
    val episodeEmbeddings: EpisodeEmbeddingRepository,
    val chatSessions: ChatSessionRepository,
    val users: UserRepository,
    val userSessions: UserSessionRepository,
    val financeTransactions: FinanceTransactionRepository,
)
