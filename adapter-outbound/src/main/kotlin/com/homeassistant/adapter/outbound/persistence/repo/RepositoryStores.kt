package com.homeassistant.adapter.outbound.persistence.repo

import com.homeassistant.domain.source.SourceRecordRepository
import com.homeassistant.application.port.output.memory.read.MemoryReader
import com.homeassistant.application.port.output.memory.write.MemoryWriter
import com.homeassistant.application.port.output.slackconversation.SlackConversationSessionStore
import com.homeassistant.application.port.output.memory.placement.MemoryTreeStore

data class RepositoryStores(
    val sourceRecords: SourceRecordRepository,
    val memoryWriter: MemoryWriter,
    val canonicalMemories: MemoryReader,
    val memoryTree: MemoryTreeStore,
    val slackCodexSessions: SlackConversationSessionStore,
)
