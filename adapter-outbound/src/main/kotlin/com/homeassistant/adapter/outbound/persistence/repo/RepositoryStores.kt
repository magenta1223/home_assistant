package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.application.port.outbound.source.SourceRecordRepository
import com.homeassistant.feature.memory.xmemory.port.outbound.episode.EpisodeExtractionQueue
import com.homeassistant.feature.memory.xmemory.port.outbound.episode.EpisodeRepository
import com.homeassistant.feature.sourceimport.port.outbound.SourceImportJobQueue
import com.homeassistant.feature.chat.port.outbound.ChatSessionRepository
import com.homeassistant.feature.identity.port.outbound.HouseholdRepository
import com.homeassistant.feature.identity.port.outbound.UserRepository
import com.homeassistant.feature.identity.port.outbound.UserSessionRepository
import com.homeassistant.feature.memory.xmemory.port.outbound.component.ComponentAssignmentOutbox
import com.homeassistant.feature.memory.xmemory.port.outbound.component.ComponentRepository
import com.homeassistant.feature.memory.xmemory.port.outbound.embedding.EpisodeEmbeddingRepository
import com.homeassistant.feature.memory.xmemory.port.outbound.embedding.ComponentEmbeddingRepository
import com.homeassistant.feature.memory.xmemory.port.outbound.group.GroupRepository
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
    val households: HouseholdRepository,
    val financeTransactions: FinanceTransactionRepository,
)
