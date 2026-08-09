package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.domain.source.SourceRecordRepository
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.application.port.output.memory.write.CanonicalMemoryBatchWriter
import com.homeassistant.application.port.output.memory.write.MemoryIndexingOutbox
import com.homeassistant.application.port.output.memory.conversation.MemoryConversationSessionStore
import com.homeassistant.application.port.output.memory.placement.MemoryTreeStore
import com.homeassistant.application.port.output.identity.HouseholdMemberStore

data class RepositoryStores(
    val sourceRecords: SourceRecordRepository,
    val canonicalMemoryBatchWriter: CanonicalMemoryBatchWriter,
    val memoryIndexingOutbox: MemoryIndexingOutbox,
    val canonicalMemories: MemoryReader,
    val memoryTree: MemoryTreeStore,
    val memoryConversationSessions: MemoryConversationSessionStore,
    val householdMembers: HouseholdMemberStore,
)
