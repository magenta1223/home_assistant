package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.application.port.outbound.source.SourceRecordRepository
import com.homeassistant.application.port.outbound.memory.xmemory.episode.EpisodeExtractionQueue
import com.homeassistant.application.port.outbound.memory.xmemory.episode.EpisodeRepository
import com.homeassistant.feature.sourceimport.port.outbound.SourceImportJobQueue
import com.homeassistant.feature.chat.port.outbound.ChatSessionRepository
import com.homeassistant.feature.identity.port.outbound.UserRepository
import com.homeassistant.feature.identity.port.outbound.UserSessionRepository
import com.homeassistant.application.port.outbound.memory.xmemory.component.ComponentAssignmentOutbox
import com.homeassistant.application.port.outbound.memory.xmemory.component.ComponentRepository
import com.homeassistant.application.port.outbound.memory.xmemory.embedding.EpisodeEmbeddingRepository
import com.homeassistant.application.port.outbound.memory.xmemory.embedding.ComponentEmbeddingRepository
import com.homeassistant.application.port.outbound.memory.xmemory.group.GroupRepository
import com.homeassistant.feature.finance.port.outbound.FinanceTransactionRepository

data class RepositoryStores(
    val sourceRecords: SourceRecordRepository,
    val episodes: EpisodeRepository,
    val episodeExtractionQueue: EpisodeExtractionQueue,
    val sourceImportJobQueue: SourceImportJobQueue,
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
